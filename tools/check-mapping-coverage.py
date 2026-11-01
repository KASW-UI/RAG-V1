#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 docs/Mapping_Table/ 各域文档的 **B / C 两列覆盖率**。

**不信任文档里的自报数字** —— 本脚本从文件系统反向取实际文件清单，
再逐个回查文档正文里是否出现过该文件名 ⇒ 独立于写文档的一方。

⚠️ **2026-11-02 · D16 基准切换**：判定基准改为「**B 侧（RAG 仓）是唯一真理**」⇒
   映射表由 **三方列** 改为 **B(RAG)|C(RAG-V1) 两方** ⇒
   ① **原第三列已物理删除**（D16 落地时删除）⇒ 本脚本的第三列判据**全部移除**；
   ② **B 列从第 2 格变成第 1 格** ⇒ 取 B 列的下标 **1 → 0**；
   ③ **主表列数 5 → 4**（B|C|类型|备注）⇒ 「映射类型」列下标 **3 → 2**；
   ④ **B 列从「参考列」升级为主判据** ⇒ 新增**按域**的 B 列覆盖率统计。

判读：
- **C 列缺失** = RAG-V1 有这个 `.java`，但映射文档里找不到 ⇒ **文档漏了位置**（要补）
- **B 列漏记** = RAG 有这个 `.java`，但 B 列没覆盖 ⇒ **抄写路线图（§1.0c）会漏掉它**
- **B 列失效** = B 列写了这个类，但 RAG 侧已改名/删除 ⇒ **文档引用了不存在的类**

八重校验（全部从文件系统反查，不采信文档自报数）：
  ① B 列覆盖率（按域）  ② C 列覆盖率  ③ `_INDEX.md` 自报数字对账
  ④ 小节标题自报条数 == 表内实际行数  ⑤ B 列双向对账（正向存在性 + 反向覆盖）
  ⑥ B↔C 指代消解后可判定性（「同上」是确定性指代 ⇒ 消解后每行都要读得出）
  ⑦ 主表行形状（列数必须为 4）  ⑧ `_INDEX.md` 合计行对账

用法：
    python3 tools/check-mapping-coverage.py            # 全部域
    python3 tools/check-mapping-coverage.py hot like    # 指定域
