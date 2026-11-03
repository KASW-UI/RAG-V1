#!/usr/bin/env python3
"""
user 域 **gRPC wire 层对拍**比对器 —— B 侧 ↔ C 侧。

输入：两侧 `UserWireProbe` 的输出文件（每行 `CASE|<用例名>|<状态>|<规范化响应>`，
另有 `SELFCHECK|<项>|<OK|FAIL>` 行）。

判据（三档，与 HTTP 维度同一套分档纪律）：
  · **一致**        → 两行的 `状态` 与 `规范化响应` **逐字相等**。
  · **🔶 已登记偏离** → 命中 `REGISTERED_DEVIATIONS` 里已签字的差异形态 ⇒ 单列、不计失败。
  · **❌ 真差异**     → 其余全部 ⇒ 计入失败、退出码非 0。

⚠️ **为什么规范化响应里还能有差异**：探针只屏蔽了「两侧必然不同的**值**」
   （雪花 uid / history_id / create_time），**presence 仍然参与比对**
   ⇒ 「B 有这个字段、C 没有」照样会被判成真差异。
⚠️ **为什么 SELFCHECK 单独判**：它是**同侧因果链自洽**断言
   （如「SelectAvatar 回显的 url == UploadAvatar 时的 url」）
   ⇒ 它保证「两侧 id 不同」这件事不至于让语义判定落空。
"""
import sys
from collections import OrderedDict

# 🔶 已签字的偏离（当前为空 —— gRPC 维度尚未登记任何偏离）。
#    形如：{用例名: (允许的 C 侧响应形态, 说明)}
REGISTERED_DEVIATIONS = {}

# 差异分档说明（登记台账时同步更新）
DRIFT_NOTE = "DRIFT-USER-PARITY-005（gRPC wire 维度 · 待登记）"


def parse(path):
    """解析探针输出 ⇒ (cases, selfchecks)。cases 用 OrderedDict 保序。"""
    cases = OrderedDict()
    checks = OrderedDict()
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line.strip():
                continue
            parts = line.split("|")
            if parts[0] == "CASE" and len(parts) >= 4:
                name = parts[1]
                status = parts[2]
                payload = "|".join(parts[3:])
                cases[name] = (status, payload)
            elif parts[0] == "SELFCHECK" and len(parts) >= 3:
                checks[parts[1]] = parts[2]
    return cases, checks


def main():
    if len(sys.argv) != 3:
        sys.stderr.write("usage: wire_compare.py <bFile> <cFile>\n")
        return 2
    b_cases, b_checks = parse(sys.argv[1])
    c_cases, c_checks = parse(sys.argv[2])

    if not b_cases or not c_cases:
        sys.stderr.write("!! 某一侧没有输出 CASE 行 ⇒ 探针没跑起来\n")
        return 2

    failed = 0
    registered = 0

    # ── 用例集合必须一致（否则有一侧少跑了 ⇒ 不能比）────────────────────────
    only_b = [k for k in b_cases if k not in c_cases]
    only_c = [k for k in c_cases if k not in b_cases]
    if only_b or only_c:
        failed += 1
        print("   ❌ 用例集合不一致")
        for k in only_b:
            print(f"      仅 B 有：{k}")
        for k in only_c:
            print(f"      仅 C 有：{k}")

    print(f"   gRPC wire 用例数：B={len(b_cases)} · C={len(c_cases)}")

    for name in b_cases:
        if name not in c_cases:
            continue
        b_status, b_payload = b_cases[name]
        c_status, c_payload = c_cases[name]
        if b_status == c_status and b_payload == c_payload:
            print(f"      ✅ {name}（{b_status}）")
            continue
        if name in REGISTERED_DEVIATIONS:
            registered += 1
            print(f"      🔶 {name}：已登记偏离 ⇒ 不计失败")
            print(f"         B: {b_status} | {b_payload}")
            print(f"         C: {c_status} | {c_payload}")
            continue
        failed += 1
        print(f"      ❌ {name}")
        print(f"         B: {b_status} | {b_payload}")
        print(f"         C: {c_status} | {c_payload}")

    # ── SELFCHECK：两侧都必须全 OK ────────────────────────────────────────
    if set(b_checks) != set(c_checks):
        failed += 1
        print("   ❌ SELFCHECK 项集合不一致")
        print(f"      B: {sorted(b_checks)}")
        print(f"      C: {sorted(c_checks)}")
    for name, verdict in b_checks.items():
        c_verdict = c_checks.get(name, "<缺失>")
        if verdict == "OK" and c_verdict == "OK":
            print(f"      ✅ SELFCHECK {name}")
        else:
            failed += 1
            print(f"      ❌ SELFCHECK {name}：B={verdict} · C={c_verdict}")

    print(f"   ⇒ ❌ 真差异 {failed} · 🔶 已登记偏离 {registered}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
