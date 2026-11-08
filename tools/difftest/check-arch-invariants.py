#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""架构符合性守门（A1–A17）。

> 断言的完整定义 → [`../../docs/架构符合性断言.md`](../../docs/架构符合性断言.md)

**它问的不是「行为一样吗」，而是「这个实现符合约定吗」** —— 符合性检查只需要**一份可执行的规则**，
不需要第二个实现来做差分。⇒ 本脚本是 RAG-V1 唯一的架构漂移探测面。

---

## 为什么不用 `mvn dependency:tree`

多模块仓库里每个模块都要跑一遍，**慢**（实测分钟级）。
⇒ **直接解析 POM 文本**（读 `<artifactId>spring-boot-starter-web</artifactId>` 是否出现在目标模块的 pom 里），
零依赖、毫秒级，且足够可靠：**依赖传递引入 starter 的情况在 A1 下反而不该存在**。

---

## 断言绑定契约（不可跨契约复用）

| 契约 | 形态 | 适用断言 |
|---|---|---|
| **A** | B 侧部署等价（api / rpc 分离） | A1 · A2 · A3 · A4–A17 |
| **B** | Spring 惯用（api + rpc 合并）★ RAG-V1 | A4–A17 |

⚠️ **A1 / A2 / A3 是 api/rpc 分离的派生物**，合并形态下它们**自动消失**。
⇒ 在 `--contract B` 下它们报 **`N/A`**，**不得改成红灯**（AGENTS.md §二 D1 连带效应）。
⇒ 反向也不许放水：`--contract A` 下它们**必须真判**。

---

## 四种判定状态（不要把「没判」当成「过了」）

| 状态 | 含义 | 计入退出码 |
|---|---|---|
| `PASS` | 判据成立 | 否 |
| `FAIL` | 判据被违反 —— **漂移** | **是**（EXIT=1） |
| `N/A` | 本契约形态下该断言不适用 | 否 |
| `PENDING` | 断言对象**还是空壳**（实现未落地）⇒ **判据无从施加** | 否，但**必须显式报出** |

⚠️ **`PENDING` 单独存在是本脚本的关键设计**：
> 「绿测试可能是错误答案的守卫，比没有测试更危险。」（AGENTS.md §1.3）

一个**还没写实现**的类，既不能算 `PASS`（那是假的绿），也不该算 `FAIL`（那是冤枉）。
⇒ 判据依赖实现内容时（当前只有 A8），对象为空壳 ⇒ 报 `PENDING` + 列出待落地清单。
⇒ 落地实现后自动转 `PASS` 或 `FAIL`。

---

## 断言必须能红

> 「没有会红的断言等于没有断言。」—— [`../../docs/架构符合性断言.md`](../../docs/架构符合性断言.md) §五

`--self-test` 会**在临时目录里造一棵带已知违规的假树**，逐条断言「该红的必须红」。
⇒ 脚本自己证明自己的判据有效，而不是靠「现在全绿」来自证。

---

## 例外必须写进脚本并附理由

> 口头豁免不算（[`../../docs/架构符合性断言.md`](../../docs/架构符合性断言.md) §四）。

例外表见 `EXCEPTIONS` 常量，**每条都带 reason**。

---

## 用法

    python3 tools/difftest/check-arch-invariants.py                # 默认 --contract B（RAG-V1 形态）
    python3 tools/difftest/check-arch-invariants.py --contract A    # api/rpc 分离形态
    python3 tools/difftest/check-arch-invariants.py --self-test     # 红灯自检（造违规假树）
    python3 tools/difftest/check-arch-invariants.py --only A4 A11   # 只跑指定断言

