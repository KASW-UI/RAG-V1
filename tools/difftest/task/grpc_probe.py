#!/usr/bin/env python3
"""task 域双侧活体对拍 —— **gRPC 面**探针（驱动 + 比对）。

补的是台账 §七·7-1 的 **U-6**：「gRPC 面尚未双侧活体对拍」
—— 现有 `http_probe.py` 的四个面（M1 HTTP 字节面 / M2 Redis 状态面 / M3 PG 状态面 /
M4 冻结锚面）**全是 HTTP 面** ⇒ 「B 侧 task-rpc 与 C 侧 task-service 在 gRPC 面
是否逐字节一致」**无任何证据**。

⚠️ **为什么客户端是 Java 而不是 python**：
    本机**没有** `grpcio`（`import grpc` 直接 ModuleNotFoundError）⇒ python 侧
    要手写 HTTP/2 + protobuf ⇒ 成本高且易错 ⇒ ⇒ 改为**现编一个 Java 探针**
    （`grpc_probe/GrpcProbe.java`）⇒ 用真 netty client 打真 gRPC server。
    ⚠️ **classpath 从 C 侧 fat jar 的 `BOOT-INF/lib/` 解出来**
    ⇒ 版本与「真正在跑的那套依赖」**完全一致**（不引入第二份版本来源）。

判定口径（**与 `http_probe.py` 完全同款四档**，任一档不满足即 EXIT=1）：
    · **普通用例**：B 与 C 的读数**必须逐字节相同**；
      且（若给了 `--expected`）C 与**冻结锚**（B 侧读数）也必须逐字节相同
      ⇒ oracle 漂移会被抓到。
    · **已知差异用例**（登记在 `--known-cases`）：B 与 C **允许**不同，但
      ① C 的读数必须**逐字节等于** `--known-readings` 里登记的形状（⇒ 差异形状变了 ⇒ 失败）；
      ② B 的读数必须逐字节等于冻结锚（⇒ B 侧漂移会被抓到）。
      ⇒ **白名单不是「豁免」** —— 它把「允许不同」钉成「只允许差这一处，且必须还是当初那一处」。
    · **无效读数档**（2026-11-10 补，与 `http_probe.py` 同款）：**oracle 侧（B）**本轮
      没给出业务应答时，本条**不可判** ⇒ 记 `INVALID` ⇒ **不计入一致数、也不计真差异**
      ⇒ 整轮结论是「环境不稳 ⇒ 需重跑」（EXIT=3），**不是**「C 侧有差异」。

退出码：
    0 = 全部可判用例逐字节一致
    1 = 有真差异
    3 = **有不可判用例**（oracle 侧至少一条没给出业务应答）⇒ 结论不可信 ⇒ 需重跑
    2 = 探针本身跑不起来（读数缺失 / 用例表非法）

子命令：
    build    解 fat jar 的 BOOT-INF/lib + 编译探针
             usage: grpc_probe.py build --fat-jar <jar> --work <dir>
    probe    单侧跑一次，打印读数
             usage: grpc_probe.py probe --port P --cases <file> --work <dir>
    compare  跑完两侧并逐条比对
             usage: grpc_probe.py compare --b-port P --c-port Q --cases <file> --work <dir>
                                        [--freeze <file>] [--freeze-known <file>]
                                        [--expected <file>]
                                        [--known-cases <file>] [--known-readings <file>]
                                        [--out <file>]
"""

from __future__ import annotations

import argparse
import difflib
import os
import pathlib
import shutil
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
PROBE_SRC = HERE / "grpc_probe" / "GrpcProbe.java"