"""
import pathlib, re, sys

ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1")
RAG  = pathlib.Path("/Users/pr/Documents/java&golang/RAG")
MT   = ROOT / "docs/Mapping_Table"

# 域 → (RAG-V1 路径, RAG 仓路径, 文档文件)
# ⚠️ 文档按域分目录：`docs/Mapping_Table/<域>/<域>.md`；`common` 是全局共享层 ⇒ 留在目录根。
# ⚠️ **一个域可以有多份文档**（users 域拆成 `_shared.md` + `users-common.md` + `user/` + `admin/`）
#    ⇒ 文档字段支持字符串或元组（元组 = 多份文档拼起来一起查覆盖）。
#
# ℹ️ **打平后的结构（2026-10-26）**：RAG-V1 侧每域**一个** jar 模块（`service/<域>/`），
#    各域的 `*-contract` 模块已并入域模块 ⇒ **本表的 RAG-V1 路径不变**。
#    ⚠️ 根级 `proto/` 模块**不在本表**：它只有 `.proto`（无手写 Java），pb 类生成到 `target/`
#    ⇒ 被 `java_files()` 的 target 过滤排除 ⇒ proto 契约由 `tools/difftest/check-proto-static.sh` 负责。
DOMAINS = {
    "users":    ("service/users",              "service/users",
                 ("users/_shared.md", "users/users-common.md", "users/user/user.md", "users/admin/admin.md")),
    "common":   ("common",                     "service/common",  "common.md"),
    "article":  ("service/article",            "service/article", "article/article.md"),
    "comment":  ("service/comment",            "service/comment", "comment/comment.md"),
    "favorite": ("service/favorite",           "service/favorite","favorite/favorite.md"),
    "follow":   ("service/follow",             "service/follow",  "follow/follow.md"),
    "hot":      ("service/hot",                "service/hot",     "hot/hot.md"),
    "like":     ("service/like",               "service/like",    "like/like.md"),
    "message":  ("service/message",            "service/message", "message/message.md"),
    "task":     ("service/task",               "service/task",    "task/task.md"),
}

# 「无对应物」的免责措辞（文档若写了这些，说明是有意标注，不算缺失）
EXEMPT_PATTERNS = [
    r"无对应物", r"无 C 落点", r"没有对应", r"刻意为空", r"故意为空",
    r"无 Java 类", r"无 C 列", r"B 侧无", r"不迁", r"已删除", r"B 侧不存在",
]

# 映射类型词表（4 个，与 docs/Mapping_Table/README.MD 的格式契约一致）
TYPE_TOKENS = {"1:1", "1:N", "N:1", "C 新增"}

# ── B 列白名单 ───────────────────────────────────────────────────────────
# 下列 RAG 侧真实存在的类，**B 列没有对应条目** —— 逐条核对后登记，不静默放过。
#
# ⚠️ **白名单不是免责声明，是待办清单**：每条必须写清「为什么 B 列可以不写」+ 核对日期。
#    脚本会检查「已失效条目」（该类后来被 B 列覆盖了却还挂在白名单）⇒ 防止白名单腐化。
#
# 格式：类名 -> (原因, 核对日期)
# ⚠️ **D16（2026-11-02）：本表现在是空的** —— 原 4 条白名单**全部失效**：
#    ① `TaskTraceAttributes` —— task 域的 B 列已覆盖它；
#    ② `ArticleGoStyleCoercion` / `ArticleGoStyleCorsFilter` —— article 域的模块级目录 token
#       （`service/article/article-api`）已覆盖它们；
#    ③ `GoStyleRpcSheddingInterceptor` —— common 域的 `service/common` 目录 token 已覆盖它。
#    ⇒ 根因 = **目录 token 的匹配语义曾是坏的**（把包名与类名相比 ⇒ 永远不命中）⇒ 已修为前缀语义。
#    ⇒ **白名单只保留「B 列确实无法覆盖」的类**；新增前先确认目录 token 真的覆盖不到。
B_COLUMN_WHITELIST = {
}

# ── 非文件级对照登记（第 ⑥ 重校验用）───────────────────────────────────
# **背景**：B↔C 的行级对应里有若干行写「同上」—— 「同上」是**确定性指代**
#   （B / C 各自独立向上取最近一个非「同上」的值，零歧义）⇒ 消解后**每行都能读出含义**。
#   消解后仍读不出「某个 `.java` 的落点」的行 ⇒ 逐条核对后确认：
#   它们是**非文件级**对照（模块 / 进程 / 集合 / 内部类 / 方法 / 清单 / 配置），或**明确写了「无」**。
#
# ⚠️ 本表是**登记制**：消解后新出现的不可判定行**必须**登记在这里，否则第 ⑥ 重校验报红；
#    已登记但后来变得可判定的行 ⇒ 报「登记已失效」⇒ **防登记腐化**。
#
# ⚠️ **2026-11-02 · D16**：键从「A 列文本」改为「**B 列文本**」（A 列已删）⇒
#    B 列会随实现推进变化 ⇒ 键不再是稳定的 ⇒ 登记条目须定期复核。
#
# 键 = (文档相对路径, B 列归一化文本[:60])
# 格式：(文档, B 列) -> (形态, 原因, 核对日期)
NON_FILE_LEVEL = {
    ('favorite/favorite.md', '—（B侧无此DTO）'): ('明确「无」', 'B 侧无此 DTO ⇒ 明确否定', '2026-11-02'),
    ('follow/follow.md', '8个单端点Service（另有FollowApiSupport+AbstractFollowApiService两个支撑'): ('集合级', 'B 侧 8 个 logic 合计 ⇒ C 侧 2 个 Service，是集合级对照', '2026-11-02'),
    ('follow/follow.md', 'follow-proto/（protoc生成物，11message+FollowServiceGrpc）'): ('proto/生成物级', 'B 侧 protoc 生成物 ⇒ 不逐个手写类', '2026-11-02'),
    ('follow/follow.md', '（无手写类；用follow-proto生成的FollowServiceGrpc.FollowServiceBlockin'): ('明确「无」', 'B 侧 goctl 桩 ⇒ C 侧无手写类', '2026-11-02'),
    ('hot/hot.md', 'service/common/（普通jar）'): ('模块/包级', '模块级对照（C 侧 common/ 收成 starter）', '2026-11-02'),
    ('like/like.md', 'like-api/'): ('模块/包级', '模块级对照（进程拓扑）', '2026-11-02'),
    ('like/like.md', 'like-common/'): ('模块/包级', '模块级对照（common/ 收成 starter）', '2026-11-02'),
    ('like/like.md', 'like-rpc/'): ('模块/包级', '模块级对照（进程拓扑）', '2026-11-02'),
    ('message/message.md', '2个（message-api/message-rpc）'): ('模块/包级', '进程拓扑对照（非文件级）', '2026-11-02'),
    ('message/message.md', 'MessageErrorCode（enum，21常量+SUCCESS）'): ('成员/方法级', 'B 侧 const 块 ⇒ 成员级对照', '2026-11-02'),
    ('message/message.md', 'MessageErrorMessages（静态map）'): ('成员/方法级', 'B 侧包级 map ⇒ 成员级对照', '2026-11-02'),
    ('message/message.md', 'MessageGrpcError.CodeError（内部类）'): ('成员/方法级', 'B 侧内部 struct ⇒ 成员级对照', '2026-11-02'),
    ('message/message.md', 'MessageGrpcError.bizCodeFromError(Throwable)'): ('成员/方法级', 'B 侧包级函数 ⇒ 方法级对照', '2026-11-02'),
    ('message/message.md', 'MessageGrpcError.grpcError(Status.Code,int)'): ('成员/方法级', 'B 侧包级函数 ⇒ 方法级对照', '2026-11-02'),
    ('message/message.md', 'service/common/（普通jar）'): ('模块/包级', '模块级对照（C 侧 common/ 收成 starter）', '2026-11-02'),
    ('users/admin/admin.md', '（无独立文件）'): ('明确「无」', 'B 侧 goctl 桩 ⇒ C 侧无独立类', '2026-11-02'),
    ('users/user/user.md', '（无独立文件）'): ('明确「无」', 'B 侧 goctl 桩 ⇒ C 侧无独立类', '2026-11-02'),
    ('users/users-common.md', 'users-common/.../http/GoStyleRequired.java+GoStyleRequiredFi'): ('明确「无」', 'B 侧内置必填校验 ⇒ C 侧 D5 改 Bean Validation', '2026-11-02'),
}


def count_map_rows(text):
    """数「映射行」—— **必须扫全行找类型词，不能固定列下标**。

    ⚠️ **踩过的坑**：`follow` / `like` / `task` 三域存在**比标准表多一列**的表，
    映射类型落在第 4 / 5 / 6 个 `|` 之后而非第 3 个 ⇒ 固定取 `parts[3]` 会**漏算 60 行**。

    判据：非空单元格 ≥ 3（标准映射表是 B|C|类型|备注 = 4 列；
    ⚠️ **备注列允许为空** ⇒ 阈值若取 4 会漏算「| B | C | 类型 | |」这类行；
    §「类型分布」小节表只有 类型|条数|说明 = 3 列 ⇒ 天然排除），
    且类型词出现在**第 3 列或更靠后**（即它在「类型」列而不是 B 列）。
    """
    n = 0
    for ln in text.splitlines():
        if not ln.startswith("|"):
            continue
        cells = [c.strip() for c in ln.split("|") if c.strip()]
        if len(cells) < 3:
            continue
        for i, c in enumerate(cells):
            if c in TYPE_TOKENS and i >= 2:
                n += 1
                break
    return n


def parse_index_table():
    """解析 `_INDEX.md` 的覆盖度表 ⇒ {域: (行数, C_分子, C_分母, B_分子, B_分母, 映射行)}。

    ⚠️ **不采信它** —— 只用于「与文件系统实测值比对」，不一致即报。
    """
    idx = MT / "_INDEX.md"
    if not idx.exists():
        return {}
    out = {}
    for ln in idx.read_text(encoding="utf-8").splitlines():
        if not ln.startswith("| **"):
            continue
        cells = [c.strip() for c in ln.split("|") if c.strip()]
        if len(cells) < 6:
            continue
        dom = cells[0].strip("*")
        if dom in ("合计", "域"):
            continue
        if dom not in DOMAINS:
            continue

        def num(s):
            s = s.replace(",", "")
            m = re.findall(r"\d+", s)
            return int(m[0]) if m else None

        # 行数格：users 是 "378 + 178 + 215 + 240 = **1,011**" ⇒ 取 "=" 之后的值
        line_cell = cells[2]
        if "=" in line_cell:
            line_cell = line_cell.split("=")[-1]
        lines = num(line_cell)
        c_frac = num(cells[3].split("/")[0])
        c_tot = num(cells[3].split("/")[1]) if "/" in cells[3] else None
        b_frac = num(cells[4].split("/")[0])
        b_tot = num(cells[4].split("/")[1]) if "/" in cells[4] else None
        rows_n = num(cells[5])
        out[dom] = (lines, c_frac, c_tot, b_frac, b_tot, rows_n)
    return out


def parse_index_totals():
    """解析 `_INDEX.md` 覆盖度表的**合计行** ⇒ `(行数, 映射行)`。

    ⚠️ **为什么单独守它**：合计行是**唯一没人核对的自报数** ⇒ 与 P-08 同病 ⇒ 补上。
    """
    idx = MT / "_INDEX.md"
    if not idx.exists():
        return None
    for ln in idx.read_text(encoding="utf-8").splitlines():
        if not ln.startswith("|"):
            continue
        cells = [c.strip() for c in ln.split("|") if c.strip()]
        if not cells or cells[0].strip("*") != "合计":
            continue
        if len(cells) < 6:
            return None
        def num(s):
            m = re.findall(r"\d+", s.replace(",", ""))
            return int(m[0]) if m else None
        return num(cells[2]), num(cells[5])
    return None


def java_files(rel):
    """RAG-V1 侧的主代码 `.java`（排除测试与构建产物）。返回 (相对路径, 文件名) 列表。

    ⚠️ **按文件计数，不按文件名去重** —— users 域的 `user/` 与 `admin/` 两个子域下
    存在同名文件（如各自的 `UserController.java`）⇒ 去重会漏计。
    """
    base = ROOT / rel
    if not base.exists():
        return []
    out = []
    for p in base.rglob("*.java"):
        parts = p.parts
        if "test" in parts or "target" in parts:      # src/test/java/... 与 target/...
            continue
        out.append((p.relative_to(base).as_posix(), p.name))
    return sorted(out)


def rag_java_files(rel):
    """RAG 仓侧该域的主代码 `.java`（排除测试 / 构建产物 / tools）。"""
    base = RAG / rel
    if not base.exists():
        return []
    out = []
    for p in base.rglob("*.java"):
        sp = str(p)
        if "/src/test/" in sp or "/target/" in sp:
            continue
        rel_p = p.relative_to(RAG).as_posix()
        if rel_p.startswith("tools/"):          # RAG 的探针 / 对拍 runner —— 非业务代码
            continue
        out.append((rel_p, p.stem))
    return sorted(out)


def mentions(text, name):
    """文件名是否在文档里出现过（忽略大小写）。"""
    stem = name[:-5] if name.endswith(".java") else name[:-3]
    low = text.lower()
    if name.lower() in low:
        return True
    return stem.lower() in low


def coverage(text, files):
    """按文件计数算出覆盖率。

    同一域内可能有**同名但不同路径**的文件（users 域的 user/ 与 admin/ 各一份）
    ⇒ 不能按文件名去重，否则漏计。判据：
      · 该文件名的**去后缀形式**在文档里出现次数 ≥ 同名文件数 ⇒ 视为已覆盖
      · 或文档里直接写了完整相对路径 ⇒ 视为已覆盖
    """
    groups = {}
    for rel, name in files:
        groups.setdefault(name, []).append(rel)

    covered, missing = 0, []
    low = text.lower()
    for name, rels in groups.items():
        stem = name[:-5] if name.endswith(".java") else name[:-3]
        n = len(rels)
        if any(r.lower() in low for r in rels):
            covered += n
            continue
        occ = low.count(name.lower()) + low.count(stem.lower())
        if occ >= n:
            covered += n
        else:
            missing.extend(rels)
    return covered, missing


def check_section_counts() -> list:
    """守恒断言：**小节标题自报的条数 == 表内实际数据行数**。

    ⚠️ **为什么要有这条**（P-08 同病）：各域 §缺口 小节会在**标题里自报条数**
    ⇒ 那是**自报数**，靠人复算 ⇒ 加一条就漂一次。
    ⇒ 这里从文件系统反向数表格的**数据行**（去掉表头行与分隔行）⇒ 与标题自报值对账。

    计数规则：取标题到**下一个同级或更高级标题**之间的表格行；
    每个表格的第一行（表头）与 `---` 分隔行**不计入**。
    """
    problems = []
    for doc in sorted(MT.rglob("*.md")):
        if doc.name == "_INDEX.md":
            continue
        text = doc.read_text(encoding="utf-8")
        lines = text.splitlines()
        for i, ln in enumerate(lines):
            if not ln.startswith("#"):
                continue
            m = re.search(r"（\s*(\d+)\s*条", ln)
            if not m:
                continue
            claimed = int(m.group(1))
            level = len(ln) - len(ln.lstrip("#"))
            end = len(lines)
            for j in range(i + 1, len(lines)):
                if lines[j].startswith("#"):
                    lv = len(lines[j]) - len(lines[j].lstrip("#"))
                    if lv <= level:
                        end = j
                        break
            n, in_table = 0, False
            for l2 in lines[i + 1:end]:
                if not l2.startswith("|"):
                    in_table = False
                    continue
                cells = [c.strip() for c in l2.split("|") if c.strip()]
                if set("".join(cells)) <= set("-: "):
                    continue                      # 分隔行
                if not in_table:
                    in_table = True
                    continue                      # 表头行
                n += 1
            if n != claimed:
                problems.append(
                    f"❌ {doc.relative_to(MT)} 的「{ln.strip()[:46]}」自报 {claimed} 条 ≠ 实测 {n} 条")
    return problems


def _split_row(ln):
    """按 **markdown 表格规则**切分单元格 —— **转义的 `\\|` 不算列分隔符**。

    ⚠️ **踩过的坑**：直接 `ln.split("|")` 会把**已正确转义**的 `\\|` 也切开
    ⇒ 一行被误判成 6/7 列 ⇒ 守卫按 cells[0] 取 B 列时**整行的 B 列内容静默丢失**
    （实测 `like/like.md:136` 的雪花游标 `(unixMilli<<22)\\|(nodeId<<12)\\|seq`
    就是这种**文档本来就对、解析器不对**的情况）。
    ⇒ 切分时把 `\\|` 还原成字面 `|`。
    """
    s = ln.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    cells, cur, i = [], [], 0
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 1 < len(s) and s[i + 1] == "|":
            cur.append("|")
            i += 2
            continue
        if ch == "|":
            cells.append("".join(cur).strip())
            cur = []
            i += 1
            continue
        cur.append(ch)
        i += 1
    cells.append("".join(cur).strip())
    return cells


def _scan_tables():
    """扫出所有表格行 `[(文档, 行号, 单元格列表, 是否主表)]`。

    ⚠️ **主表的识别按「表头第 3 格」**（D16 后主表 = B|C|类型|备注 = 4 列）：
      而 `| 映射类型 | 条数 | 占比 | 说明 |`（统计表）第 3 格是「占比」⇒ **不算主表**。

    ⚠️ **主表行的列数必须是 4** —— 单元格里的 `|` 未转义（markdown 里 `|` 是列分隔符）
      会把一行切成 5/6 列 ⇒ 那样的行**既认不出 B 列，也渲染错乱** ⇒ 单独报红
      （见 `check_table_shape`），**不静默跳过**。
    """
    out = []
    for doc in sorted(MT.rglob("*.md")):
        if doc.name in ("_INDEX.md", "README.MD"):
            continue
        in_main = False
        for i, ln in enumerate(doc.read_text(encoding="utf-8").splitlines(), 1):
            if not ln.startswith("|"):
                in_main = False
                continue
            cells = _split_row(ln)
            if set("".join(cells)) <= set("-: "):      # 分隔行
                continue
            if len(cells) == 4 and "映射类型" in cells[2].replace("`", "").replace("**", ""):
                in_main = True
                continue
            out.append((doc, i, cells, in_main))
    return out


def _b_cells():
    """收集 B 列单元格文本 `[(文档, 行号, 文本)]`。

    范围只有两处（**不能扫全文** —— 全文里的 `.java` 也包含 C 列的类名，
    拿全文当 B 列会把「C 侧有、RAG 侧没有」的类误报成「B 列引用了不存在的类」）：
      ① **主表**（B|C|类型|备注）的**第 1 列**（D16 后 A 列已删，B 列下标 1 → 0）；
      ② **§0.5「RAG（B）包 | RAG-V1（C）包」速查表**的第 1 列（包级写法）。
    """
    out = []
    for doc, i, cells, is_main in _scan_tables():
        if is_main and len(cells) >= 1:
            out.append((doc, i, cells[0]))
            continue
        if cells and "RAG（B）包" in cells[0] and len(cells) >= 2:
            out.append((doc, i, cells[0]))
    return out


def _parse_b_tokens(text):
    """把一段 B 列文本拆成三类 token。

    返回 `(exact, wild, dirs)`：
    - **exact** —— 具体类名（`Xxx.java` 的 basename）；
    - **wild**  —— 通配类名（`Message<Case>Service.java`）⇒ 转正则；
    - **dirs**  —— 目录形态（`like-rpc/…/mapper/`，**≥2 段**才收）。

    ⚠️ **三处踩过的坑**（照抄必踩）：
    1. `…` / `...` **不在** `\\w` 里 ⇒ 必须先归一成 `*`，否则整条路径 token 只剩模块名；
    2. **不能去掉括注** —— `article-api/…/dto/`（13 个文件：`Article.java` / …）的类名枚举在括注里，
       去掉括注等于丢掉 12 个类的覆盖；
    3. `re.escape` 在 Python 3.7+ **不转义** `<` `>` ⇒ 通配替换必须在 escape **之前**做，
       否则 `Message<Case>Service` 永远匹配不上真实类名。
    """
    exact, wild, dirs = set(), [], set()
    t = re.sub(r"[`*]", "", text)
    t = re.sub(r"<br\s*/?>", " ", t)
    t = t.replace("…", "*").replace("...", "*")
    for tok in re.findall(r"[\w\-<>\.]+(?:/[\w\-<>\.\*]*)*/?", t):
        tok = tok.strip("./")
        if not tok or tok in ("—", "无对应物", "同上", "无") or tok.startswith("*"):
            continue
        if tok.endswith((".proto", ".sql", ".yml", ".yaml")):
            continue
        if "<" in tok:
            seg = tok.split("/")[-1]
            if seg.endswith(".java"):
                wild.append(seg[:-5])
            continue
        if tok.endswith(".java"):
            stem = tok.split("/")[-1][:-5]
            if re.search(r"[A-Za-z0-9]", stem):      # 排除 `*.java` 通配拆出的空名
                exact.add(stem)
            continue
        parts = [p for p in tok.split("/") if p != "*" and re.search(r"[A-Za-z0-9]", p)]
        if len(parts) >= 2:
            dirs.add("/".join(parts))
    return exact, wild, dirs


def _covered_by(cn, rel, exact, wild_re, dirs):
    """某个 RAG 类是否被 B 列 token 覆盖。

    ⚠️ **目录 token = B 侧文件路径的「分段前缀」**
    （`service/comment/comment-api` ⇒ 该模块下的全部 `.java`）。

    🔴 **旧实现把「包名」与「类名」相比**（`dp[-1] == pkg`，而 `pkg` 取的是类名）
    ⇒ **目录 token 永远不命中** ⇒ 按域统计里凡靠目录 token 覆盖的类**一律报成未覆盖**
    （实测 `article` 域 74/83，而该域文档里明明写了 `service/article/article-api`）。
    ⇒ **D16（2026-11-02）改为前缀语义**；实测只有 `article` 一域的数字变化（74→83），
    其余 9 个域**逐字不变** ⇒ 说明这是修 bug，不是放松判据。
    """
    if cn in exact or any(w.match(cn) for w in wild_re):
        return True
    segs = rel.replace(".java", "").split("/")
    for d in dirs:
        dp = d.split("/")
        if segs[:len(dp)] == dp:
            return True
    return False


def check_b_column():
    """**B 列双向对账** —— B 列是 D16 之后的**主判据**。

    - **B1（正向）**：B 列写下的每个**具体类名**必须在 RAG 磁盘真实存在
      ⇒ 否则 = 文档引用了不存在的 RAG 类。
    - **B2（反向）**：RAG 侧每个**业务** `.java` 类都必须被 B 列覆盖
      （精确类名 / 通配 / 目录形态三者任一命中）⇒ 否则 = RAG 侧有类没进 B 列，
      **抄写路线图（§1.0c 的 B↔C）会漏掉它**。
    - **白名单**：登记在案的例外；**已失效条目会被报出来**（防白名单腐化）。

    ⚠️ `tools/`（RAG 的语义探针 / 对拍 runner）**不是业务代码** ⇒ 不参与分母。
    """
    problems, exact, wild, dirs = [], set(), [], set()
    for doc, ln, text in _b_cells():
        e, w, d = _parse_b_tokens(text)
        exact |= e; wild += w; dirs |= d

    wild_re = [re.compile("^" + re.sub(r"<(\w+)>", r"[A-Za-z0-9]+", re.escape(t)) + "$")
               for t in wild]

    rag_stems = {p.stem for p in RAG.rglob("*.java")}

    # ── B1 正向 ──────────────────────────────────────────────────────────
    for stem in sorted(exact):
        if stem not in rag_stems:
            problems.append(f"❌ B 列引用了 RAG 侧不存在的类 `{stem}.java`（RAG 改名/删除后文档未跟上）")

    # ── B2 反向（全局）────────────────────────────────────────────────────
    uncovered, whitelisted = [], []
    for p in sorted(RAG.rglob("*.java")):
        sp = str(p)
        if "/src/test/" in sp or "/target/" in sp:
            continue
        rel = p.relative_to(RAG).as_posix()
        if rel.startswith("tools/"):
            continue
        cn = p.stem
        if _covered_by(cn, rel, exact, wild_re, dirs):
            continue
        if cn in B_COLUMN_WHITELIST:
            whitelisted.append(cn)
        else:
            uncovered.append((cn, rel))

    for cn, rel in uncovered:
        problems.append(f"❌ B 列遗漏 RAG 侧类 `{cn}.java`（{rel}）—— 抄写路线图会漏掉它")
    for cn in sorted(set(B_COLUMN_WHITELIST) - set(whitelisted)):
        problems.append(f"❌ B 列白名单条目 `{cn}` 已失效（该类已被 B 列覆盖）⇒ 请删除白名单条目")

    summary = (f"精确 {len(exact)} · 通配 {len(wild)} · 目录 {len(dirs)} · "
               f"白名单命中 {len(whitelisted)} 条")
    return problems, summary, len(uncovered), (exact, wild_re, dirs)


def b_coverage_by_domain(token_bundle):
    """按域统计 B 列覆盖率 ⇒ {域: (覆盖, 总数, 缺失列表)}。

    ⚠️ **按域归属**：一个域的 B 列 token 只用于判该域的 RAG 类 ⇒
       否则 `common.md` 里写的 `service/common/...` 会把别的域也算成已覆盖。
    """
    exact, wild_re, dirs = token_bundle
    out = {}
    for dom, (_v, rag_rel, docs) in DOMAINS.items():
        doc_list = (docs,) if isinstance(docs, str) else tuple(docs)
        # 该域自己的文档 → token
        e, w, d = set(), [], set()
        for doc_rel in doc_list:
            p = MT / doc_rel
            if not p.exists():
                continue
            for ln in p.read_text(encoding="utf-8").splitlines():
                if not ln.startswith("|"):
                    continue
                cells = _split_row(ln)
                if set("".join(cells)) <= set("-: "):
                    continue
                # 主表第 1 列 或 §0.5 第 1 列
                txt = cells[0] if cells else ""
                te, tw, td = _parse_b_tokens(txt)
                e |= te; w += tw; d |= td
        wre = [re.compile("^" + re.sub(r"<(\w+)>", r"[A-Za-z0-9]+", re.escape(t)) + "$")
               for t in w]
        files = rag_java_files(rag_rel)
        covered, missing = 0, []
        for rel, cn in files:
            if _covered_by(cn, rel, e, wre, d):
                covered += 1
            else:
                missing.append(f"{cn}.java")
        out[dom] = (covered, len(files), missing)
    return out


# ── 第 ⑥ 重校验：指代消解 ────────────────────────────────────────────────
def _norm(x):
    """去掉 markdown 强调与空白 —— 表格单元格的归一化形态。"""
    if not isinstance(x, str):
        return ""
    return re.sub(r"[*`\s]", "", x)


def _is_ref(x):
    """是否是**指代**（「同上」/「同左」）—— 需向上消解才能读出含义。"""
    return "同上" in _norm(x) or "同左" in _norm(x)


def _is_neg(x):
    """是否是**明确否定**（「无对应文件」/「无独立文件」/「—」）—— 机器读得出，且语义正确。"""
    return bool(re.search(r"无对应|不适用|无落点|无C落点|没有对应|^—$|^-$", _norm(x)))


def _has_path(x):
    """是否给出了**具体路径**（`.java` / 目录 / `.proto` / `.sql` / `.yml`）。"""
    s = _norm(x)
    if _is_neg(x):
        return False
    return bool(re.search(r"\.java\b|[\w\-]/[\w\-]|\.proto|\.sql|\.yml", s))


def _map_rows():
    """主表映射行 `[(文档, 行号, B, C, 类型)]` —— **按文档 → 行号顺序**。

    ⚠️ 消解**必须按这个顺序**：指代是「向上指」，跨文档指代不成立。
    """
    out = []
    for doc, i, cells, is_main in _scan_tables():
        if not is_main or len(cells) < 4:
            continue
        t = cells[2].replace("`", "").replace("**", "").strip()
        if t in TYPE_TOKENS:
            out.append((doc, i, cells[0], cells[1], t))
    return out


def check_table_shape():
    """**主表行的列数必须为 4**（D16 后主表 = B|C|类型|备注）。

    ⚠️ **为什么必须报红而不是静默跳过**：这样的行
      ① **渲染错乱**（markdown 里 `|` 是列分隔符）；
      ② **B 列认不出来**（守卫按 cells[0] 取 B 列）⇒ 该行的 B 列内容**静默丢失**
      ⇒ 正是 P-08「没人复算的证据 = 字节层面和愿望一样」的同病。
    """
    problems = []
    n = 0
    for doc, i, cells, is_main in _scan_tables():
        if not is_main or len(cells) == 4:
            continue
        n += 1
        problems.append(
            f"❌ {doc.relative_to(MT)}:{i} 主表行有 {len(cells)} 列（应为 4）"
            f" ⇒ 单元格里的 `|` 未转义（markdown 里它是列分隔符）⇒ 该行 B 列认不出")
    return problems, n


def check_b_resolvability():
    """**第 ⑥ 重校验：B↔C 指代消解后可判定性**。

    **背景**：B 列有若干行写「同上」。「同上」是**确定性指代** —— B / C 各自独立向上
    取最近一个非「同上」的值，零歧义 ⇒ 消解后**每行都能读出含义**。
    ⇒ 真正要守的不是「有没有『同上』」，而是「**消解后每行是不是都读得出**」。

    三个子判据：
    - **⑥-a 孤立指代** —— 「同上」向上找不到锚点 ⇒ **真缺陷**（指代链断了）；
    - **⑥-b 未登记** —— 消解后仍读不出「某个 `.java` 的落点」的行，**必须**登记在
      `NON_FILE_LEVEL`（非文件级对照 / 明确「无」）⇒ 未登记即报红；
    - **⑥-c 登记失效** —— 已登记的行后来变得可判定 ⇒ 报「登记已失效，请删除」⇒ 防登记腐化。

    ⚠️ **锚点规则**：只有「具体路径」或「明确否定」能当锚点 ——
       纯描述文字**不是**锚点。
    """
    problems = []
    rows = _map_rows()
    cur_doc = None
    last_b = last_c = None
    undecidable, orphan = [], []

    for doc, i, b, c, t in rows:
        if doc != cur_doc:            # 换文档 ⇒ 指代链重置
            cur_doc, last_b, last_c = doc, None, None
        rb, rc = b, c
        broken = False
        if _is_ref(rb):
            if last_b is None:
                orphan.append((doc.relative_to(MT), i, "B"))
                rb, broken = "", True
            else:
                rb = last_b
        elif _has_path(rb) or _is_neg(rb):
            last_b = rb
        if _is_ref(rc):
            if last_c is None:
                orphan.append((doc.relative_to(MT), i, "C"))
                rc, broken = "", True
            else:
                rc = last_c
        elif _has_path(rc) or _is_neg(rc):
            last_c = rc
        if broken:
            continue                  # 孤立指代已单独报过 ⇒ 不再计入「不可判定」
        if not ((_has_path(rb) or _is_neg(rb)) and (_has_path(rc) or _is_neg(rc))):
            undecidable.append((doc, i, b, c))

    for rel, i, side in orphan:
        problems.append(f"❌ {rel}:{i} 的 {side} 列是孤立指代（向上找不到锚点）")

    for doc, i, b, c in undecidable:
        key = (str(doc.relative_to(MT)), _norm(b)[:60])
        if key not in NON_FILE_LEVEL:
            problems.append(
                f"❌ {doc.relative_to(MT)}:{i} 消解后仍不可判定（未登记）"
                f"—— B=`{_norm(b)[:30]}` C=`{_norm(c)[:30]}`"
                f" ⇒ 请登记到 NON_FILE_LEVEL 或把路径写全")

    seen = set()
    for doc, i, b, c in undecidable:
        seen.add((str(doc.relative_to(MT)), _norm(b)[:60]))
    for key in sorted(NON_FILE_LEVEL):
        if key not in seen:
            problems.append(
                f"❌ NON_FILE_LEVEL 登记条目已失效：{key[0]} 的「{key[1][:40]}」"
                f"（该行现已可判定）⇒ 请删除该登记")

    total = len(rows)
    ok = total - len(undecidable)
    pct = (ok * 100 / total) if total else 0.0
    summary = (f"消解后双侧可判定 {ok}/{total} = {pct:.1f}% · "
               f"非文件级登记 {len(seen)} 条 · 孤立指代 {len(orphan)} 处")
    return problems, summary


def main():
    targets = sys.argv[1:] or list(DOMAINS)
    total_c = total_c_missing = 0
    rows = []
    problems = []
    actual = {}          # 域 → (行数, C覆盖, C总数, B覆盖, B总数, 映射行) —— 实测值

    for dom in targets:
        if dom not in DOMAINS:
            print(f"❌ 未知域：{dom}", file=sys.stderr); continue
        v_rel, rag_rel, docs = DOMAINS[dom]
        doc_list = (docs,) if isinstance(docs, str) else tuple(docs)
        missing_docs = [d for d in doc_list if not (MT / d).exists()]
        if missing_docs:
            problems.append(f"❌ {dom}: 文档不存在 {missing_docs}")
            rows.append([dom, ",".join(doc_list), "-", "-", "-", "-", "文档缺失"])
            continue
        # ⚠️ **路径不存在 = 域路径表已过时**（目录改名 / 模块被并掉）。
        #    绝不能让它悄悄走 `java_files()==[]` ⇒ 0/0 ⇒ 「100% 覆盖」**假绿**。
        if not (ROOT / v_rel).exists():
            problems.append(f"❌ {dom}: RAG-V1 路径不存在 `{v_rel}`（域路径表与磁盘不符）")
            rows.append([dom, ",".join(doc_list), "-", "-", "-", "-", "路径缺失"])
            continue
        if not (RAG / rag_rel).exists():
            problems.append(f"❌ {dom}: RAG 路径不存在 `{rag_rel}`（域路径表与磁盘不符）")
            rows.append([dom, ",".join(doc_list), "-", "-", "-", "-", "路径缺失"])
            continue
        text = "\n".join((MT / d).read_text(encoding="utf-8") for d in doc_list)

        js = java_files(v_rel)
        c_covered, c_missing = coverage(text, js)

        total_c += len(js);  total_c_missing += len(c_missing)

        c_pct = 100.0 if not js else c_covered*100/len(js)
        rows.append([dom, ",".join(doc_list), f"{c_covered}/{len(js)}",
                     f"{c_pct:.0f}%", "-", "-",
                     "OK" if not c_missing else "有缺口"])
        for r in c_missing:
            problems.append(f"❌ {dom}: C 列缺失 `{r}`")

        # 实测值：行数 + 映射行
        lines = sum(len((MT / d).read_text(encoding="utf-8").splitlines()) for d in doc_list)
        actual[dom] = (lines, c_covered, len(js), count_map_rows(text))

    # ── B 列按域覆盖率（需先拿到 token 包）─────────────────────────────
    _b_problems, b_summary, b_uncovered, bundle = check_b_column()
    b_cov = b_coverage_by_domain(bundle)
    for dom in targets:
        if dom not in b_cov:
            continue
        cov, tot, miss = b_cov[dom]
        pct = 100.0 if not tot else cov*100/tot
        for r in rows:
            if r[0] == dom:
                r[4] = f"{cov}/{tot}"; r[5] = f"{pct:.0f}%"
                if miss:
                    r[6] = "有缺口" if not r[2].startswith("-") else r[6]
        actual.setdefault(dom, (0, 0, 0, 0))
        actual[dom] = actual[dom][:3] + (cov, tot) + actual[dom][3:]

    print(f"{'域':<10} {'文档':<16} {'C 列':<10} {'覆盖':<7} {'B 列':<10} {'覆盖':<7} 状态")
    print("-" * 74)
    for r in rows:
        print(f"{r[0]:<10} {r[1]:<16} {r[2]:<10} {r[3]:<7} {r[4]:<10} {r[5]:<7} {r[6]}")
    print("-" * 74)
    tb = sum(v[3] for v in actual.values())
    ttb = sum(v[4] for v in actual.values())
    print(f"{'合计':<10} {'':<16} {f'{total_c-total_c_missing}/{total_c}':<10} "
          f"{'':7} {f'{tb}/{ttb}':<10}")

    # ── `_INDEX.md` 自报数字 vs 文件系统实测值 ──────────────────────────
    idx = parse_index_table()
    drift = []
    for dom, (lines, c_cov, c_tot, b_cov, b_tot, mrows) in actual.items():
        if dom not in idx:
            continue
        il, ic, ict, ib, ibt, im = idx[dom]
        for label, got, want in (("行数", il, lines),
                                 ("C 列覆盖", (ic, ict), (c_cov, c_tot)),
                                 ("B 列覆盖", (ib, ibt), (b_cov, b_tot)),
                                 ("映射行", im, mrows)):
            if got != want:
                drift.append(f"❌ _INDEX.md {dom} 的「{label}」自报 {got} ≠ 实测 {want}")
    for d in drift:
        problems.append(d)
    if idx:
        print(f"\n_INDEX.md 自报数字对账：{'✅ 全部一致' if not drift else f'❌ {len(drift)} 处漂移'}"
              f"（{len(actual)} 个域 × 4 项）")
    else:
        print("\n_INDEX.md 自报数字对账：跳过（索引不存在）")

    # ── `_INDEX.md` 合计行对账 ───────────────────────────────────────────
    tot_idx = parse_index_totals()
    if tot_idx and actual:
        exp_lines = sum(v[0] for v in actual.values())
        exp_rows = sum(v[5] for v in actual.values())
        for label, got, want in (("行数", tot_idx[0], exp_lines), ("映射行", tot_idx[1], exp_rows)):
            if got != want:
                problems.append(f"❌ _INDEX.md 合计行的「{label}」自报 {got} ≠ 实测 {want}")
        print(f"\n_INDEX.md 合计行对账：{'✅ 一致' if tot_idx==(exp_lines,exp_rows) else '❌ 有漂移'}"
              f"（行数 {exp_lines} · 映射行 {exp_rows}）")

    # ── 小节标题自报条数 vs 表内实际行数（P-08 同病 ⇒ 机器复算）──────────
    sec_problems = check_section_counts()
    if sec_problems:
        print(f"\n小节自报条数对账：❌ {len(sec_problems)} 处漂移")
        problems.extend(sec_problems)
    else:
        print("\n小节自报条数对账：✅ 全部一致（标题自报 == 表内实际行数）")

    # ── 主表行形状（列数必须为 4）─────────────────────────────────────
    shape_problems, shape_n = check_table_shape()
    if shape_problems:
        print(f"\n主表行形状：❌ {shape_n} 行列数异常（未转义的 `|`）")
        problems.extend(shape_problems)
    else:
        print("\n主表行形状：✅ 全部为 4 列")

    # ── B 列双向对账 ───────────────────────────────────────────────────
    if _b_problems:
        print(f"\nB 列双向对账：❌ {len(_b_problems)} 处问题（{b_summary}）")
        problems.extend(_b_problems)
    else:
        print(f"\nB 列双向对账：✅ 全部一致（{b_summary}）")

    # ── B↔C 指代消解后可判定性（第 6 重校验）──────────────────────────
    r_problems, r_summary = check_b_resolvability()
    if r_problems:
        print(f"\nB↔C 指代消解：❌ {len(r_problems)} 处问题（{r_summary}）")
        problems.extend(r_problems)
    else:
        print(f"\nB↔C 指代消解：✅ 全部一致（{r_summary}）")

    if problems:
        print("\n".join(problems))
    print(f"\nEXIT={'0' if not problems else '1'}  "
          f"C 列 {total_c-total_c_missing}/{total_c} · "
          f"B 列 {tb}/{ttb} · B 列全局未覆盖 {b_uncovered}")
    return 0 if not problems else 1

if __name__ == "__main__":
    sys.exit(main())