退出码：`0` 全绿（可含 N/A / PENDING）· `1` 有 FAIL（漂移）· `2` 用法错误
"""
from __future__ import annotations

import argparse
import pathlib
import re
import shutil
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]

PASS, FAIL, NA, PENDING = "PASS", "FAIL", "N/A", "PENDING"

# ---------------------------------------------------------------------------
# 例外表：每条必须带 reason（AGENTS.md §六「例外必须写进断言文件并附理由」）
# ---------------------------------------------------------------------------
EXCEPTIONS = {
    "A8": {
        # B 侧 communityfact.NewProcessLogger 是一个**工厂**（返回 *slog.Logger），
        # 对应的 Java 侧 ProcessLogger 同样是「产出 Logger 的工厂」⇒ 工厂本身不必 implements 接口。
        # 若后续把它改成直接实现 Logger，则本例外自动不再需要（可删）。
        "ProcessLogger": "B 侧 communityfact.ProcessLogger#newProcessLogger 是工厂（返回 Logger）"
                         "⇒ Java 侧同类亦为工厂，无需 implements slf4j.Logger",
    },
}

# A11：三域各自必须有的 fact-* 装配落点（B 侧 cmd/fact-* 独立进程 ⇒ 域内组件）
A11_DOMAINS = {
    "comment": "Comment",
    "favorite": "Favorite",
    "like": "Like",
}
A11_REQUIRED = [
    "{d}FactDispatchScheduler.java",
    "{d}AuthorityEndpointConfig.java",
    "{d}FactProcessLogging.java",
]

# A14：必须有 `otel:` 段（追踪导出配置）的域清单
# ⚠️ **为什么是「清单」而不是「扫全仓」**：判据是「域**应该**有追踪导出配置」——
#   域清单 = 显式声明 ⇒ 新增域时**由人决定**要不要进清单（而不是脚本默默要求）。
#   ⇒ ⚠️ `user-service`（users-user 子域）**不在清单内** ⇒ 它那同款缺口由 users 域 lane 处置
#   ⇒ （**登记在 `docs/DRIFT/common/verification-common-2026-11-02.md` 的 V21**）。
# ⚠️ **模块定位一律按 artifactId**（与 A11 同款 ⇒ 对目录改名鲁棒）。
A14_DOMAINS = [
    ("article", "article-service"),
    ("comment", "comment-service"),
    ("favorite", "favorite-service"),
    ("follow", "follow-service"),
    ("like", "like-service"),
    ("message", "message-service"),
    ("task", "task-service"),
    ("users-admin", "admin-service"),
    ("users-user", "user-service"),
]
# ⚠️ **一个「已登记但不在清单内」的域**（**不是漏写**，有确切理由）：
#   · `hot`（hot-service）⇒ **实现尚未合入 main**（主干里还是空壳 + 48 行的 yml 桩）
#     ⇒ ⇒ 它那份 `otel:` 段在 `align/hot` 分支上（**值与本清单各域逐条同构**）
#     ⇒ ⇒ 合入 main 后**立刻**被下面的「通用规则」覆盖（见 A14 的 `通用规则` 一段）⇒ 无需改本清单。
#     ⇒ ⇒ ⚠️ **不要**为了让它进清单而去改主干的 hot yml（那是 48 行的桩，合并时必冲突）。

# A10：手写必填校验器的特征类名（RAG 侧 `GoStyleRequired*` / `GoStyleRequest` 同款）
# ⚠️ **必须精确匹配** —— 用 `GoStyleRequest\w*` 会把 `GoStyleRequestDeadline`（请求超时传递）
# 一并误判成必填校验器 ⇒ 断言误报 ⇒ 会被当成噪声忽略 ⇒ 真违规时反而没人看。
A10_HANDWRITTEN_VALIDATOR = re.compile(
    r"GoStyleRequired\w*|GoStyleRequest\b|\w*RequiredValidator"
)


# ---------------------------------------------------------------------------
# 基础解析工具
# ---------------------------------------------------------------------------
def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def _strip_pkg_import(src: str) -> str:
    src = re.sub(r"^\s*package\s+[^;]+;", "", src, flags=re.M)
    src = re.sub(r"^\s*import\s+[^;]+;", "", src, flags=re.M)
    return src


def type_header(src: str) -> str:
    """类型声明头（第一个 `{` 之前）—— 已去注释 / package / import。"""
    s = _strip_pkg_import(strip_comments(src))
    i = s.find("{")
    return s[:i] if i != -1 else s


def type_body(src: str) -> str:
    """类型体（第一个 `{` 与最后一个 `}` 之间）—— 已去注释 / package / import。"""
    s = _strip_pkg_import(strip_comments(src))
    i, j = s.find("{"), s.rfind("}")
    if i == -1 or j == -1 or j <= i:
        return ""
    return s[i + 1:j].strip()


def is_empty_shell(src: str) -> bool:
    """是不是**空壳**（只有类型声明，没有成员）—— 空树阶段的判定依据。"""
    return type_body(src) == ""


def has_import_with_beans(src: str) -> bool:
    """源码里是否有「带 Bean 的 `@Import`」。

    ⚠️ **不能用简单正则** —— `@Import(Other.class)` / `@Import({A.class, B.class})` /
    `@Import(Foo)` 三种形态的收尾字符各不相同（`)` / `}` / 无），
    用「@Import 后跟标识符」的正则会**漏掉 class 字面量形态** ⇒ 断言看着绿、实际是哑弹。
    ⇒ 改为**括号配平**取内容，再看里面有没有名字。

    `@Import({})`（空清单）不算违规。
    """
    for m in re.finditer(r"@Import\b", src):
        rest = src[m.end():].lstrip()
        if not rest.startswith("("):
            if re.match(r"[A-Za-z_][\w.]*", rest):
                return True
            continue
        depth, buf = 0, []
        for ch in rest:
            buf.append(ch)
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    break
        if re.search(r"[A-Za-z_][\w.]*", "".join(buf)):
            return True
    return False


def implements_slf4j(src: str) -> bool:
    h = type_header(src)
    if "org.slf4j.Logger" in h:
        return True
    return bool(
        re.search(r"implements\s+[^{]*\bLogger\b", h)
        and re.search(r"import\s+org\.slf4j\.Logger", src)
    )


def is_rag_type(path: pathlib.Path) -> bool:
    """是不是本仓自己的类型（`com.rag.**`）—— 排除第三方。"""
    return "com" in path.parts and "rag" in path.parts


def module_artifact_id(pom: pathlib.Path) -> str:
    """项目自身的 artifactId（**不是**父 pom 的）。

    ⚠️ 物理目录 ≠ artifactId：`service/like/` 的 artifactId 是 `like-service`，
    `service/users/user/` 的是 `user-service` ⇒ **不能用目录名判模块种类**。
    """
    if not pom.exists():
        return ""
    txt = pom.read_text(encoding="utf-8")
    txt = re.sub(r"<parent>.*?</parent>", "", txt, flags=re.S)
    m = re.search(r"<artifactId>([^<]+)</artifactId>", txt)
    return m.group(1) if m else ""


def iter_modules(root: pathlib.Path):
    """产出 (模块目录, 模块种类)。种类：contract / service / common / other。

    ⚠️ 种类**由 artifactId 判定**（物理目录 ≠ artifactId：目录叫 `user` / `admin` / `article`，
    artifactId 却是 `user-service` / `admin-service` / `article-service` ⇒ 用目录名会把它们全判成 other）。

    ℹ️ `contract` 种类在**打平后的结构里已为空** —— 各域的 `*-contract` 模块被并进域模块，
    `.proto` 契约集中到根级 `proto/` 模块（artifactId = `proto`）。判定分支保留，供旧树自检使用。
    """
    for pom in sorted(root.glob("**/pom.xml")):
        if "target" in pom.parts:
            continue
        d = pom.parent
        aid = module_artifact_id(pom)
        name = aid or d.name
        if name.endswith("-contract"):
            kind = "contract"
        elif name.endswith("-service"):
            kind = "service"
        elif name == "common" or name.endswith("-common"):
            kind = "common"
        else:
            kind = "other"
        yield d, kind


def module_by_artifact_id(root: pathlib.Path, artifact_id: str):
    """按 **artifactId** 定位模块目录 —— 找不到返回 None。

    ⚠️ **绝不能用目录名定位模块** —— 物理目录 ≠ artifactId（`service/like/` 的 artifactId 是
    `like-service`）。凡是要「找到某个模块」，一律走这里 ⇒ 对目录改名鲁棒。
    """
    for d, _kind in iter_modules(root):
        if module_artifact_id(d / "pom.xml") == artifact_id:
            return d
    return None


def module_java(root: pathlib.Path, module: pathlib.Path):
    return [p for p in module.glob("src/main/java/**/*.java") if "target" not in p.parts]


def module_yml(module: pathlib.Path):
    return module / "src" / "main" / "resources" / "application.yml"


def pom_text(module: pathlib.Path) -> str:
    pom = module / "pom.xml"
    return pom.read_text(encoding="utf-8") if pom.exists() else ""


def pom_has_artifact(module: pathlib.Path, artifact: str) -> bool:
    return f"<artifactId>{artifact}</artifactId>" in pom_text(module)


def parse_grpc_clients(yml_text: str) -> dict:
    """抽出 `grpc.client.<name>` → 属性字典。**手写解析，不依赖 PyYAML**（零依赖）。"""
    out: dict = {}
    in_grpc = in_client = False
    cur = None
    for raw in yml_text.splitlines():
        if not raw.strip() or raw.strip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        s = raw.strip()
        if indent == 0:
            in_grpc, in_client, cur = (s == "grpc:"), False, None
            continue
        if not in_grpc:
            continue
        if indent == 2:
            in_client, cur = (s == "client:"), None
            continue
        if not in_client:
            continue
        m = re.match(r"^([A-Za-z0-9_.\-]+):\s*(.*)$", s)
        if not m:
            continue
        key, val = m.group(1), m.group(2).split("#")[0].strip()
        if indent == 4:
            cur = key
            out[cur] = {}
        elif indent >= 6 and cur is not None:
            out[cur][key] = val
    return out


def parse_yaml_block(yml_text: str, top_key: str) -> dict:
    """抽出顶层 `top_key:` 下的「点路径 → 值」映射。**手写解析，不依赖 PyYAML**（零依赖）。

    例：`otel:` 段里的 `service.name` ⇒ `{"service.name": "hot-service", ...}`。
    ⚠️ **只收叶子**（有值的行）⇒ 纯容器键（`service:`）不进映射。
    """
    out: dict = {}
    in_block = False
    stack: list = []  # [(indent, key)]
    for raw in yml_text.splitlines():
        if not raw.strip() or raw.strip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        s = raw.strip()
        if indent == 0:
            in_block = (s == f"{top_key}:")
            stack = []
            continue
        if not in_block:
            continue
        m = re.match(r"^([A-Za-z0-9_.\-]+):\s*(.*)$", s)
        if not m:
            continue
        key, val = m.group(1), m.group(2).split("#")[0].strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        if val:
            out[".".join([k for _, k in stack] + [key])] = val
        stack.append((indent, key))
    return out


def strip_java_comments(text: str) -> str:
    """把 Java 源码里的**注释与字符串字面量**剥掉，只留可执行代码。

    🔴 **为什么需要它**：本仓的 javadoc 里**大量引用 B 侧源码**
    （例如 `if (props.getStat().isEnabled())` 这样的原文摘录）
    ⇒ ⇒ 若判据直接在**带注释**的文本里找调用点 ⇒ ⇒ 它会数到**注释里的引用**
    ⇒ ⇒ 「接线被摘掉」也照样绿 ⇒ ⇒ **假绿**
    ⇒ ⇒ 这正是 AGENTS.md §3.1 那条「**不要拿代码注释当对照基准**」的机器化形态。

    ⚠️ 字符串 / 字符字面量也一并置空 ⇒ ⇒ 免得源码里的示例字符串被误当成调用点。
    """
    out: list = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            i = j + 1
            out.append('""')
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    break
                j += 1
            i = j + 1
            out.append("''")
            continue
        out.append(c)
        i += 1
    return "".join(out)


# ---------------------------------------------------------------------------
# 断言结果容器
# ---------------------------------------------------------------------------
class Result:
    def __init__(self, state: str, detail: str = "", violations=()):
        self.state = state
        self.detail = detail
        self.violations = list(violations)


def ok(detail: str) -> Result:
    return Result(PASS, detail)


def na(detail: str) -> Result:
    return Result(NA, detail)


# ---------------------------------------------------------------------------
# A1 · A2 · A3 —— 仅契约 A（api / rpc 分离形态）适用
# ---------------------------------------------------------------------------
def check_a1(root: pathlib.Path, contract: str) -> Result:
    """A1 · `*-rpc` 的 classpath 不得有 `spring-boot-starter-web`。"""
    if contract != "A":
        return na("契约 B（api+rpc 合并）下不适用 —— D1 连带效应，不得改成红灯")
    rpcs = [d for d, _ in iter_modules(root) if d.name.endswith("-rpc")]
    if not rpcs:
        return na("无 `*-rpc` 模块")
    bad = [d for d in rpcs if pom_has_artifact(d, "spring-boot-starter-web")]
    if bad:
        return Result(FAIL, f"{len(bad)}/{len(rpcs)} 个 `*-rpc` 引入了 web starter",
                      [f"{d.relative_to(root)}/pom.xml → spring-boot-starter-web" for d in bad])
    return ok(f"{len(rpcs)}/{len(rpcs)} 个 `*-rpc` 无 web starter")


def check_a2(root: pathlib.Path, contract: str) -> Result:
    """A2 · `*-api` 必须有 `spring-boot-starter-web`。"""
    if contract != "A":
        return na("契约 B（api+rpc 合并）下不适用 —— D1 连带效应，不得改成红灯")
    apis = [d for d, _ in iter_modules(root) if d.name.endswith("-api")]
    if not apis:
        return na("无 `*-api` 模块")
    bad = [d for d in apis if not pom_has_artifact(d, "spring-boot-starter-web")]
    if bad:
        return Result(FAIL, f"{len(bad)}/{len(apis)} 个 `*-api` 缺 web starter",
                      [f"{d.relative_to(root)}/pom.xml → 缺 spring-boot-starter-web" for d in bad])
    return ok(f"{len(apis)}/{len(apis)} 个 `*-api` 有 web starter")


def check_a3(root: pathlib.Path, contract: str) -> Result:
    """A3 · gRPC 拦截链必须**真挂到 server 上**。

    ⚠️ **这条断言在合并形态下依然成立** —— 它不是「api/rpc 分离」的派生物：
    B 侧范围内 **10 个服务逐条显式**调 `AddUnaryInterceptors(...)`（`service/*/rpc/*.java`），
    合并成「一域一进程」后仍要挂，只是**挂载点从「每 rpc 模块」变成「共享层 1 份」**。

    ┌─ 契约 A（api/rpc 分离，RAG 形态）────────────────────────────┐
    │ 每个 `*-rpc` 模块各自提供一个 `GrpcServerConfigurer`，且真调 │
    │ `server.intercept(...)`                                      │
    └──────────────────────────────────────────────────────────────┘
    ┌─ 契约 B（api+rpc 合并，RAG-V1 形态）─────────────────────────┐
    │ ① **共享层必须提供** `GrpcServerConfigurer` 且真调          │
    │    `server.intercept(...)` —— devh starter 只注入            │
    │    `List<GrpcServerConfigurer>`，不产 Configurer ⇒           │
    │    Bean 在、starter 不用它 ⇒ **超时 / deadline 传播 /        │
    │    gRPC span / `sea.slow` 标记全丢，且零报错**               │
    │ ② **域内不得再出现** `GrpcServerConfigurer` —— 重复定义 ⇒   │
    │    同一进程挂 2 条链 ⇒ span 重复、timeout 叠加              │
    └──────────────────────────────────────────────────────────────┘
    """
    if contract == "A":
        rpcs = [d for d, _ in iter_modules(root) if d.name.endswith("-rpc")]
        if not rpcs:
            return na("无 `*-rpc` 模块")
        viol, hit = [], 0
        for d in rpcs:
            files = module_java(root, d)
            cfgs = [f for f in files if "GrpcServerConfigurer" in f.read_text(encoding="utf-8")]
            if not cfgs:
                viol.append(f"{d.relative_to(root)} → 无 GrpcServerConfigurer 实现")
                continue
            if not any("server.intercept(" in f.read_text(encoding="utf-8") for f in cfgs):
                viol.append(f"{d.relative_to(root)} → 有 GrpcServerConfigurer，但未调用 server.intercept(...)")
                continue
            hit += 1
        if viol:
            return Result(FAIL, f"{len(viol)}/{len(rpcs)} 个 `*-rpc` 未挂上拦截链", viol)
        return ok(f"{hit}/{len(rpcs)} 个 `*-rpc` 拦截链已挂上")

    # ── 契约 B（合并形态 · RAG-V1）──────────────────────────────
    commons = [d for d, k in iter_modules(root) if k == "common" and d.name == "common"]
    if not commons:
        return na("无 `common` 模块")
    common = commons[0]
    cfiles = module_java(root, common)

    def declares(src: str) -> bool:
        """文件是否**声明**了 `GrpcServerConfigurer`（而非只在 javadoc 里提到它）。"""
        return bool(re.search(r"implements\s+[\w.]*GrpcServerConfigurer", src)
                    or re.search(r"\bGrpcServerConfigurer\s+\w+\s*\(", src))

    mount = [f for f in cfiles if declares(f.read_text(encoding="utf-8"))]
    viol = []
    if not mount:
        # 指定的挂载点类仍是空壳 ⇒ 判据未施加（PENDING），不是违规
        target = common / "src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java"
        if target.exists() and is_empty_shell(target.read_text(encoding="utf-8")):
            return Result(PENDING,
                          "挂载点 `ProtectionAutoConfiguration` 仍是空壳 ⇒ 判据未施加（落地实现后自动转 PASS/FAIL）")
        viol.append("common/ → 无 `GrpcServerConfigurer` 声明（挂载点缺失 ⇒ 拦截链静默失效）")
    elif not any("server.intercept(" in f.read_text(encoding="utf-8") for f in mount):
        viol.append("common/ → 有 GrpcServerConfigurer 声明，但未调用 server.intercept(...)")

    # 反向：域内重复声明 ⇒ 同一条链被挂 2 次（span 重复 / timeout 叠加）
    for d, _ in iter_modules(root):
        if d == common:
            continue
        for f in module_java(root, d):
            if declares(f.read_text(encoding="utf-8")):
                viol.append(f"{f.relative_to(root)} → 域内重复声明 `GrpcServerConfigurer`（⇒ 链被挂 2 次）")
    if viol:
        return Result(FAIL, f"gRPC 拦截链挂载点不合规（{len(viol)} 项）", viol)
    return ok("common/ 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复")

def strip_java_comments(src: str) -> str:
    """去掉 Java 块注释与行注释，只留**真代码**。

    ⚠️ **A4 修 bug（2026-11-02）**：原判据用 `re.findall` 扫**全文**找 `@GrpcClient("X")`
    ⇒ javadoc 里**引述**该注解的说明文字也被当成真注入点
    （实测命中 `service/users/user/.../wiring/NacosRegistration.java:40` —— 那是引 B 侧
    admin-api 注释的一段说明，**不是注入点**；而 D1 合并 api+rpc 后 users 域确实无跨域
    gRPC 调用 ⇒ yml `grpc.client: {}` 是**对的**）⇒ **假阳性**。
    ⇒ 修法：先剥注释再匹配 ⇒ **只认真注解**，判据强度不变（真注入点仍必红）。
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)   # 块注释
    src = re.sub(r"//[^\n]*", "", src)                   # 行注释
    return src