# ── 构建（解 classpath + 编译探针）─────────────────────────────────────────────
def cmd_build(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fat-jar", required=True, help="C 侧 Spring Boot fat jar")
    ap.add_argument("--work", required=True, help="工作目录（classpath 与 class 文件落这里）")
    args = ap.parse_args(argv)

    work = pathlib.Path(args.work)
    cp_dir = work / "cp"
    classes_dir = work / "classes"
    if cp_dir.exists():
        shutil.rmtree(cp_dir)
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    cp_dir.mkdir(parents=True, exist_ok=True)
    classes_dir.mkdir(parents=True, exist_ok=True)

    fat = pathlib.Path(args.fat_jar)
    if not fat.exists():
        sys.stderr.write(f"!! fat jar 不存在：{fat}\n")
        return 2

    # ⚠️ 解 **整个** BOOT-INF/lib ⇒ grpc-netty-shaded / grpc-api / grpc-core /
    #    grpc-stub / grpc-protobuf / protobuf-java / guava / perfmark / proto jar 全都在里面
    #    ⇒ 版本与「真正在跑的那套依赖」一致（不另找 ~/.m2 的第二份版本）
    r = subprocess.run(
        ["unzip", "-q", "-o", "-j", str(fat), "BOOT-INF/lib/*.jar", "-d", str(cp_dir)],
        capture_output=True, text=True)
    if r.returncode != 0:
        sys.stderr.write(f"!! 解 fat jar 失败：{r.stderr}\n")
        return 2
    jars = sorted(cp_dir.glob("*.jar"))
    if not jars:
        sys.stderr.write("!! fat jar 里没有 BOOT-INF/lib/*.jar ⇒ 探针无法构建\n")
        return 2

    cp = f"{cp_dir}/*"
    r = subprocess.run(
        ["javac", "-nowarn", "-cp", cp, "-d", str(classes_dir), str(PROBE_SRC)],
        capture_output=True, text=True)
    if r.returncode != 0:
        sys.stderr.write(f"!! 编译探针失败：\n{r.stdout}\n{r.stderr}\n")
        return 2
    print(f"✓ 探针就绪（classpath {len(jars)} 个 jar · {classes_dir}）")
    return 0


# ── 跑一侧 ─────────────────────────────────────────────────────────────────────
def run_side(work: str, cases: str, port: int, out: pathlib.Path) -> dict[str, str]:
    """跑一侧 ⇒ 返回 {用例名: 读数}。"""
    cp = f"{work}/cp/*:{work}/classes"
    r = subprocess.run(
        ["java", "-cp", cp, "GrpcProbe", "--cases", cases, "--port", str(port), "--out", str(out)],
        capture_output=True, text=True, timeout=300)
    if r.returncode != 0:
        sys.stderr.write(f"!! 探针执行失败（port={port}）：\n{r.stdout}\n{r.stderr}\n")
        return {}
    readings: dict[str, str] = {}
    with open(out, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            name, _, reading = line.partition("\t")
            readings[name] = reading
    return readings


def load_cases(path: str) -> list[tuple[str, str]]:
    """读用例表 ⇒ [(名称, user_id)]。"""
    cases: list[tuple[str, str]] = []
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, start=1):
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) != 2:
                raise SystemExit(f"{path}:{lineno} 期望 2 个制表符分隔字段，实得 {len(fields)}")
            cases.append((fields[0].strip(), fields[1].strip()))
    return cases


def _read_frozen(path: str | None) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path or not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            name, _, reading = line.partition("\t")
            out[name] = reading
    return out


# ── 「oracle 无效读数」判定（2026-11-10 补 · 与 http_probe.py 同款）───────────
# 🔴 **为什么只把这三个 status 判为「无效」**：
#     B 侧 task-rpc 的**合法业务 status 只有两个** ——
#     `OK`（正常）与 `INVALID_ARGUMENT`（`uid <= 0`，见
#     `service/task/task-rpc/.../service/TaskDomainService.java:90-93`
#     ＋ `service/task/task-rpc/.../service/impl/TaskServiceGrpcImpl.java:80-88` 的透传），
#     外加领域层裸 error 的兜底 `UNKNOWN`（`TaskServiceGrpcImpl.java:89-92`）。
#     ⇒ 而 `UNAVAILABLE` / `DEADLINE_EXCEEDED` / `CANCELLED` **不在业务 status 集合里**
#     ⇒ 它们**只可能**来自传输层（连不上 / 超时 / 被取消）
#     ⇒ ⇒ **B 侧出现这三个 status ⇒ oracle 自己没答上来 ⇒ 本条不可判**。
#     ⚠️ **`INTERNAL` / `UNKNOWN` / `RESOURCE_EXHAUSTED` 不算无效** ——
#        它们是**服务端真正产生的 status**（业务语义）⇒ 必须参与逐字节比对
#        （否则真差异会被降级成「不可判」⇒ 漏判）。
INVALID_STATUSES = ("UNAVAILABLE", "DEADLINE_EXCEEDED", "CANCELLED")


def invalid_reason(reading: str) -> str | None:
    """这一侧**有没有给出业务应答**？⇒ 返回「无效原因」；`None` = 有效读数。

    🔴 **判据只看 status 码，不看 description 文案** —— 与 HTTP 面同款理由：
        实测到的那个超时文案在 B 仓源码树里**搜不到** ⇒ 没有 `文件:行号` 可钉
        ⇒ ⇒ 不能把它写成判据（写进去就是「拿无法复核的字符串当规则」）。
    """
    if not reading or reading == "<MISSING>":
        return "<MISSING>"
    if reading.startswith("status="):
        status = reading[len("status="):].split("|", 1)[0]
        if status in INVALID_STATUSES:
            return f"gRPC {status}"
    return None


