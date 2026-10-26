#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""架构符合性守门（A1–A12）。

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
| **A** | Go 部署等价（api / rpc 分离） | A1 · A2 · A3 · A4–A12 |
| **B** | Spring 惯用（api + rpc 合并）★ RAG-V1 | A4–A12 |

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
        # Go 侧 communityfact.NewProcessLogger 是一个**工厂**（返回 *slog.Logger），
        # 对应的 Java 侧 ProcessLogger 同样是「产出 Logger 的工厂」⇒ 工厂本身不必 implements 接口。
        # 若后续把它改成直接实现 Logger，则本例外自动不再需要（可删）。
        "ProcessLogger": "Go communityfact.NewProcessLogger 是工厂（返回 *slog.Logger）"
                         "⇒ Java 侧同类亦为工厂，无需 implements slf4j.Logger",
    },
}

# A11：三域各自必须有的 fact-* 装配落点（Go 侧 cmd/fact-* 独立进程 ⇒ 域内组件）
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

# A10：Go 风格「手写必填校验器」的特征类名（RAG 侧 `GoStyleRequired*` / `GoStyleRequest` 同款）
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
    Go 侧范围内 **10 个服务逐条显式**调 `AddUnaryInterceptors(...)`（`service/*/rpc/*.go`），
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


# ---------------------------------------------------------------------------
# A4 · 双向交叉引用（devh 默认 TLS，Go zrpc 是明文 gRPC）
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
            src = f.read_text(encoding="utf-8")
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
    """
    hits = []
    for f in root.glob("**/src/main/java/**/*.java"):
        if "target" in f.parts:
            continue
        src = f.read_text(encoding="utf-8")
        if "MybatisPlus" in src and "AutoConfiguration" in f.name:
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

    判据对象 = 简单名以 `Logger` 结尾，或以 `ProcessLogging` 结尾的类（Go 的 `logx` call_chain
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
    """A9 · `dto` 包下的类型必须 immutable（`record` 或 final 字段 + 无 setter）。"""
    viol, n = [], 0
    for f in root.glob("**/dto/*.java"):
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
    """A10 · ① 不得存在 Go 风格手写必填校验器 ② 含 DTO 的模块必须引入 Bean Validation。

    手写校验器 = 放弃整个 Bean Validation 生态（消息插值、分组、自定义约束）。
    """
    viol = []
    for f in root.glob("**/src/main/java/**/*.java"):
        if "target" in f.parts or not is_rag_type(f):
            continue
        if A10_HANDWRITTEN_VALIDATOR.search(f.stem):
            viol.append(f"{f.relative_to(root)} → 手写必填校验器")
    for module, _ in iter_modules(root):
        has_dto = any(module.glob("src/main/java/**/dto/*.java"))
        if not has_dto:
            continue
        txt = pom_text(module)
        if "spring-boot-starter-validation" not in txt and "jakarta.validation" not in txt:
            viol.append(f"{module.relative_to(root)}/pom.xml → 有 DTO 但未引入 Bean Validation")
    if viol:
        return Result(FAIL, f"{len(viol)} 处违反 Bean Validation 约定", viol)
    return ok("无手写必填校验器 · 含 DTO 的模块均已引入 Bean Validation")


# ---------------------------------------------------------------------------
# A11 · Go 侧 cmd/fact-* 独立进程 ⇒ 域内必须有对应装配落点
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
    # A12 违规：同名类，不同包
    _write(root / f"{j}/Dup.java", "package com.rag.x;\npublic class Dup { }\n")
    _write(root / f"{j}/sub/Dup.java", "package com.rag.x.sub;\npublic class Dup { }\n")
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
    ap = argparse.ArgumentParser(description="架构符合性守门（A1–A12）")
    ap.add_argument("--contract", choices=["A", "B"], default="B",
                    help="断言绑定契约：A=api/rpc 分离（Go 部署等价）· B=api+rpc 合并（RAG-V1，默认）")
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