# ---------------------------------------------------------------------------
# A4 · 双向交叉引用（devh 默认 TLS，B 侧 zrpc 侧是明文 gRPC）
# ---------------------------------------------------------------------------
def check_a4(root: pathlib.Path, contract: str) -> Result:
    """A4 · `@GrpcClient("X")` ⟷ 同模块 yml 的 `grpc.client.X.negotiation-type: plaintext`。

    **两个方向都要查**：
    ① 代码里的 `@GrpcClient("X")` ⇒ yml 必须有对应 `plaintext`（漏写 ⇒ 静默变 TLS）
    ② yml 里配了 `grpc.client.<name>` ⇒ 必须有 `plaintext`
       —— 这正是 RAG `article-rpc` 的 2 颗哑弹形态：通道配了但没人用，**当时无害**，
       任何人补一行 `@GrpcClient` 就会引爆。⇒ ② 是把哑弹在**引爆前**抓出来。
    """
    viol, n_code, n_cfg = [], 0, 0
    for module, _ in iter_modules(root):
        if module.name == "common":
            continue
        yml = module_yml(module)
        clients = parse_grpc_clients(yml.read_text(encoding="utf-8")) if yml.exists() else {}
        n_cfg += len(clients)
        for f in module_java(root, module):
            src = strip_java_comments(f.read_text(encoding="utf-8"))
            for name in re.findall(r'@GrpcClient\s*\(\s*"([^"]+)"', src):
                n_code += 1
                if clients.get(name, {}).get("negotiation-type") != "plaintext":
                    viol.append(f"{f.relative_to(root)} → @GrpcClient(\"{name}\") "
                                f"但 yml 无 grpc.client.{name}.negotiation-type: plaintext")
        for name, props in clients.items():
            if props.get("negotiation-type") != "plaintext":
                viol.append(f"{yml.relative_to(root)} → grpc.client.{name} 缺 negotiation-type: plaintext")
    if viol:
        return Result(FAIL, f"{len(viol)} 处 grpc 通道配置漂移", viol)
    detail = f"@GrpcClient {n_code} 处 · yml grpc.client {n_cfg} 处 · 全部 plaintext"
    if n_code == 0 and n_cfg == 0:
        detail += "（空树阶段预期：无跨域客户端）"
    return ok(detail)


# ---------------------------------------------------------------------------
# A5 · `@Import` 清单必须为空（starter 化之后）
# ---------------------------------------------------------------------------
def check_a5(root: pathlib.Path, contract: str) -> Result:
    """A5 · 每个模块的 `@Import` 清单为空。

    ⚠️ starter 化（D3）后共享 Bean 由 `AutoConfiguration.imports` 统一装配。
    残留 `@Import` 会与 auto-config 争抢求值顺序，改变 `@ConditionalOnMissingBean` 语义。
    """
    viol = []
    for f in root.glob("**/src/main/java/**/*.java"):
        if "target" in f.parts:
            continue
        if has_import_with_beans(f.read_text(encoding="utf-8")):
            viol.append(f"{f.relative_to(root)} → 残留 @Import")
    if viol:
        return Result(FAIL, f"{len(viol)} 个文件残留 @Import", viol)
    return ok("0 个 @Import")


# ---------------------------------------------------------------------------
# A6 · MP ordering bridge 全仓只有一份
# ---------------------------------------------------------------------------
def check_a6(root: pathlib.Path, contract: str) -> Result:
    """A6 · `MybatisPlusOrderingBridge` 类全仓只有一份，且必须在 `common/`。

    理由：Boot 4 把 `DataSourceAutoConfiguration` 挪了包 ⇒ MP 的 `@AutoConfigureAfter` 引用静默悬空
    ⇒ MP 自动配置不生效 ⇒ mapper 报 `sqlSessionFactory required`。这个桥**域无关**，不该有 9 份。

    ⚠️ **判据必须是「类名」而不是「源码里提到 MybatisPlus」**（2026-11-02 实测修正）：
    旧判据 `("MybatisPlus" in src and "AutoConfiguration" in f.name)` 会把
    **任何在 javadoc / `@ConditionalOnClass` 里提到 MP 的 auto-config**
    （例如 `PersistenceAutoConfiguration`）误判成「第 2 份 bridge」⇒ 假红。
    ⇒ 现在按**专属类名**匹配 ⇒ 判据与本断言的语义一致。
    """
    hits = []
    for f in root.glob("**/src/main/java/**/*.java"):
        if "target" in f.parts:
            continue
        if "MybatisPlusOrderingBridge" in f.name:
            hits.append(f)
    if len(hits) > 1:
        return Result(FAIL, f"{len(hits)} 份 MP ordering bridge（应为 1 份）",
                      [f"{f.relative_to(root)}" for f in hits])
    if not hits:
        return Result(PENDING, "未找到 MP ordering bridge（实现未落地）")
    f = hits[0]
    if "common" not in f.parts:
        return Result(FAIL, "MP ordering bridge 不在 common/ 下",
                      [f"{f.relative_to(root)}"])
    return ok(f"1 份 ⇒ {f.relative_to(root)}")


# ---------------------------------------------------------------------------
# A7 · classpath 有 resilience4j ⇒ 不得有手写熔断器
# ---------------------------------------------------------------------------
def check_a7(root: pathlib.Path, contract: str) -> Result:
    """A7 · 若 classpath 上有 resilience4j，则不得存在手写的熔断器实现。

    生态已有就不该手写 —— 手写版拿不到 resilience4j 的修复、指标与 Spring Cloud 集成。
    """
    has_r4j = any("io.github.resilience4j" in pom_text(d) for d, _ in iter_modules(root))
    if not has_r4j:
        return na("classpath 无 resilience4j ⇒ 断言不触发")
    pat = re.compile(r"Breaker|CircuitBreaker|CircuitOpen|\bFuse")
    viol = []
    for f in root.glob("**/src/main/java/**/*.java"):
        if "target" in f.parts or not is_rag_type(f):
            continue
        if pat.search(f.stem):
            viol.append(f"{f.relative_to(root)} → 手写熔断器（生态已有 resilience4j）")
    if viol:
        return Result(FAIL, f"{len(viol)} 个手写熔断器", viol)
    return ok("无手写熔断器（resilience4j 在 classpath）")


# ---------------------------------------------------------------------------
# A8 · 手写 Logger 必须实现 org.slf4j.Logger
# ---------------------------------------------------------------------------
def check_a8(root: pathlib.Path, contract: str) -> Result:
    """A8 · 自定义日志载体必须接入 `org.slf4j.Logger`。

    判据对象 = 简单名以 `Logger` 结尾，或以 `ProcessLogging` 结尾的类（B 侧 `logx` call_chain
    需要自研载体承载 —— G-4）。

    ⚠️ **空壳 ⇒ PENDING，不算 PASS**：还没写实现的类既不是「过了」也不是「违规」。
    """
    viol, pending = [], []
    for f in root.glob("**/src/main/java/**/*.java"):
        if "target" in f.parts or not is_rag_type(f):
            continue
        stem = f.stem
        if not (stem.endswith("Logger") or stem.endswith("ProcessLogging")):
            continue
        if stem in EXCEPTIONS.get("A8", {}):
            continue
        src = f.read_text(encoding="utf-8")
        if implements_slf4j(src) or "org.slf4j.Logger" in src:
            continue
        if is_empty_shell(src):
            pending.append(f"{f.relative_to(root)}")
        else:
            viol.append(f"{f.relative_to(root)} → 未接入 org.slf4j.Logger")
    if viol:
        return Result(FAIL, f"{len(viol)} 个日志载体未接入 slf4j", viol)
    if pending:
        return Result(PENDING, f"{len(pending)} 个日志载体仍是空壳 ⇒ 判据未施加", pending)
    return ok("全部日志载体均接入 org.slf4j.Logger")