def cmd_probe(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--cases", required=True)
    ap.add_argument("--work", required=True)
    args = ap.parse_args(argv)
    out = pathlib.Path(args.work) / f"grpc-readings-{args.port}.txt"
    readings = run_side(args.work, args.cases, args.port, out)
    for name, reading in readings.items():
        print(f"{name}\t{reading}")
    return 0 if readings else 1


def cmd_compare(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--b-port", type=int, required=True)
    ap.add_argument("--c-port", type=int, required=True)
    ap.add_argument("--cases", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--freeze", help="把 B 侧读数写进该文件（冻结 B 侧为 oracle）")
    ap.add_argument("--freeze-known", help="把「已登记差异用例」的 C 侧读数写进该文件")
    ap.add_argument("--expected", help="冻结锚文件（B 侧读数）⇒ 校验两侧都没漂移")
    ap.add_argument("--known-cases", help="已登记的表达层差异用例清单（<名称>\\t<原因>）")
    ap.add_argument("--known-readings", help="已登记差异的 C 侧读数形状（<名称>\\t<读数>）")
    ap.add_argument("--out", help="把逐条明细写进该文件")
    args = ap.parse_args(argv)

    cases = load_cases(args.cases)
    expected = _read_frozen(args.expected)
    known_reasons: dict[str, str] = {}
    if args.known_cases and os.path.exists(args.known_cases):
        with open(args.known_cases, encoding="utf-8") as fh:
            for line in fh:
                line = line.rstrip("\n")
                if not line.strip() or line.lstrip().startswith("#"):
                    continue
                name, _, reason = line.partition("\t")
                known_reasons[name] = reason
    known_readings = _read_frozen(args.known_readings)

    work = pathlib.Path(args.work)
    b_readings = run_side(args.work, args.cases, args.b_port, work / "grpc-b.txt")
    c_readings = run_side(args.work, args.cases, args.c_port, work / "grpc-c.txt")
    if not b_readings or not c_readings:
        sys.stderr.write("!! 探针没跑出读数（两侧至少一侧失败）\n")
        return 2

    lines: list[str] = []
    out_expected: list[str] = []
    out_known: list[str] = []
    failed = 0        # **真差异**
    known_n = 0       # **已登记的表达层差异**（计数，不算失败）
    invalid_n = 0     # **不可判**（oracle 侧本轮没给出业务应答 ⇒ 不计入任何一侧）

    for name, _uid in cases:
        rb = b_readings.get(name, "<MISSING>")
        rc = c_readings.get(name, "<MISSING>")
        # 🔴 **冻结时只收「oracle 侧有业务应答」的读数** —— 把 DEADLINE_EXCEEDED 冻进锚里
        #    ⇒ 锚就变成「一条超时」⇒ 下一轮所有 C 侧正常读数都会被误判成「与冻结锚不一致」
        #    ⇒ ⇒ **冻结一个无效读数 = 毒化锚** ⇒ 这里直接跳过。
        b_inv = invalid_reason(rb)
        c_inv = invalid_reason(rc)
        if b_inv is None:
            out_expected.append(f"{name}\t{rb}")
        if name in known_reasons:
            out_known.append(f"{name}\t{rc}")

        same = rb == rc
        notes: list[str] = []
        verdict = "SAME" if same else "DIFF"

        # ── 🔴 「oracle 无效读数」档（2026-11-10 补 · 与 http_probe.py 同款）────
        if b_inv is not None:
            invalid_n += 1
            verdict = "INVALID"
            notes.append(f"**oracle 侧无业务应答**（{b_inv}）⇒ 本条不可判")
            # 🔴 **「不可判」只免 B↔C 那一对，不免 C 侧的独立判据**：
            #    · **普通用例** ⇒ C 的基准是**冻结锚**（= oracle 的历史读数）；
            #    · **已登记差异用例** ⇒ C 的基准是**登记形状**（`known_readings`），
            #      **不是**冻结锚（⇒ C 本来就与锚不同 ⇒ 拿锚判它 = **必然假红**）。
            if c_inv is None:
                if name in known_reasons:
                    want = known_readings.get(name)
                    if want is None:
                        notes.append(f"已知差异 · **缺登记形状**（{known_reasons[name]}）")
                        failed += 1
                        verdict = "DIFF"
                    elif want != rc:
                        notes.append(f"已知差异 · **形状变了**（登记：{known_reasons[name]}）")
                        failed += 1
                        verdict = "DIFF"
                    else:
                        notes.append(f"已知差异 · 形状与登记一致（{known_reasons[name]}）")
                elif expected and name in expected and expected[name] != rc:
                    notes.append("**C 侧读数与冻结锚不一致**（回归）")
                    failed += 1
                    verdict = "DIFF"
        elif c_inv is not None:
            failed += 1
            verdict = "DIFF"
            notes.append(f"**C 侧无业务应答**（{c_inv}）而 oracle 侧有 ⇒ 行为差异")
        elif name in known_reasons:
            known_n += 1
            want = known_readings.get(name)
            if want is None:
                notes.append(f"已知差异 · **缺登记形状**（{known_reasons[name]}）")
                failed += 1
            elif want != rc:
                notes.append(f"已知差异 · **形状变了**（登记：{known_reasons[name]}）")
                failed += 1
            else:
                notes.append(f"已知差异 · 形状与登记一致（{known_reasons[name]}）")
            if expected and name in expected and expected[name] != rb:
                notes.append("**B 侧读数与冻结锚不一致**（oracle 漂移）")
                failed += 1
        else:
            if not same:
                notes.append("**B↔C 逐字节不一致**")
                failed += 1
            if expected and name in expected and expected[name] != rc:
                notes.append("**C 侧读数与冻结锚不一致**（回归）")
                failed += 1

        lines.append(f"### {name}")
        lines.append(f"  B: {rb}")
        lines.append(f"  C: {rc}")
        lines.append(f"  => {verdict}"
                     + ("  [" + " ｜ ".join(notes) + "]" if notes else ""))

    report = "\n".join(lines) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(report)
    # 🔴 **冻结时若存在「不可判」条目 ⇒ 拒绝写锚**（2026-11-10 补 · 与 http_probe.py 同款）：
    #    部分锚 = **静默削弱的锚** ⇒ 下一轮那些用例会因为「锚里没有它」而**跳过锚校验**
    #    ⇒ ⇒ 等于把「回归检测」悄悄关掉 ⇒ ⇒ **宁可不冻结**（保持旧锚不动）⇒ 报错退出。
    if args.freeze and invalid_n:
        sys.stderr.write(
            f"!! 拒绝冻结：{invalid_n} 条用例的 oracle 侧读数无效（见明细）"
            f"⇒ 冻结出来的锚会**缺条目** ⇒ 下一轮那些用例会跳过锚校验\n"
            f"   ⇒ 请在 oracle 稳定时重跑 FREEZE=1\n")
        return 3
    if args.freeze:
        with open(args.freeze, "w", encoding="utf-8") as fh:
            fh.write("\n".join(out_expected) + "\n")
    if args.freeze_known:
        with open(args.freeze_known, "w", encoding="utf-8") as fh:
            fh.write("\n".join(out_known) + "\n")

    total = len(cases)
    print(f"用例 {total} 条 · B↔C 逐字节一致 {total - known_n - failed - invalid_n} 条 · "
          f"已登记的表达层差异 {known_n} 条 · 真差异 {failed} 条 · 不可判 {invalid_n} 条")
    if invalid_n:
        print(f"  ⚠️ 有 {invalid_n} 条**oracle 侧没给出业务应答** ⇒ 这些条本轮不可判 "
              f"⇒ **不是「C 侧有差异」** ⇒ 结论不可信 ⇒ 需重跑（EXIT=3）")
    if failed:
        for idx, line in enumerate(lines):
            if "=> DIFF" in line and ("不一致" in line or "漂移" in line
                                      or "缺登记形状" in line or "形状变了" in line
                                      or "无业务应答" in line):
                print("\n".join(lines[max(0, idx - 3):idx + 1]))
                bh = lines[idx - 2].split("B: ", 1)[-1]
                ch = lines[idx - 1].split("C: ", 1)[-1]
                if bh != ch:
                    print("  --- 读数 diff（B → C）---")
                    for d in difflib.unified_diff(
                            [bh], [ch], fromfile="B", tofile="C", lineterm=""):
                        print("  " + d)
                print()
    # 🔴 **退出码三档**（与 http_probe.py 同款）：真差异（1）优先于不可判（3）。
    if failed:
        return 1
    if invalid_n:
        return 3
    return 0


def main() -> int:
    if len(sys.argv) < 2:
        sys.stderr.write(__doc__ or "")
        return 2
    sub = sys.argv[1]
    if sub == "build":
        return cmd_build(sys.argv[2:])
    if sub == "probe":
        return cmd_probe(sys.argv[2:])
    if sub == "compare":
        return cmd_compare(sys.argv[2:])
    sys.stderr.write(f"unknown subcommand: {sub}\n")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
