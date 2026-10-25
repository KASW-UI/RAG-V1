#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按实际文件系统生成 docs/File_Tree/ 的逐模块文件树文档（不手写，避免与代码漂移）。

**输出布局 = 源树的路径镜像**（去掉 `service/` 前缀，末段换 `.md`）：

    common                              →  docs/File_Tree/common.md
    proto                               →  docs/File_Tree/proto.md
    service/users/common                →  docs/File_Tree/users/common.md
    service/users/user                  →  docs/File_Tree/users/user.md
    service/users/admin                 →  docs/File_Tree/users/admin.md
    service/article                     →  docs/File_Tree/article.md

⚠️ **打平后的结构（2026-10-26）**：每域**一个** jar 模块（`service/<域>/`）——
各域的 `*-contract` 模块已并入域模块（错误契约 + 事件 DTO 落在 `com.rag.<域>.contract` 包），
`.proto` 契约集中到**根级 `proto/` 模块** ⇒ 叶子模块从 22 降到 13。
`service/users/` 仍是聚合结构（真有两个子域）⇒ `user` / `admin` 各自一个 jar 模块。

⇒ `docs/File_Tree/` 与源树**一一对应**，且 `users/` 下保留 `user/`·`admin/` 两个子域层级。

脚本幂等：每次运行先清掉不再产出的陈旧 `.md` 与空目录，再重新生成。
"""
import pathlib, re, shutil

ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1")
OUT  = ROOT / "docs/File_Tree"

GO = {
 "common":   "service/common/",
 "proto":    "proto/",
 "users-common": "service/user/common/errmsg/errmsg.go",
 "user":     "service/user/user/",
 "users":    "service/user/",
 "admin":    "service/user/admin/",
 "article":  "service/article/",
 "comment":  "service/comment/",
 "favorite": "service/favorite/",
 "follow":   "service/follow/",
 "hot":      "service/hot/",
 "like":     "service/like/",
 "message":  "service/message/",
 "task":     "service/task/",
}

# 各域的 L1 特殊约定（实测 Go）
SPECIAL = {
 "common": [
  "**starter 形态**：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册 7 个 `@AutoConfiguration` ⇒ 消费方**零 `@Import` 清单**。",
  "**错误契约逐域隔离**：各域错误码值/文案/语言**不可统一**（详见《第二轮深挖-契约盘点》§四）。",
  "**communityfact = 跨服务事件协议**：`Event` / `TechnicalReceipt` / `SubjectRef` / `AuthorityFact` 是 **L1 wire 类型**（comment·favorite·like 三域共享）。",
  "**CanonicalHash 用 RFC 8785 JCS** ⇒ Jackson 的 `SORT_PROPERTIES_ALPHABETICALLY` **不等价** ⇒ 指纹不一致会破坏幂等。",
 ],
 "proto": [
  "**`.proto` 契约集中到根级 `proto/` 模块** —— 10 个域各一份（`admin.proto` / `article.proto` / `comment.proto` / `favorite.proto` / `follow.proto` / `hot.proto` / `like.proto` / `message.proto` / `task.proto` / `users.proto`（user 域））⇒ 域模块**不再自带 proto**。",
  "**pb 类包名 `com.rag.proto.<域>`**（例 `com.rag.proto.like.LikeActionReq`）—— 不是 `com.rag.<域>.contract.proto.*` ⇒ 域代码 import 时用新包名。",
  "**文件内容与 Go 侧 `proto/*.proto` 逐字节一致** —— 只有 option 块与空白层面是表达层差异（`java_package` / `java_multiple_files`）。",
  "**Go 侧 `proto/` 多 2 份**（`points.proto` / `security.proto`）—— 那两个域**不在迁移范围**内 ⇒ 不是待办。",
 ],
 "users-common": [
  "**为什么存在**：Go 的 `service/user/common/errmsg/errmsg.go` 被 `user/api`·`user/rpc`·`admin/api`·`admin/rpc` **四方共用** ⇒ 抽为独立共享模块，避免错误码段复制两份。",
  "**文案是中文**（`\"服务繁忙\"`）—— 与 follow/hot/message 的英文文案不同。",
  "⚠️ **不含 proto** —— proto 已集中到根级 `proto/` 模块 ⇒ 本模块只留错误契约。",
 ],
 "user": [
  "**路由前缀是签字偏离**：Go `/usercenter/v1/user/*` ⇒ 本仓库 `/user/v1/*`（见 `docs/Intentional_Deviations/_cross/signed-deviations.md`）。",
  "**method 是签字偏离**：Go `Get2·Post6·Delete1` ⇒ 本仓库统一 POST。",
 ],
 "admin": [
  "**路由前缀是签字偏离**：Go `/admincenter/v1/admin/*` ⇒ 本仓库 `/admin/v1/*`（中间那层 `/admin` 段被去掉）。",
  "**method 是签字偏离**：Go `Get3·Post9·Delete1` ⇒ 本仓库统一 POST。",
  "**⚠️ 指标名拼写错误必须照抄**：`admin_rpc_high_risk_action_logal`（应为 log/audit）⇒ 监控看板依赖它。",
 ],
 "article": [
  "**前缀是 `/v1`** —— 唯一不带域名前缀的域。",
  "**method 混合**：Get3·Post2·Put1·Delete1 ⇒ **不是**统一 POST。",
  "**指标无命名空间**：`article_total` 等是裸名（无 `<subsystem>_` 前缀）⇒ 与其它域形态不一致，照抄。",
  "**唯一不走信封的端点**：`POST /v1/upload`（multipart，字段名固定 `image`）⇒ 返回裸 `{\"image_url\":…}`。",
 ],
 "comment": [
  "**共享 communityfact 事件协议**（与 favorite/like 三方共用）。",
  "**Kafka 契约**：`CommentKafkaMsg`（`comment/rpc/common/types/kqueue.go`）是跨服务 L1。",
 ],
 "favorite": [
  "**共享 communityfact 事件协议**。",
  "**错误契约形态独有**：本域是唯一「只有常量 + `codeMsg` + 反查索引 + `GRPCError` + `BizCodeFromError`，无 `CodeError` 类型」的域。",
  "**注意**：`GET /favorite/v1/folder/list` 是 GET 端点，但 Go 仍解析并校验 body ⇒ 必须保留（否则非法 body 会从 5003 放过成 200）。",
 ],
 "follow": [
  "**错误文案是英文**（`\"service busy\"`）—— 与 article/comment/favorite/like/user 的中文文案不同。",
  "**api 层用例不合并**：8 个用例中 2 个带专属业务规则（`forbidden_scope`）⇒ 拆 `FollowRelationService` + `FollowQueryService`。",
 ],
 "hot": [
  "**⚠️ 全部端点免鉴权**（Go 无 `WithJwt` 组）⇒ 本域**不该有** `*JwtAuthInterceptor`。",
  "**唯一端点是 GET**：`GET /hot/v1/articles`。",
  "**`heavykeeper` 是 hot 域私有算法包**（仅被 hot/rpc 的 config + mqs import）⇒ 非跨域共享。",
 ],
 "like": [
  "**⚠️ 指标名用连字符**：`like-rpc_action_total` ⇒ Prometheus 正则不允许 ⇒ 该指标不会出现在 `/metrics`。**照抄，不修正**。",
  "**rpc 侧另有独立 types 包**（`like/rpc/internal/types/types.go`）：`KafkaLikeMsg` / `ArticleHotEvent` / `TaskArticleProgressMsg`。",
  "**共享 communityfact 事件协议**。",
 ],
 "message": [
  "**两组 JWT**：`/message/v1` 用 UserAuth、`/message/v1/admin` 用 **AdminAuth** ⇒ 两套 secret、两套 interceptor、两套 Properties。",
  "**错误文案是英文**。",
  "**rpc 侧无 `*RpcProperties`**，配置块名 `MessageProperties`。",
 ],
 "task": [
  "**⚠️ 全部端点免鉴权**（Go 无 `WithJwt` 组）。",
  "**前缀是 `/taskcenter/v1`** —— 不是 `/task/v1`。",
  "**Go 侧 task 没有错误码空间**（无 common 包）⇒ 本域**没有** `contract/` 包（不是漏搬）。",
  "**`UserLikeCountEntity` 在 Go 侧位于 `internal/sink/like_sink.go`**（不在 `internal/model/`）⇒ 按目录盘点会漏。",
 ],
}

def read_first_note(path):
    """从 javadoc 里抽第一行有信息量的职责说明。"""
    try:
        txt = path.read_text(encoding="utf-8")
    except Exception:
        return None
    for line in txt.splitlines():
        s = line.strip()
        if not s.startswith("*"):
            continue
        s = s.lstrip("*").strip()
        if not s or s.startswith("<p><b>TODO"):
            continue
        if s.startswith("/"):
            # javadoc 的结束符 `*/` —— 不是职责说明
            continue
        if s.startswith("<p>"):
            s = s[3:].strip()
        if s.startswith("<b>") and "</b>" in s:
            s = s.replace("<b>", "").replace("</b>", "").strip()
        s = re.sub(r"<[^>]+>", "", s).strip()
        if s:
            return s
    return None

def tree_of(base, pkg_root):
    """生成带职责注释的目录树。"""
    lines = []
    def walk(d, prefix=""):
        # ⚠️ 排除构建产物目录（`target/`）—— 否则编译一次就会把 .class 混进文件树
        entries = sorted((e for e in d.iterdir() if e.name != "target"),
                         key=lambda p: (p.is_file(), p.name))
        dirs  = [e for e in entries if e.is_dir()]
        files = [e for e in entries if e.is_file()]
        items = dirs + files
        for i, e in enumerate(items):
            last = (i == len(items) - 1)
            conn = "└── " if last else "├── "
            if e.is_dir():
                lines.append(f"{prefix}{conn}{e.name}/")
                walk(e, prefix + ("    " if last else "│   "))
            else:
                note = read_first_note(e) if e.suffix == ".java" else None
                if not note:
                    note = {"pom.xml": "Maven 模块描述", ".proto": "proto 契约（L1）",
                            ".yml": "运行时配置", ".gitkeep": ""}.get(e.name, "")
                lines.append(f"{prefix}{conn}{e.name}" + (f"    # {note}" if note else ""))
    walk(base)
    return "\n".join(lines)

def pom_deps(pom):
    if not pom.exists(): return []
    txt = pom.read_text(encoding="utf-8")
    out = []
    for m in re.finditer(r"<dependency>(.*?)</dependency>", txt, re.S):
        g = re.search(r"<groupId>([^<]+)</groupId>", m.group(1))
        a = re.search(r"<artifactId>([^<]+)</artifactId>", m.group(1))
        if g and a:
            out.append(f"{g.group(1)}:{a.group(1)}")
    return out

def pom_artifact_id(pom):
    """项目自身的 artifactId（**不是**父 pom 的）。

    ⚠️ 目录名 ≠ artifactId：物理目录是 `service/like/` / `service/users/user/`，
    但 artifactId 保持长名唯一（`like-service` / `user-service` / `users-common`）。
    ⇒ 必须从 pom 的 `<build>` 侧读，不能用目录名。
    """
    if not pom.exists(): return None
    txt = pom.read_text(encoding="utf-8")
    # 去掉 <parent> 块，否则会读到父 pom 的 artifactId
    txt = re.sub(r"<parent>.*?</parent>", "", txt, flags=re.S)
    m = re.search(r"<artifactId>([^<]+)</artifactId>", txt)
    return m.group(1) if m else None

def build(module, base, pkg_root, title, desc, go_src):
    base = pathlib.Path(base)
    lines = [f"# {title} —— 文件树", ""]
    lines += ["> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。",
              f"> 改动树结构后重新运行该脚本。", ""]
    lines += ["## 一、模块坐标", "",
              "| 项 | 值 |", "|---|---|",
              f"| 模块路径 | `{base.relative_to(ROOT)}` |",
              f"| artifactId | `{pom_artifact_id(base / 'pom.xml') or '（无 pom.xml）'}` |",
              f"| 根包 | `{pkg_root}` |",
              f"| Go 出处 | `{go_src}` |"]
    if desc:
        lines.append(f"| 说明 | {desc} |")
    lines.append("")

    deps = pom_deps(base / "pom.xml")
    if deps:
        lines += ["## 二、依赖", ""]
        for d in deps:
            lines.append(f"- `{d}`")
        lines.append("")

    lines += ["## 三、目录树", "", "```"]
    lines.append(tree_of(base, pkg_root))
    lines += ["```", ""]

    if module in SPECIAL:
        lines += ["## 四、特殊约定（L1 契约 · 勿凭直觉改）", ""]
        for s in SPECIAL[module]:
            lines.append(f"- {s}")
        lines.append("")

    lines += ["## 五、状态", "",
              "| 项 | 状态 |", "|---|---|",
              "| 目录结构 | ✅ 已落地 |",
              "| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |",
              "| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |",
              ""]
    return "\n".join(lines)

# ---------- 生成 ----------
OUT.mkdir(parents=True, exist_ok=True)

# ⚠️ **打平后的模块清单（2026-10-26）**：每域**一个** jar 模块（`service/<域>/`）——
#    各域的 `*-contract` 模块已并入域模块（错误契约 + 事件 DTO 在 `com.rag.<域>.contract` 包），
#    `.proto` 契约集中到**根级 `proto/` 模块**（pb 类包名 `com.rag.proto.<域>`）。
#    `service/users/` 仍是聚合结构（真有两个子域）⇒ `user` / `admin` 各自一个 jar 模块。
modules = [
 ("common", ROOT/"common", "com.rag.common", "common（全局共享 starter）",
  "由 Go 的 `service/common`（11 个 .go）升级而来的 **starter**；同时承载 RAG 侧 `users-common` 的通用部分（jwt / cryptx）", GO["common"]),
 ("proto", ROOT/"proto", "com.rag.proto", "proto（proto 契约根模块）",
  "**10 个域的 `.proto` 契约集中于此**（pb 类包名 `com.rag.proto.<域>`）；域模块只依赖它，不再自带 proto", GO["proto"]),
 ("users-common", ROOT/"service/users/common", "com.rag.users.common", "users-common（users 域公共库）",
  "**公共库**：users 域（user + admin）共享的错误契约 —— 不是第三个域", GO["users-common"]),
]
SUB = {"user":"service/users/user", "admin":"service/users/admin"}
for d in ["user","admin","article","comment","favorite","follow","hot","like","message","task"]:
    base = ROOT/SUB[d] if d in SUB else ROOT/f"service/{d}"
    modules.append((d, base, f"com.rag.{d}", f"{d}（{d} 域应用）",
                    "api + rpc 合并后的单一 Spring Boot 应用，**域内包已压平为单层技术角色包树**"
                    "（`controller` / `advice` / `interceptor` / `dto` / `config` / `metrics` / `service` / "
                    "`grpc` / `domain` / `messaging` / `mapper` / `client` / `bootstrap` / `exception`）；"
                    "域内错误契约与事件 DTO 在 `com.rag.<域>.contract` 包", GO[d]))

# ---------- 路径映射 ----------
def out_path(base):
    """模块路径 → 文档路径（源树路径镜像：去掉 `service/` 前缀，末段换 `.md`）。"""
    parts = list(base.relative_to(ROOT).parts)
    if parts and parts[0] == "service":
        parts = parts[1:]
    return OUT.joinpath(*parts[:-1], parts[-1] + ".md")

def domain_of(base):
    """模块归属的一级目录（= 域）；全局模块（common / proto）时返回 None。

    ⚠️ **打平后每域只有一个模块**（`service/<域>/`）⇒ 文档落在 `File_Tree/<域>.md`（**单段**）。
    ⇒ **不能靠「输出路径的段数」判域** —— 那会把 `article.md` 误判成全局模块。
    ⇒ 改从**模块路径**判：`service/users/<子模块>` → `users` 域；`service/<域>` → `<域>` 域；
    `common/` / `proto/`（根级）→ 不属于任何域。
    """
    parts = base.relative_to(ROOT).parts
    if not parts or parts[0] != "service":
        return None                 # common / proto 等根级模块
    rest = parts[1:]
    if not rest:
        return None                 # service 聚合 pom 本身（无模块）
    if rest[0] == "users":
        return "users"              # users 域：common / user / admin 三个模块同属一域
    return rest[0]

# ---------- 写出 ----------
expected = set()
for key, base, pkg, title, desc, go in modules:
    dest = out_path(base)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(build(key, base, pkg, title, desc, go), encoding="utf-8")
    expected.add(dest.resolve())

# ---------- 清理陈旧产物（保证幂等）----------
# 只删 `.md`；`.DS_Store` 等非本脚本产出的文件保留。
for md in sorted(OUT.rglob("*.md"), reverse=True):
    if md.name == "_INDEX.md":
        continue
    if md.resolve() not in expected:
        md.unlink()
# 再自底向上删空目录（含历史遗留的拼写错误目录，如 `commen/`）
for d in sorted([p for p in OUT.rglob("*") if p.is_dir()], key=lambda p: len(p.parts), reverse=True):
    if not any(d.iterdir()):
        d.rmdir()

# ---------- 索引（按域分组）----------
DOMAIN_LABEL = {"users": "**users 域**（一个域，两个子域 user / admin）"}

def kind_of(key):
    """模块类型 —— 按模块键**显式**判定。

    ⚠️ 不要用描述文案猜 —— 域应用的说明里也会出现「契约」二字（域内 `contract/` 包）⇒ 会误分类。
    """
    if key == "proto":
        return "契约"
    if key in ("common", "users-common"):
        return "公共库"
    return "应用"

def rows(items):
    out = []
    for key, base, pkg, title, desc, go in items:
        rel = out_path(base).relative_to(OUT).as_posix()
        aid = pom_artifact_id(base / "pom.xml") or "—"
        out.append(f"| `{base.relative_to(ROOT)}` | `{aid}` | {kind_of(key)} | [`{rel}`]({rel}) | `{go}` |")
    return out

globals_ = [m for m in modules if domain_of(m[1]) is None]
by_dom = {}
for m in modules:
    d = domain_of(m[1])
    if d: by_dom.setdefault(d, []).append(m)

index = ["# File_Tree 索引", "",
         "> 本目录由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。",
         "> 改动树结构后重新运行该脚本。", "",
         "## 布局规则", "",
         "本目录是 **源树路径的镜像** —— 去掉 `service/` 前缀，模块路径末段换成 `.md`：", "",
         "```",
         "common                              →  File_Tree/common.md",
         "proto                               →  File_Tree/proto.md",
         "service/users/common                →  File_Tree/users/common.md",
         "service/users/user                  →  File_Tree/users/user.md",
         "service/users/admin                 →  File_Tree/users/admin.md",
         "service/article                     →  File_Tree/article.md",
         "```", "",
         "⇒ 每域**一个**模块 ⇒ 域文档落在 `File_Tree/<域>.md`（单段）；",
         "⇒ `users/` 下保留 `user/`·`admin/` 两级 ⇒ **一眼看出 users 是一个域、两个子域**。", "",
         "## 全局", "",
         "| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |", "|---|---|---|---|---|"]
index += rows(globals_)
index += ["", "## 按域", ""]
for dom in sorted(by_dom):
    index += [f"### {DOMAIN_LABEL.get(dom, f'**{dom} 域**')}", "",
              "| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |", "|---|---|---|---|---|"]
    index += rows(by_dom[dom])
    index.append("")
index += ["## 说明", "",
          "- `common` = 全局 starter（原 `service/common`），**不属于任何域** ⇒ 放在本目录根。",
          "- `proto` = **根级 proto 契约模块** —— 10 个域的 `.proto` 全部集中于此（pb 类包名 `com.rag.proto.<域>`）⇒ 域模块只依赖它。",
          "- **不再有独立的 `*-contract` 模块** —— 各域的错误契约与事件 DTO 已并入域模块的 `com.rag.<域>.contract` 包。",
          "- `service/users/` = **users 域的根**（一个域，两个子域 user / admin）—— 不是三个域。",
          "- `users/common` = users 域的**公共库**（共享错误契约）—— artifactId 仍是 `users-common`，命名体现「库」，不是「域」。",
          "- `users/user` / `users/admin` 是两个独立应用；它们**共享** `users/common` 与根级 `proto`。",
         "- **物理目录 ≠ artifactId**：目录叫 `user` / `admin` / `article`，artifactId 仍是 `<域>-service`（`user-service` / `admin-service` / `article-service`）⇒ **不要用目录名反推 artifactId**。",
          "- 每份文档含：模块坐标 · 依赖 · 带职责注释的目录树 · **该域的 L1 特殊约定** · 状态。", ""]
(OUT/"_INDEX.md").write_text("\n".join(index), encoding="utf-8")
print(f"DONE  {len(modules)} 个模块文档 + _INDEX.md")