# ---------------------------------------------------------------------------
# A9 · DTO 必须 immutable
# ---------------------------------------------------------------------------
def check_a9(root: pathlib.Path, contract: str) -> Result:
    """A9 · `dto` 包下的类型必须 immutable（`record` 或 final 字段 + 无 setter）。

    ⚠️ **glob 必须递归到子包**（2026-10-26 · D11）：`dto/` 已按业务对象拆成
    `dto/<业务对象>/`（如 `dto/user/` `dto/auth/`）⇒ 只扫 `dto/*.java` 会**一个 DTO 都扫不到**
    ⇒ 判据静默失效（假绿）⇒ 必须用 `**/dto/**/*.java`（`**` 匹配零层或多层）。
    """
    viol, n = [], 0
    for f in root.glob("**/dto/**/*.java"):
        if "target" in f.parts:
            continue
        n += 1
        src = f.read_text(encoding="utf-8")
        h = type_header(src)
        if re.search(r"\brecord\s+\w+", h):
            continue
        body = type_body(src)
        if re.search(r"\bvoid\s+set[A-Z]\w*\s*\(", body):
            viol.append(f"{f.relative_to(root)} → 存在 setter")
        elif re.search(r"^\s*(private|protected|public)\s+(?!final\b)(?!static\b)[\w<>\[\],\s.]+\s+\w+\s*(=|;)",
                       body, flags=re.M):
            viol.append(f"{f.relative_to(root)} → 非 final 字段")
    if viol:
        return Result(FAIL, f"{len(viol)}/{n} 个 DTO 非 immutable", viol)
    return ok(f"{n}/{n} 个 DTO 均 immutable")


# ---------------------------------------------------------------------------
# A10 · 有手写必填校验器 ⇒ 必须引入 Bean Validation
# ---------------------------------------------------------------------------
def check_a10(root: pathlib.Path, contract: str) -> Result:
    """A10 · ① 不得存在手写必填校验器 ② 含 DTO 的模块必须引入 Bean Validation。

    手写校验器 = 放弃整个 Bean Validation 生态（消息插值、分组、自定义约束）。
    """
    viol = []
    for f in root.glob("**/src/main/java/**/*.java"):
        if "target" in f.parts or not is_rag_type(f):
            continue
        if A10_HANDWRITTEN_VALIDATOR.search(f.stem):
            viol.append(f"{f.relative_to(root)} → 手写必填校验器")
    for module, _ in iter_modules(root):
        # ⚠️ 递归到子包（D11）—— `dto/` 已按业务对象拆子包，只扫 `dto/*.java` 会漏判
        has_dto = any(module.glob("src/main/java/**/dto/**/*.java"))
        if not has_dto:
            continue
        txt = pom_text(module)
        if "spring-boot-starter-validation" not in txt and "jakarta.validation" not in txt:
            viol.append(f"{module.relative_to(root)}/pom.xml → 有 DTO 但未引入 Bean Validation")
    if viol:
        return Result(FAIL, f"{len(viol)} 处违反 Bean Validation 约定", viol)
    return ok("无手写必填校验器 · 含 DTO 的模块均已引入 Bean Validation")


# ---------------------------------------------------------------------------
# A11 · B 侧 cmd/fact-* 独立进程 ⇒ 域内必须有对应装配落点
# ---------------------------------------------------------------------------
def check_a11(root: pathlib.Path, contract: str) -> Result:
    """A11 · comment / favorite / like 三域各须有 `wiring/<域>FactDispatchScheduler`、
    `<域>AuthorityEndpointConfig`、`<域>FactProcessLogging`。

    ⚠️ 树里没有落点 = **该能力在实现阶段会被整个漏掉**，而漏掉它**不会有任何编译错误或测试红灯**
    —— 正是 RAG `article-rpc` 2 颗哑弹的同款形态。

    ⚠️ **模块定位一律按 artifactId**（`<域>-service`）—— 打平后的物理目录是 `service/<域>/`，
    写死 `service/<域>/service/` 这种路径随一次重构就失效（曾因此误报 3/0 假 FAIL）。
    ⇒ 走 `module_by_artifact_id()` ⇒ **对目录改名鲁棒**。

    ⚠️ **包路径已压平**（2026-10-26）：域内 `api/` + `rpc/` 二分取消 ⇒ 装配落点从
    `com.rag.<域>.rpc.bootstrap` 上收到 `com.rag.<域>.bootstrap`。

    ⚠️ **装配包改名 `wiring/`**（2026-10-26 · D10）：`bootstrap/` 在 Spring 生态里唯一
    的强含义是**已废弃**的 Spring Cloud Bootstrap Context（`bootstrap.yml`）⇒ 且本项目
    用 Nacos（Spring Cloud Alibaba 传统就用 `bootstrap.yml`）⇒ 双重误导 ⇒ 改名 `wiring/`
    （Spring 的 dependency wiring 术语）。**类名后缀 `Bootstrap` 保留**（作类名后缀通顺，
    且与废弃的 Bootstrap Context 无关）。
    """
    viol, n = [], 0
    for dom, prefix in A11_DOMAINS.items():
        aid = f"{dom}-service"
        module = module_by_artifact_id(root, aid)
        if module is None:
            for tpl in A11_REQUIRED:
                n += 1
                viol.append(f"全仓无 artifactId={aid} 的模块 ⇒ 缺 {tpl.format(d=prefix)}")
            continue
        base = module / "src" / "main" / "java" / "com" / "rag" / dom / "wiring"
        rel_mod = module.relative_to(root).as_posix()
        if not base.exists():
            for tpl in A11_REQUIRED:
                n += 1
                viol.append(f"{rel_mod} → 缺 wiring/{tpl.format(d=prefix)}")
            continue
        for tpl in A11_REQUIRED:
            want = tpl.format(d=prefix)
            n += 1
            if not (base / want).exists():
                viol.append(f"{rel_mod}/.../wiring/{want} → 缺落点")
    if viol:
        return Result(FAIL, f"{len(viol)}/{n} 个 fact-* 落点缺失", viol)
    return ok(f"{n}/{n} 个 fact-* 落点齐备（三域各 3 个）")


# ---------------------------------------------------------------------------
# A12 · 一个模块内不得有同名简单类
# ---------------------------------------------------------------------------
def check_a12(root: pathlib.Path, contract: str) -> Result:
    """A12 · 同一 Maven 模块内，两个 `.java` 文件的**简单类名**不得相同（不同包也不行）。

    同名类 ⇒ 职责重复，且按简单类名检索时必然二义 ⇒ 后写实现的人会填错那一个。
    """
    viol = []
    for module, _ in iter_modules(root):
        seen: dict = {}
        for f in module_java(root, module):
            seen.setdefault(f.stem, []).append(f)
        for stem, fs in seen.items():
            if len(fs) > 1:
                rel = sorted(str(x.relative_to(root)) for x in fs)
                viol.append(f"{module.relative_to(root)} → 同名类 {stem}: {rel}")
    if viol:
        return Result(FAIL, f"{len(viol)} 组模块内同名类", viol)
    return ok("无模块内同名类")


# ---------------------------------------------------------------------------
# A13 · 用了 Lombok ⇒ 必须在该注解正上方写「原本的完整代码」展开块
# ---------------------------------------------------------------------------

#: 本仓认可的 Lombok 注解（类级）。
#: ⚠️ **判据边界**：只认**类上**的注解（字段级/方法级的不在本条判据范围）。
LOMBOK_CLASS_ANNOTATIONS = (
    "Data", "Getter", "Setter", "NoArgsConstructor", "AllArgsConstructor",
    "RequiredArgsConstructor", "Builder", "Value", "EqualsAndHashCode",
    "ToString", "Slf4j", "Cleanup", "SneakyThrows", "UtilityClass",
)

#: 展开块的标记（Lombok-使用规范.md §3.4 R2/R5）。
LOMBOK_EXPANSION_MARKER = "Lombok 展开"
LOMBOK_EXPANSION_CODE = re.compile(r"<pre>\s*\{@code", re.S)


def lombok_annotated_types(src: str) -> list:
    """找出**类级**带 Lombok 注解的类型（返回类型名列表）。

    ⚠️ **为什么要先剥注释**：源码里任何位置的 `@Data` 都可能出现在 javadoc 正文里
    ⇒ 直接正则全文匹配会**把注释里的示例当成真的注解** ⇒ 假红。
    ⇒ 先 `strip_comments` 再找「类型声明头 + 其上方注解」。

    ⚠️ **为什么要限定在类型声明头**：字段级/方法级上方的 `@Getter` 不在本判据范围
    （本条只管**类上**那组 Lombok 注解）⇒ 只看 `type_header`。
    """
    header = type_header(src)
    found = []
    for ann in LOMBOK_CLASS_ANNOTATIONS:
        if re.search(r"@" + ann + r"\b", header):
            found.append(ann)
    return found


def class_javadoc(src: str) -> str:
    """取**紧贴类声明**的那段 javadoc（`/** ... */`）。

    ⚠️ **必须取「紧贴」的那段**而不是全文第一段 javadoc ⇒ 否则类上方远处
    的一段无关 javadoc 会被误当成展开块 ⇒ 假绿。

    实现：找类型声明头的位置 ⇒ 从它往前找**最近的** `/** ... */`。
    """
    s = _strip_pkg_import(strip_comments(src))  # 仅用于定位；正文另取原文
    i = s.find("{")
    if i == -1:
        return ""
    # 用「去注释后」的长度做前缀定位不可靠（长度不一致）⇒ 改为在原文里按类型名定位
    m = re.search(r"\b(class|interface|enum|record)\s+(\w+)", s)
    if not m:
        return ""
    name = m.group(2)
    # 在原文里找类型声明（跳过 import/package 行）
    decl = re.search(r"^[ \t]*(?:@\w+[^\n]*\n[ \t]*)*"
                     r"(?:public\s+|final\s+|abstract\s+|static\s+)*"
                     r"(?:class|interface|enum|record)\s+" + re.escape(name) + r"\b",
                     src, flags=re.M)
    if not decl:
        return ""
    head = src[:decl.start()]
    # 取**最近的**一段 `/** ... */`
    blocks = list(re.finditer(r"/\*\*.*?\*/", head, flags=re.S))
    if not blocks:
        return ""
    return blocks[-1].group(0)


def check_a13(root: pathlib.Path, contract: str) -> Result:
    """A13 · 凡类上出现 Lombok 注解 ⇒ 该类的 javadoc 必须含「原本的完整代码」展开块。

    **为什么这条要机器判**（D18）：「用了 Lombok 就把原本的完整代码写清楚」是
    **可机械判定**的 ⇒ 不靠自觉 ⇒ 与「表达层可以变，但变的那部分必须看得见」配套。

    **为什么它不是形式主义**：B 侧是唯一 oracle（D16）⇒ 没有展开块 ⇒
    review 的人无法在不打开 IDE、不跑 delombok 的情况下核对「这段代码原本长什么样」
    ⇒ Lombok 化就成了**不可对照的黑盒**。
    """
    viol, n = [], 0
    for f in root.glob("**/src/main/java/**/*.java"):
        if "target" in f.parts or not is_rag_type(f):
            continue
        src = f.read_text(encoding="utf-8")
        anns = lombok_annotated_types(src)
        if not anns:
            continue
        n += 1
        doc = class_javadoc(src)
        if LOMBOK_EXPANSION_MARKER in doc and LOMBOK_EXPANSION_CODE.search(doc):
            continue
        viol.append(f"{f.relative_to(root)} → 类上有 @{' / @'.join(anns)} "
                    f"但缺「{LOMBOK_EXPANSION_MARKER}」展开块")
    if viol:
        return Result(FAIL, f"{len(viol)}/{n} 个 Lombok 类缺展开块", viol)
    return ok(f"{n}/{n} 个 Lombok 类均带展开块")


# ---------------------------------------------------------------------------
# A14 · 凡清单内的域 ⇒ 其 `application.yml` 必须有 `otel:` 段且键齐（追踪导出配置）
# ---------------------------------------------------------------------------
def check_a14(root: pathlib.Path, contract: str) -> Result:
    """A14 · 清单内各域的 `application.yml` 必须有 `otel:` 段，且**端点非空 / 服务名自洽**。

    🔴 **为什么要有这条**：共享层 `GoStyleOtelConfig` 按 B 侧的
    ``if len(c.Endpoint) > 0`` 口径 —— **端点为空就不装 exporter** ⇒ ⇒ 缺 `otel:` 段
    ⇒ ⇒ **零编译错误、零测试红灯**（配置面缺一整段 ⇒ 没有任何代码路径会报错）
    ⇒ ⇒ 该域的 span **一条都导不出去**（跨域链路在 Jaeger 侧断链）⇒ ⇒ **静默失效**。

    ⚠️ **判据钉的是「不变量」而不是「字面值」**：服务名一律与该域自己的
    `spring.application.name` 比 ⇒ ⇒ 域改名 / 目录改名时判据**自动跟随** ⇒ ⇒ 不会假红。
    ⇒ ⇒ 值侧唯一被钉住的是「协议 = grpc」（= B 侧 `Telemetry.Batcher: otlpgrpc`）与
    「sampler 属 always_on / parentbased_always_on 两族」（两者都被共享层映射成 ratio = 1.0）。

    ⚠️ **两套服务名拼法**：共享层 `GoStyleOtelConfig` 同时认 `otel.service.name` 与
    `otel.resource.attributes.service.name` ⇒ ⇒ **两者都写时必须一致**（否则「读哪套」成谜）。
    ⇒ ⇒ 判据：出现即须与 `spring.application.name` 相等；两套同时出现时还须彼此相等。
    """
    viol, checked = [], 0
    for dom, aid in A14_DOMAINS:
        module = module_by_artifact_id(root, aid)
        if module is None:
            viol.append(f"全仓无 artifactId={aid} 的模块 ⇒ 域 {dom} 的 `otel:` 段无从谈起")
            continue
        rel = module.relative_to(root).as_posix()
        yml = module_yml(module)
        if not yml.exists():
            viol.append(f"{rel}/src/main/resources/application.yml ⇒ 不存在")
            continue
        checked += 1
        text = yml.read_text(encoding="utf-8")
        otel = parse_yaml_block(text, "otel")
        app_name = parse_yaml_block(text, "spring").get("application.name", "")
        if not otel:
            viol.append(f"{rel} ⇒ 缺 `otel:` 段 ⇒ 端点为空 ⇒ 共享层不装 exporter ⇒ span 导不出去")
            continue
        endpoint = otel.get("exporter.otlp.endpoint", "")
        if not endpoint:
            viol.append(f"{rel} ⇒ `otel.exporter.otlp.endpoint` 缺失或为空 ⇒ 不装 exporter")
        protocol = otel.get("exporter.otlp.protocol", "")
        if protocol != "grpc":
            viol.append(f"{rel} ⇒ `otel.exporter.otlp.protocol` = {protocol!r}"
                        f"（应 `grpc` = B 侧 `Telemetry.Batcher: otlpgrpc`）")
        svc = otel.get("service.name", "")
        res = otel.get("resource.attributes.service.name", "")
        if not res:
            viol.append(f"{rel} ⇒ 缺 `otel.resource.attributes.service.name` ⇒ 服务名无据")
        elif app_name and res != app_name:
            viol.append(f"{rel} ⇒ `otel.resource.attributes.service.name` = {res!r}"
                        f" ≠ `spring.application.name` = {app_name!r}")
        if svc and res and svc != res:
            viol.append(f"{rel} ⇒ 两套服务名不一致：otel.service.name={svc!r} vs resource…={res!r}")
        sampler = otel.get("traces.sampler", "")
        if sampler not in ("always_on", "parentbased_always_on"):
            viol.append(f"{rel} ⇒ `otel.traces.sampler` = {sampler!r}"
                        f"（应 always_on / parentbased_always_on）")

    # ---- 通用规则：**任何**域，只要它的 yml 里有 `otel:` 段，就必须合规 ----
    # ⚠️ 为什么要有这一段：清单是「**必须要有**」的白名单；这一段是「**有了就不得歪**」的黑名单
    #   ⇒ ⇒ 清单外的域（如 hot · 合入 main 后 / user-service · users lane 补齐后）
    #   **一旦写出 otel 段就立刻被覆盖** ⇒ ⇒ 白名单不必随每次合入而改 ⇒ ⇒ 不会腐烂。
    for module, kind in iter_modules(root):
        if kind != "service" or "target" in module.parts:
            continue
        aid = module_artifact_id(module / "pom.xml")
        if any(aid == known for _, known in A14_DOMAINS):
            continue          # 清单内域上面已查过
        yml = module_yml(module)
        if not yml.exists():
            continue
        text = yml.read_text(encoding="utf-8")
        otel = parse_yaml_block(text, "otel")
        if not otel:
            continue          # 没有 otel 段 ⇒ 不在清单 ⇒ 本规则不管它
        rel = module.relative_to(root).as_posix()
        checked += 1
        endpoint = otel.get("exporter.otlp.endpoint", "")
        if not endpoint:
            viol.append(f"{rel} ⇒ `otel.exporter.otlp.endpoint` 缺失或为空 ⇒ 不装 exporter")
        protocol = otel.get("exporter.otlp.protocol", "")
        if protocol != "grpc":
            viol.append(f"{rel} ⇒ `otel.exporter.otlp.protocol` = {protocol!r}"
                        f"（应 `grpc` = B 侧 `Telemetry.Batcher: otlpgrpc`）")
        app_name = parse_yaml_block(text, "spring").get("application.name", "")
        svc = otel.get("service.name", "")
        res = otel.get("resource.attributes.service.name", "")
        if not res:
            viol.append(f"{rel} ⇒ 缺 `otel.resource.attributes.service.name` ⇒ 服务名无据")
        elif app_name and res != app_name:
            viol.append(f"{rel} ⇒ `otel.resource.attributes.service.name` = {res!r}"
                        f" ≠ `spring.application.name` = {app_name!r}")
        if svc and res and svc != res:
            viol.append(f"{rel} ⇒ 两套服务名不一致：otel.service.name={svc!r} vs resource…={res!r}")
        sampler = otel.get("traces.sampler", "")
        if sampler not in ("always_on", "parentbased_always_on"):
            viol.append(f"{rel} ⇒ `otel.traces.sampler` = {sampler!r}"
                        f"（应 always_on / parentbased_always_on）")
    if viol:
        return Result(FAIL, f"{len(viol)} 处违规（已核 {checked} 个域）", viol)
    return ok(f"{checked}/{checked} 个域的 `otel:` 段齐备且自洽")


# ---------------------------------------------------------------------------
# A15 · 保护层的每个 `enabled` 开关都必须**真的接到层上**（接线守门）
# ---------------------------------------------------------------------------
def check_a15(root: pathlib.Path, contract: str) -> Result:
    """A15 · `ProtectionProperties` 里每个 `enabled` 开关都必须有消费点。

    🔴 **为什么要有这条**（DRIFT-COMMON-V16 的原形态）：`rag.protection.stat.enabled` /
    `rag.protection.prometheus.enabled` 这两个开关在 C 侧**从来没被读过**
    ⇒ ⇒ 置 `false` 也毫无效果（该层恒装）⇒ ⇒ **零编译错误、零测试红灯**
    ⇒ ⇒ 只有「某个域真的去关它」的那一天才会显形 ⇒ ⇒ **静默失效**。

    ⚠️ **判据钉的是「不变量」而不是「字面值」**：脚本自己从 `ProtectionProperties.java`
    **解析出带 `enabled` 字段的嵌套类** ⇒ ⇒ **将来新增的开关自动进清单**
    ⇒ ⇒ 不必改脚本 ⇒ ⇒ 清单不会腐烂。

    ⚠️ **🔴 为什么必须先剥注释**：本仓的 javadoc 里**大量引用 B 侧源码**
    （例如 `if (props.getStat().isEnabled())` 这样的原文摘录）
    ⇒ ⇒ 不剥离的话，判据会数到**注释里的引用** ⇒ ⇒ 「接线被摘掉」也照样绿 ⇒ ⇒ **假绿**
    ⇒ ⇒ 这正是 AGENTS.md §3.1「**不要拿代码注释当对照基准**」的机器化形态。

    ⚠️ **消费点只在共享层找**：保护层的装配点全在 `common/`
    ⇒ ⇒ 域侧读它不构成「这层装不装」的判据。
    """
    props_rel = "common/src/main/java/com/rag/common/protection/ProtectionProperties.java"
    props = root / props_rel
    if not props.exists():
        return Result(FAIL, f"{props_rel} ⇒ 不存在 ⇒ 无法核对保护层开关的接线")
    code = strip_java_comments(props.read_text(encoding="utf-8"))

    # 解析出「带 `enabled` 字段的嵌套类」⇒ 每个就是一枚开关
    class_pos = [(m.group(1), m.start()) for m in re.finditer(r"public static class (\w+)", code)]
    switches: list = []
    for m in re.finditer(r"private boolean enabled\b", code):
        owner = None
        for name, pos in class_pos:
            if pos < m.start():
                owner = name
            else:
                break
        if owner and owner not in switches:
            switches.append(owner)
    if not switches:
        return Result(PENDING, f"{props_rel} ⇒ 解析不到任何 `enabled` 开关 ⇒ 判据无从施加")

    files = sorted((root / "common/src/main/java").rglob("*.java"))
    if not files:
        return Result(PENDING, "共享层 `common/src/main/java` 下没有 Java 文件 ⇒ 判据无从施加")
    codes = [strip_java_comments(f.read_text(encoding="utf-8")) for f in files]

    viol, wired = [], []
    for cls in switches:
        direct = f"get{cls}().isEnabled()"
        found = False
        for text in codes:
            if direct in text:
                found = True
                break
            # 间接形态：先把开关对象存进局部变量再读（`Stat s = props.getStat(); s.isEnabled()`）
            for var in re.findall(r"\b" + cls + r"\s+(\w+)\s*=", text):
                if f"{var}.isEnabled()" in text:
                    found = True
                    break
            if found:
                break
        if found:
            wired.append(cls)
        else:
            viol.append(f"保护层开关 `{cls}`（消费点应为 `{direct}`）**共享层零消费点**"
                        f" ⇒ 置 false 也毫无效果 ⇒ 该层恒装"
                        f"（零编译错误、零测试红灯 ⇒ 静默失效）")

    if viol:
        return Result(FAIL, f"保护层开关接线：{len(switches)} 枚开关里 {len(viol)} 枚没有消费点", viol)
    return ok(f"保护层开关接线：{len(switches)} 枚开关全都有消费点（{' / '.join(wired)}）")


# ---------------------------------------------------------------------------
# A16 · 任何 yml 的**顶层键不得重复**（YAML 重复键 ⇒ 解析器行为不确定）
# ---------------------------------------------------------------------------
def check_a16(root: pathlib.Path, contract: str) -> Result:
    """A16 · 配置文件里**同一层不得出现重复键**。

    🔴 **为什么要有这条**（2026-11-05 · 合并工作树时撞出）：**两个来源各自改了同一份 yml**
    ⇒ Git 合并**可能**把两段内容都留下来 ⇒ ⇒ **同一层出现重复的顶层键**
    ⇒ ⇒ 实测：`otel:` 在 `service/comment` / `service/task` / `service/favorite`
    的 `application.yml` 里**各出现两次**（域 lane 自己那段 ＋ 收口域统一补的那段）
    ⇒ ⇒ **YAML 重复键是未定义行为**：SnakeYAML 默认「后者覆盖前者」（⇒ 静默生效一份），
    换更严的解析器就**直接报错** ⇒ ⇒ 且**读者会看到两套互相矛盾的口径**
    ⇒ ⇒ **零编译错误、零测试红灯**（配置面 ⇒ 没有任何代码路径会报错）。

    ⚠️ **判据只查「同一层重复」**：YAML 的重复键问题**发生在同一层**；
    不同层的同名键（如 `spring.application.name` 与 `otel.service.name`）是**正常**的。
    """
    viol, checked = [], 0
    for yml in sorted(root.glob("service/**/src/main/resources/application.yml")):
        if "target" in yml.parts:
            continue
        checked += 1
        rel = yml.relative_to(root).as_posix()
        seen: dict = {}
        for i, raw in enumerate(yml.read_text(encoding="utf-8").split("\n"), 1):
            s = raw.strip()
            if not s or s.startswith("#") or s.startswith("-"):
                continue
            m = re.match(r"^([A-Za-z0-9_.\-]+):(.*)$", s)
            if not m:
                continue
            key, rest = m.group(1), m.group(2).strip()
            # 🔴 **只认「顶层键」**：缩进为 0 的才算；带值的（`key: value`）与
            #    不带值的（`key:` ＝ 容器）**都要算**（两者都能重复）。
            if raw[:1] in (" ", "\t"):
                continue
            if key in seen:
                viol.append(f"{rel} ⇒ 顶层键 `{key}` **重复**（L{seen[key]} 与 L{i}）"
                            f" ⇒ ⇒ YAML 重复键 ⇒ 解析行为不确定（SnakeYAML 后者覆盖前者，"
                            f"更严的解析器直接报错）⇒ ⇒ 必须只保留一份")
            else:
                seen[key] = i
    if viol:
        return Result(FAIL, f"{len(viol)} 处重复顶层键（已核 {checked} 份 yml）", viol)
    return ok(f"{checked}/{checked} 份 yml 的顶层键均唯一")


# ---------------------------------------------------------------------------
# A17 · 全用 Jackson 2（D14 · 2026-11-09「全用 jackson2」决策）
# ---------------------------------------------------------------------------

def _strip_java_comments(text: str) -> str:
    """把 Java 源码里的注释（行/块/文本块内的内容）抹掉，**保留行数**。

    ⚠️ **为什么必须抹注释再判**：本仓有**多处 javadoc 正面提到** Jackson 3 的字母序
    （`CanonicalHash` · `HotArticleItem` · `RecommendUser` …）⇒ 不抹注释的判据
    **必然假红** ⇒ ⇒ 那样这条断言就变成了「天天误报的噪音」⇒ 没有价值。
    """
    out, i, n = [], 0, len(text)
    in_block = in_str = in_char = in_textblock = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_textblock:
            if text.startswith('"""', i):
                in_textblock = False
                out.append('"""')
                i += 3
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                out.append("  ")
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\" and nxt:
                out.append(nxt)
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if in_char:
            out.append(c)
            if c == "\\" and nxt:
                out.append(nxt)
                i += 2
                continue
            if c == "'":
                in_char = False
            i += 1
            continue
        if text.startswith('"""', i):
            in_textblock = True
            out.append('"""')
            i += 3
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            out.append("  ")
            continue
        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "'":
            in_char = True
            out.append(c)
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def check_a17(root: pathlib.Path, contract: str) -> Result:
    """A17 · 全用 Jackson 2 —— ① 无 Jackson 3 包名 ② 不开「属性字母序」。

    🔴 **为什么要有这条**（2026-11-09 · 用户拍板「全用 jackson2」）：
    - ① **Jackson 3 包名**（`tools.jackson.*`）：D14 已把全仓钉在 Jackson 2
      ⇒ 一旦有人引了 `tools.jackson`，**编译期就能过**（Jackson 3 在 classpath 上），
      而**序列化行为会变**（Jackson 3 默认**属性字母序**）⇒ ⇒ wire 字节静默变 ⇒ **零报错**。
    - ② **`MapperFeature.SORT_PROPERTIES_ALPHABETICALLY`**：打开后 Jackson 2 也改成**字母序**
      ⇒ ⇒ 本仓的键序契约（= Go 真源 `encoding/json` 的 struct **声明序**）**全线塌掉**
      ⇒ ⇒ 而本仓绝大多数 DTO **没有** `@JsonPropertyOrder` ⇒ **没有任何注解能兜住**
      ⇒ ⇒ 只能靠**全局不打开**这条纪律 + 这条守门。
    ⚠️ **判定只针对 `src/main`**（`src/test` 里可以故意构造字母序样本做反证锚点）。
    ⚠️ **注释里的提及不算违规**（本仓多处 javadoc 正面讨论 Jackson 3 的字母序）⇒ 先抹注释。
    """
    viol, checked_java, checked_yml = [], 0, 0
    for java in sorted(root.glob("**/src/main/**/*.java")):
        if "target" in java.parts:
            continue
        checked_java += 1
        rel = java.relative_to(root).as_posix()
        code = _strip_java_comments(java.read_text(encoding="utf-8"))
        # ① Jackson 3 包名（import 或全限定名）
        for i, line in enumerate(code.split("\n"), 1):
            if "tools.jackson" in line:
                viol.append(f"{rel}:{i} ⇒ 引用了 **Jackson 3** 包名 `tools.jackson`"
                            f" ⇒ ⇒ D14 钉死全仓 Jackson 2 ⇒ ⇒ 换库会改序列化行为"
                            f"（Jackson 3 默认**属性字母序**）⇒ wire 字节静默变")
        # ② 开启「属性字母序」
        for m in re.finditer(r"SORT_PROPERTIES_ALPHABETICALLY", code):
            stmt_end = code.find(";", m.start())
            stmt = code[m.start(): stmt_end if stmt_end != -1 else m.start() + 200]
            if re.search(r"\bfalse\b", stmt):
                continue      # 显式关掉 ⇒ 合规
            viol.append(f"{rel} ⇒ **开启了** `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY`"
                        f" ⇒ ⇒ 键序从「声明序」变成**字母序**"
                        f" ⇒ 与 Go 真源 `encoding/json` 的 struct 声明序**分叉**"
                        f" ⇒ 且本仓多数 DTO 无 `@JsonPropertyOrder` ⇒ **无注解能兜住**")
    for yml in sorted(root.glob("**/src/main/resources/*.yml")):
        if "target" in yml.parts:
            continue
        checked_yml += 1
        rel = yml.relative_to(root).as_posix()
        text = yml.read_text(encoding="utf-8")
        for i, line in enumerate(text.split("\n"), 1):
            if re.search(r"sort-properties-alphabetically\s*:\s*(true|yes|on)\b", line, re.I):
                viol.append(f"{rel}:{i} ⇒ yml 打开了 `spring.jackson.mapper.sort-properties-alphabetically`"
                            f" ⇒ ⇒ 同上：键序变字母序 ⇒ 与 Go 声明序分叉")
    if viol:
        return Result(FAIL, f"{len(viol)} 处 Jackson 口径违规"
                             f"（已核 {checked_java} 份 Java + {checked_yml} 份 yml）", viol)
    return ok(f"{checked_java}/{checked_java} 份 Java + {checked_yml}/{checked_yml} 份 yml"
              f" 均为纯 Jackson 2 且未开字母序")


# ---------------------------------------------------------------------------
# 断言注册表
# ---------------------------------------------------------------------------
CHECKS = [
    ("A1", "*-rpc 不得有 spring-boot-starter-web", check_a1),
    ("A2", "*-api 必须有 spring-boot-starter-web", check_a2),
    ("A3", "*-rpc 须挂上 server.intercept(...)", check_a3),
    ("A4", "@GrpcClient ⟷ yml negotiation-type: plaintext", check_a4),
    ("A5", "@Import 清单必须为空", check_a5),
    ("A6", "MP ordering bridge 全仓只一份", check_a6),
    ("A7", "有 resilience4j ⇒ 无手写熔断器", check_a7),
    ("A8", "手写 Logger 必须实现 org.slf4j.Logger", check_a8),
    ("A9", "DTO 必须 immutable", check_a9),
    ("A10", "无手写必填校验器 · 含 DTO 须有 Bean Validation", check_a10),
    ("A11", "fact-* 独立进程须有域内装配落点", check_a11),
    ("A12", "模块内无同名简单类", check_a12),
    ("A13", "用了 Lombok ⇒ 须写原本的完整代码展开块", check_a13),
    ("A14", "追踪导出配置（otel 段）齐备且服务名自洽", check_a14),
    ("A15", "保护层每个 enabled 开关都必须有消费点（接线守门）", check_a15),
    ("A16", "yml 顶层键不得重复（YAML 重复键）", check_a16),
    ("A17", "全用 Jackson 2（无 Jackson 3 包名 · 不开属性字母序）", check_a17),
]


# ---------------------------------------------------------------------------
# 红灯自检：造一棵带已知违规的假树，验证每条判据「该红的必须红」
# ---------------------------------------------------------------------------
FIXTURE_VIOLATIONS = {
    "A1": "rpc 模块引入 web starter",
    "A2": "api 模块缺 web starter",
    "A3": "rpc 模块未挂 server.intercept",
    "A4": "@GrpcClient 缺 plaintext / yml 哑弹通道",
    "A5": "残留 @Import",
    "A6": "MP ordering bridge 有两份",
    "A7": "手写熔断器",
    "A8": "日志载体未接入 slf4j",
    "A9": "DTO 有 setter",
    "A10": "手写必填校验器 / 有 DTO 缺 Bean Validation",
    "A11": "缺 FactProcessLogging 落点",
    "A12": "模块内同名类",
    "A13": "Lombok 类缺「原本的完整代码」展开块",
    "A14": "域的 application.yml 缺 `otel:` 段（追踪导出配置）",
    "A15": "保护层开关（ProtectionProperties 的 enabled）共享层零消费点",
    "A16": "yml 顶层键重复（YAML 重复键）",
    "A17": "混入 Jackson 3 包名 / 开启了属性字母序",
}


def _write(p: pathlib.Path, text: str) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def build_fixture(base: pathlib.Path) -> pathlib.Path:
    """造一棵**每个模块都含已知违规**的假树。"""
    root = base / "fixture"
    j = "src/main/java/com/rag/x"

    _write(root / "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                            "<artifactId>fixture-parent</artifactId></project>")
    # --- 契约 A 用的 rpc / api 模块（A1 / A2 / A3） ---
    _write(root / "service/x/x-rpc/pom.xml",
           "<project><dependencies><dependency><artifactId>spring-boot-starter-web</artifactId>"
           "</dependency></dependencies></project>")   # A1 违规
    _write(root / "service/x/x-rpc/src/main/java/com/rag/x/GrpcCfg.java",
           "package com.rag.x;\n"
           "public class GrpcCfg implements GrpcServerConfigurer {\n"
           "  public void configure(GrpcServer server) { }\n}\n")   # A3 违规：无 server.intercept
    _write(root / "service/x/x-api/pom.xml",
           "<project><dependencies></dependencies></project>")        # A2 违规：缺 web starter
    # --- 契约 B 用的 service 模块 ---
    _write(root / "service/x/x-service/pom.xml",
           "<project><dependencies></dependencies></project>")        # A10 违规：缺 Bean Validation
    _write(root / f"{j}/dto/BadDto.java",
           "package com.rag.x.dto;\n"
           "public class BadDto {\n"
           "  private String name;\n"
           "  public void setName(String n) { this.name = n; }\n}\n")   # A9 违规
    _write(root / f"{j}/dto/GoodDto.java", "package com.rag.x.dto;\npublic record GoodDto() {}\n")
    # ⚠️ **子包负向 fixture**（2026-10-26 · D11）—— `dto/` 已按业务对象拆子包，
    #    若 A9 的 glob 只扫 `dto/*.java`（零层），这两个**子包里的坏 DTO 会被漏掉** ⇒ 假绿。
    #    ⇒ 把坏 DTO 放进 `dto/user/`：只有递归 glob 才抓得到 ⇒ 自检能证明这一点。
    _write(root / f"{j}/dto/user/NestedBadDto.java",
           "package com.rag.x.dto.user;\n"
           "public class NestedBadDto {\n"
           "  private String name;\n"
           "  public void setName(String n) { this.name = n; }\n}\n")   # A9 违规（子包内）
    _write(root / f"{j}/dto/user/NestedGoodDto.java",
           "package com.rag.x.dto.user;\npublic record NestedGoodDto() {}\n")   # 合规（子包内）
    _write(root / f"{j}/GrpcClientUse.java",
           "package com.rag.x;\n"
           "public class GrpcClientUse {\n"
           "  @GrpcClient(\"foo-rpc\")\n"
           "  private Object foo;\n}\n")                                # A4 违规①
    _write(root / "service/x/x-service/src/main/resources/application.yml",
           "grpc:\n"
           "  client:\n"
           "    bar-rpc:\n"
           "      address: 127.0.0.1:9001\n")                        # A4 违规②（哑弹通道）
    _write(root / f"{j}/ImportUse.java",
           "package com.rag.x;\n"
           "import org.springframework.context.annotation.Import;\n"
           "@Import(Other.class)\n"
           "public class ImportUse { }\n")                            # A5 违规
    _write(root / f"{j}/MybatisPlusOrderingBridgeAutoConfiguration.java",
           "package com.rag.x;\npublic class MybatisPlusOrderingBridgeAutoConfiguration { }\n")
    _write(root / "common/src/main/java/com/rag/common/MybatisPlusOrderingBridgeAutoConfiguration.java",
           "package com.rag.common;\npublic class MybatisPlusOrderingBridgeAutoConfiguration { }\n")  # A6 违规
    _write(root / "common/pom.xml",
           "<project><dependencies><dependency><groupId>io.github.resilience4j</groupId>"
           "<artifactId>resilience4j-spring-boot3</artifactId></dependency></dependencies></project>")
    _write(root / f"{j}/GoStyleBreaker.java",
           "package com.rag.x;\npublic class GoStyleBreaker { }\n")   # A7 违规
    _write(root / f"{j}/logger/GoStyleLogger.java",
           "package com.rag.x.logger;\n"
           "public class GoStyleLogger {\n"
           "  public void info(String m) { }\n}\n")                   # A8 违规（非空壳但未接 slf4j）
    _write(root / f"{j}/RequiredValidator.java",
           "package com.rag.x;\npublic class RequiredValidator { }\n")  # A10 违规①
    # A11 违规：`comment-service` 模块（**按 artifactId 定位**，与真实仓同形）只放两个，缺 FactProcessLogging
    _write(root / "service/comment/pom.xml",
           "<project><artifactId>comment-service</artifactId><dependencies></dependencies></project>")
    _write(root / "service/comment/src/main/java/com/rag/comment/wiring/"
                  "CommentFactDispatchScheduler.java",
           "package com.rag.comment.wiring;\npublic class CommentFactDispatchScheduler { }\n")
    _write(root / "service/comment/src/main/java/com/rag/comment/wiring/"
                  "CommentAuthorityEndpointConfig.java",
           "package com.rag.comment.wiring;\npublic class CommentAuthorityEndpointConfig { }\n")
    # A14 违规：`comment-service` 的 application.yml **缺 `otel:` 段**
    #   ⇒ 端点为空 ⇒ 共享层不装 exporter ⇒ 该域的 span 一条都导不出去（静默失效）
    _write(root / "service/comment/src/main/resources/application.yml",
           "spring:\n  application:\n    name: comment-service\n"
           "otel:\n  service:\n    name: comment-service\n"
           "otel:\n  service:\n    name: comment-rpc\n")   # A16 违规：顶层 `otel:` 重复两次
    # A12 违规：同名类，不同包
    _write(root / f"{j}/Dup.java", "package com.rag.x;\npublic class Dup { }\n")
    _write(root / f"{j}/sub/Dup.java", "package com.rag.x.sub;\npublic class Dup { }\n")
    # A13 违规：类上有 Lombok 注解，但 javadoc **缺**「原本的完整代码」展开块
    _write(root / f"{j}/LombokNoExpand.java",
           "package com.rag.x;\n"
           "import lombok.Data;\n"
           "/**\n * <p>普通 javadoc，**没有**展开块。\n */\n"
           "@Data\n"
           "public class LombokNoExpand {\n"
           "    private Long id;\n"
           "}\n")
    # A15 违规：`ProtectionProperties` 里的 `Stat.enabled` 开关**共享层零消费点**
    #   ⇒ 置 false 也毫无效果 ⇒ 该层恒装（零编译错误、零测试红灯 ⇒ 静默失效）
    # ⚠️ 注释里**故意**引用一次 `props.getStat().isEnabled()` ⇒ 验证判据**不会**被注释骗到
    _write(root / "common/src/main/java/com/rag/common/protection/ProtectionProperties.java",
           "package com.rag.common.protection;\n"
           "public class ProtectionProperties {\n"
           "  public static class Stat {\n"
           "    private boolean enabled = true;\n"
           "    public boolean isEnabled() { return enabled; }\n"
           "    public void setEnabled(boolean e) { this.enabled = e; }\n"
           "  }\n"
           "  public Stat getStat() { return new Stat(); }\n"
           "}\n")
    _write(root / "common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java",
           "package com.rag.common.autoconfigure;\n"
           "public class ProtectionAutoConfiguration {\n"
           "  // if (props.getStat().isEnabled()) { ... }  <= **注释里的引用不算消费点**\n"
           "}\n")
    # A17 违规：① 引用 **Jackson 3** 包名 ② **开启**「属性字母序」
    #   ⇒ 两者任一都会让键序从「声明序」变「字母序」⇒ 与 Go 真源分叉 ⇒ wire 字节静默变
    # ⚠️ 注释里**故意**提一次 Jackson 3 与字母序 ⇒ 验证判据**不会**被注释骗到（必须抹注释再判）
    _write(root / f"{j}/BadJacksonUsage.java",
           "package com.rag.x;\n"
           "/**\n"
           " * <p>注释里提到 tools.jackson 与 SORT_PROPERTIES_ALPHABETICALLY **不算违规**。\n"
           " */\n"
           "import tools.jackson.databind.ObjectMapper;\n"
           "import com.fasterxml.jackson.databind.MapperFeature;\n"
           "public class BadJacksonUsage {\n"
           "  public ObjectMapper m = new ObjectMapper()\n"
           "      .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);\n"
           "}\n")
    return root


def self_test_a3_contract_b() -> tuple[bool, list]:
    """A3 · **契约 B（合并形态）** 的三向自检。

    ⚠️ 为什么要单独造：**A3 不是「api/rpc 分离」的派生物** —— 合并后拦截链照样要挂，
    只是挂载点从「每 rpc 模块」变成「共享层 1 份」。⇒ 判据必须同时管住**两个方向**：
      ① 正向：共享层有 `GrpcServerConfigurer` 且真调 `server.intercept(...)`
      ② 反向：域内**不得**重复定义（否则同一条链被挂 2 次）

    三种树各验一次：合规 ⇒ 绿 · 缺挂载 ⇒ 红 · 域内重复 ⇒ 红
    """
    base = pathlib.Path(tempfile.mkdtemp(prefix="arch-a3b-"))
    try:
        def make(mount: bool, dup: bool) -> pathlib.Path:
            r = base / f"t{int(mount)}{int(dup)}"
            _write(r / "pom.xml", "<project><artifactId>x-parent</artifactId></project>")
            _write(r / "common/pom.xml", "<project><artifactId>common</artifactId></project>")
            body = ("package com.rag.common;\n"
                    "import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;\n"
                    "public class GrpcServerCfg implements GrpcServerConfigurer {\n"
                    "  public void configure(ServerBuilder<?> server) { %s }\n}\n")
            _write(r / "common/src/main/java/com/rag/common/GrpcServerCfg.java",
                   body % ("server.intercept(x);" if mount else ""))
            _write(r / "service/x/pom.xml", "<project><artifactId>x-service</artifactId></project>")
            if dup:
                _write(r / "service/x/src/main/java/com/rag/x/DupCfg.java",
                       "package com.rag.x;\n"
                       "import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;\n"
                       "public class DupCfg { GrpcServerConfigurer c() { return null; } }\n")
            return r

        results = []
        r = check_a3(make(True, False), "B")
        results.append(("合规树（common 挂载 + 域内 0 重复）⇒ 应 PASS", r.state == PASS, r.detail))
        r = check_a3(make(False, False), "B")
        results.append(("common 有 Configurer 但缺 server.intercept ⇒ 应 FAIL", r.state == FAIL, r.detail))
        r = check_a3(make(True, True), "B")
        results.append(("域内重复声明 GrpcServerConfigurer ⇒ 应 FAIL", r.state == FAIL, r.detail))
        # ④ 空壳 ⇒ PENDING（判据未施加，不算 FAIL）
        shell = base / "shell"
        _write(shell / "pom.xml", "<project><artifactId>x-parent</artifactId></project>")
        _write(shell / "common/pom.xml", "<project><artifactId>common</artifactId></project>")
        _write(shell / "common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java",
               "package com.rag.common.autoconfigure;\n"
               "public class ProtectionAutoConfiguration { }\n")
        r = check_a3(shell, "B")
        results.append(("挂载点仍是空壳 ⇒ 应 PENDING（判据未施加）", r.state == PENDING, r.detail))
        return all(ok for _, ok, _ in results), results
    finally:
        shutil.rmtree(base, ignore_errors=True)


def self_test_a9_nested() -> tuple[bool, list]:
    """A9 · **子包负向自检** —— 证明 glob 真的递归到了 `dto/<业务对象>/` 子包。

    ⚠️ **为什么这条必须单独存在**：只造一个 `dto/user/BadDto.java`，
    **零层的 `dto/` 根下不放任何 DTO** ⇒ 若 A9 的 glob 只扫 `dto/*.java`，
    它会扫到 **0 个 DTO** ⇒ 判据**静默通过**（假绿）⇒ 而常规自检只看「红/不红」，
    零层 glob 同样会红（漏抓的那个不算）⇒ **常规自检抓不住这个洞**。
    ⇒ 这条专测：**违规清单里必须出现 `dto/user/` 下的文件**。
    """
    base = pathlib.Path(tempfile.mkdtemp(prefix="arch-self-a9-"))
    try:
        root = base / "fixture"
        _write(root / "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                                "<artifactId>fixture-parent</artifactId></project>")
        _write(root / "service/x/x-service/pom.xml",
               "<project><dependencies></dependencies></project>")
        j = "service/x/x-service/src/main/java/com/rag/x"
        _write(root / f"{j}/dto/user/NestedBadDto.java",
               "package com.rag.x.dto.user;\n"
               "public class NestedBadDto {\n"
               "  private String name;\n"
               "  public void setName(String n) { this.name = n; }\n}\n")
        r = check_a9(root, "B")
        caught = (r.state == FAIL
                  and any("dto/user/" in v for v in r.violations))
        results = [("子包 `dto/user/` 里的坏 DTO 必须被抓到（零层 glob 会漏 ⇒ 假绿）",
                    caught, r.detail)]
        return all(ok for _, ok, _ in results), results
    finally:
        shutil.rmtree(base, ignore_errors=True)


def self_test() -> int:
    """红灯自检 —— 逐条断言「该红的必须红」。"""
    print("=" * 78)
    print("红灯自检（--self-test）—— 在临时假树上验证每条判据确实会红")
    print("=" * 78)
    base = pathlib.Path(tempfile.mkdtemp(prefix="arch-self-test-"))
    try:
        root = build_fixture(base)
        failed = []
        for aid, title, fn in CHECKS:
            contract = "A" if aid in ("A1", "A2", "A3") else "B"
            r = fn(root, contract)
            ok_red = r.state == FAIL
            mark = "✅ 红" if ok_red else "❌ 没红"
            print(f"  {mark}  {aid:<4} {title}")
            print(f"          期望 FAIL，实际 {r.state} —— {r.detail}")
            if not ok_red:
                failed.append(aid)
        print("-" * 78)
        if failed:
            print(f"红灯自检失败：{len(failed)} 条判据**没红** ⇒ 该断言等于没有断言：{', '.join(failed)}")
            return 1
        print(f"红灯自检通过：{len(CHECKS)}/{len(CHECKS)} 条判据均能红")

        # ── A9 的**子包 glob**负向自检（glob 漏子包 ⇒ 扫到 0 个 ⇒ 假绿）──
        print("-" * 78)
        print("A9 · `dto/` 子包 glob 负向自检")
        a9_ok, a9 = self_test_a9_nested()
        for title, okflag, detail in a9:
            mark = "✅" if okflag else "❌"
            print(f"  {mark}  {title}")
            print(f"          {detail}")
        if not a9_ok:
            print("A9 子包 glob 自检失败 ⇒ 判据在 `dto/<业务对象>/` 下**静默失效**（假绿）")
            return 1
        print("A9 子包 glob 自检通过：递归 glob 确实扫到了 `dto/<业务对象>/` 子包")

        # ── A3 的**契约 B（合并形态）**三向自检 ──────────────────────
        print("-" * 78)
        print("A3 · 契约 B（api+rpc 合并 · RAG-V1 形态）三向自检")
        a3b_ok, a3b = self_test_a3_contract_b()
        for title, okflag, detail in a3b:
            mark = "✅" if okflag else "❌"
            print(f"  {mark}  {title}")
            print(f"          {detail}")
        if not a3b_ok:
            print("A3 契约 B 自检失败 ⇒ 该判据在合并形态下形同虚设")
            return 1
        print("A3 契约 B 自检通过：合规⇒绿 · 缺挂载⇒红 · 域内重复⇒红")
        return 0
    finally:
        shutil.rmtree(base, ignore_errors=True)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="架构符合性守门（A1–A17）")
    ap.add_argument("--contract", choices=["A", "B"], default="B",
                    help="断言绑定契约：A=api/rpc 分离（B 侧部署等价）· B=api+rpc 合并（RAG-V1，默认）")
    ap.add_argument("--only", nargs="+", metavar="A#", help="只跑指定断言，如 --only A4 A11")
    ap.add_argument("--self-test", action="store_true", help="红灯自检：造违规假树，验证判据会红")
    ap.add_argument("--root", default=str(ROOT), help="仓库根目录（自检用）")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    only = {a.upper() for a in args.only} if args.only else None

    print("=" * 78)
    print(f"架构符合性守门 —— 契约 {args.contract}"
          f"（{'api/rpc 分离' if args.contract == 'A' else 'api+rpc 合并 · RAG-V1'}）")
    print(f"仓库：{root}")
    print("=" * 78)

    results = []
    for aid, title, fn in CHECKS:
        if only and aid not in only:
            continue
        r = fn(root, args.contract)
        results.append((aid, title, r))
        icon = {PASS: "✅", FAIL: "🔴", NA: "➖", PENDING: "⏳"}[r.state]
        print(f"  {icon} {aid:<4} {title}")
        print(f"        {r.state:<8} {r.detail}")
        for v in r.violations:
            print(f"               · {v}")

    n_fail = sum(1 for _, _, r in results if r.state == FAIL)
    n_pending = sum(1 for _, _, r in results if r.state == PENDING)
    n_na = sum(1 for _, _, r in results if r.state == NA)
    n_pass = sum(1 for _, _, r in results if r.state == PASS)
    print("-" * 78)
    print(f"  PASS {n_pass} · FAIL {n_fail} · N/A {n_na} · PENDING {n_pending}  （共 {len(results)} 条）")
    if n_pending:
        print("  ⏳ PENDING = 断言对象仍是空壳 ⇒ 判据未施加（落地实现后自动转 PASS/FAIL）")
    if n_fail:
        print(f"  🔴 有漂移 ⇒ EXIT=1")
    else:
        print("  ✅ 无漂移 ⇒ EXIT=0")
    return 1 if n_fail else 0


if __name__ == "__main__":
    sys.exit(main())
