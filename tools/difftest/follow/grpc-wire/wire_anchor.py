#!/usr/bin/env python3
"""gRPC wire 层的**第三方锚** —— 把 wire 语义对到 Go 真源冻结字节上。

🔴 **为什么需要这个（销 §11.6 的 NOT_VERIFIED ①）**
────────────────────────────────────────────────────────────
历史读数：wire 层只做到「**双边一致**」（C == B）⇒ **没有第三方锚**
⇒ 那只证明「两侧一样」，**不证明「两侧都对」**。

⚠️ **Go 真源（B 侧的上游）已被删除**（B 仓现在只剩 `tools/**` 里的探针 `.go`）
⇒ ⇒ 「拿 Go 代码重新生成字节」这条路**不可得**。

✅ **本工具的解法**：**不依赖任何一方的实现**，用**两个互相独立的第三方**判：

    ① **Go 真源冻结字节**（`expected.txt` · B 侧 2026-10-29 从 Go 侧录的 20 条 HTTP 用例）
       ⇒ 这是**真源级**的第三方锚（已经冻结在仓里，不依赖任何当前可运行的代码）。

    ② **protobuf 参考实现**（`protoc` 35.0 生成 Python 绑定 ⇒ `google.protobuf` 7.36.2）
       ⇒ 用来**独立解码** wire 原始字节 ⇒ 它**与两侧的 grpc-java 完全独立**
       ⇒ ⇒ 能抓住「两侧一起错」类缺陷（比双边一致更强）。

⇒ ⇒ 判据 = **wire 解出的语义** 必须与 **①的 HTTP 语义**逐条对齐，
          且 **②的规范重编码** 必须与原始字节一致（编码方式符合契约）。

🔴 **效力边界（诚实声明）**
────────────────────────────────────────────────────────────
① 本锚验的是「**wire 层承载的业务语义 == Go 真源冻结语义**」
   ⇒ 覆盖 code / msg / user_ids / users(target_id+mutual_score) 全字段。
② 但它**不验字节序列的逐位相等**（那是 C==B 双边比对 + HTTP 层 21/21 三方负责）
   ⇒ ⇒ **两件事互补，不是替代**。
③ 少数 wire 用例在 `expected.txt` 里**没有同名 HTTP 用例**
   （`wire_follow_list_user_missing` / `wire_block`）⇒ 这两条只能做
   **C vs B 语义一致 + 编码规范性**两项判定，**不挂 Go 锚**
   ⇒ 台账里**必须如实标注**，不得把它们算作「三方一致」。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
# ⚠️ 仓库根 = tools/difftest/follow/grpc-wire/ ⇒ **上溯 4 层**（不是 3 层）
REPO_ROOT = HERE.parent.parent.parent.parent
DEFAULT_PROTO = REPO_ROOT / "proto" / "src" / "main" / "proto" / "follow.proto"
DEFAULT_EXPECTED = HERE.parent / "expected.txt"

# ── wire 用例 ↔ 响应类型 ↔ expected.txt 里的 Go 锚用例 ──────────────────────────
# ⚠️ `go_anchor = None` ⇒ 该用例在 Go 冻结字节里**没有对应 HTTP 用例**
#    ⇒ 只能判「C vs B 语义一致 + 编码规范」，**不计入三方一致**。
CASE_SPEC = {
    "wire_follow_list":            ("UserListResp", "follow_list_seeded"),
    "wire_block_list":             ("UserListResp", "block_list_seeded"),
    "wire_follower_list":          ("UserListResp", "follower_list_seeded"),
    "wire_recommendations":        ("RecommendResp", "recommendations_seeded"),
    "wire_follow_list_offset_neg": ("UserListResp", "follow_list_offset_neg"),
    "wire_follow_list_limit_max":  ("UserListResp", "follow_list_limit_max"),
    "wire_follow_list_user_missing": ("UserListResp", None),   # ⚠️ 错误态（见 ERROR_CASES）
    "wire_follow_self":            ("BaseResp", None),         # ⚠️ 错误态（Go 锚是 HTTP 业务码，不可直比）
    "wire_follow_target_notfound": ("BaseResp", None),         # ⚠️ 错误态（同上）
    "wire_follow_new":             ("BaseResp", "follow_new_relation"),
    "wire_unfollow":               ("BaseResp", "unfollow_seeded"),
    "wire_block":                  ("BaseResp", None),         # ⚠️ 错误态（同上）
    "wire_unblock":                ("BaseResp", "unblock_seeded"),
}

# ⚠️ **错误态用例**（gRPC 层返回 status，**没有响应体** ⇒ 没有 wire 字节可解）
# 🔴 **为什么它们不能挂 Go 锚（这一点极易想错）**：
#    Go 锚（`expected.txt`）是 **HTTP 层**的响应（业务码 + 文案），
#    而 wire 层错误态给的是 **gRPC status**（`NOT_FOUND` / `INVALID_ARGUMENT` …）。
#    ⇒ **两者不是同一个码空间**，不可直接比对。
#    ⚠️ 而且 B 侧**自己**这两者就**不一致**（B 侧 `FollowGrpcError` 的映射表自陈：
#      `INVALID_ARGUMENT` → 5201 自关系在 B 侧映射成 **5003** ❌；
#      `NOT_FOUND` → 5101 用户不存在在 B 侧映射成 **5002** ❌）
#    ⇒ ⇒ **错误态只判「C == B 的 status 一致」**（wire 层契约由 proto 决定），
#        **不挂 Go 锚**，也**不判**业务码对应关系（那是 B 侧自己的既有漂移，不属本锚范围）。
ERROR_CASES = {
    "wire_follow_list_user_missing",
    "wire_follow_self",
    "wire_follow_target_notfound",
    "wire_block",
}


def die(msg: str) -> "None":
    print(f"❌ {msg}", file=sys.stderr)
    sys.exit(2)


def load_pb2(proto_path: Path, work_dir: Path):
    """用 protoc 生成 Python 绑定 ⇒ **第三方实现**（不碰任何一方的 pb 生成码）。"""
    if not proto_path.is_file():
        die(f"找不到契约文件：{proto_path}")
    out = work_dir / "pb"
    out.mkdir(parents=True, exist_ok=True)
    cmd = [
        "protoc",
        f"--proto_path={proto_path.parent}",
        f"--python_out={out}",
        str(proto_path),
    ]
    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True)
    except FileNotFoundError:
        die("找不到 `protoc`（第三方锚需要 protobuf 参考实现）")
    except subprocess.CalledProcessError as e:
        die(f"protoc 生成失败：{e.stderr.strip()[:400]}")
    gen = out / (proto_path.stem + "_pb2.py")
    if not gen.is_file():
        die(f"protoc 没产出 {gen}")
    sys.path.insert(0, str(out))
    try:
        mod = __import__(proto_path.stem + "_pb2")
    except Exception as e:  # noqa: BLE001
        die(f"导入生成的绑定失败：{e}")
    return mod


def parse_probe_output(path: Path) -> list[tuple[str, str, str]]:
    """解析 `CASE|<name>|<status>|<hex>` 行 ⇒ [(name, status, hex)]。"""
    rows: list[tuple[str, str, str]] = []
    if not path.is_file():
        die(f"找不到探针输出：{path}")
    for raw in path.read_text(encoding="utf-8").splitlines():
        raw = raw.strip()
        if not raw.startswith("CASE|"):
            continue
        parts = raw.split("|")
        if len(parts) != 4:
            die(f"探针输出行形状异常（应为 4 段）：{raw[:120]}")
        rows.append((parts[1], parts[2], parts[3]))
    return rows


def parse_expected(path: Path) -> dict[str, tuple[int, str]]:
    """解析 `expected.txt` ⇒ {用例名: (http 状态码, body)}；body 为空表示空 body。"""
    out: dict[str, tuple[int, str]] = {}
    if not path.is_file():
        die(f"找不到 Go 真源冻结字节：{path}")
    for raw in path.read_text(encoding="utf-8").splitlines():
        raw = raw.strip()
        if not raw or not raw.startswith("CASE|"):
            continue
        parts = raw.split("|")
        if len(parts) != 4:
            die(f"expected.txt 行形状异常（应为 4 段）：{raw[:120]}")
        name, http_code, body_hex = parts[1], parts[2], parts[3]
        body = ""
        if body_hex:
            try:
                body = bytes.fromhex(body_hex).decode("utf-8")
            except ValueError:
                die(f"expected.txt 的 {name} body 不是合法 hex")
        out[name] = (int(http_code), body)
    return out


def summarize(msg) -> dict:
    """把 protobuf 消息压成**可比对的语义字典**（字段号 → 值）。"""
    fields: dict = {}
    for field, value in msg.ListFields():
        # ⚠️ **protobuf 7.x 的 upb 实现没有 `FieldDescriptor.label`** ⇒ 用 `is_repeated`
        if getattr(field, "is_repeated", False):
            if field.message_type is not None:
                fields[field.number] = [summarize(v) for v in value]
            else:
                fields[field.number] = list(value)
        elif field.message_type is not None:
            fields[field.number] = summarize(value)
        else:
            fields[field.number] = value
    return fields


def go_anchor_semantics(body: str) -> dict:
    """从 Go 冻结的 HTTP body（JSON 信封）抽出**与 wire 同构**的语义。

    ⚠️ **字段映射**（wire 消息 ↔ HTTP 信封）：
      · `code`（1）   ↔ 信封 `code`
      · `msg`（2）    ↔ 信封 `msg`
      · `user_ids`（3）↔ 信封 `data.user_ids`（**null ⇒ 空数组** ⇒ wire 侧不编码该字段）
      · `users`（3）  ↔ 信封 `data.users[]`（`target_id` 是**字符串** ⇒ 须转 int）
    """
    if not body:
        return {}
    try:
        env = json.loads(body)
    except json.JSONDecodeError as e:
        die(f"Go 锚 body 不是合法 JSON：{e}")
    sem: dict = {}
    if "code" in env:
        sem[1] = env["code"]
    if "msg" in env:
        sem[2] = env["msg"]
    data = env.get("data")
    if isinstance(data, dict):
        if data.get("user_ids") is not None:
            sem[3] = [int(x) for x in data["user_ids"]]
        if data.get("users") is not None:
            # ⚠️ **嵌套消息的字段号也必须是 int 键**（与 `summarize()` 的形状一致）
            #    ⇒ 否则 wire 侧 {1:..} 与 Go 侧 {'1':..} 比不上（自证时被这一条抓到）
            sem[3] = [
                {1: int(u["target_id"]), 2: int(u["mutual_score"])}
                for u in data["users"]
            ]
    return sem


def main() -> int:
    ap = argparse.ArgumentParser(description="gRPC wire 层第三方锚（Go 真源冻结字节 + protobuf 参考实现）")
    ap.add_argument("--c-output", required=True, help="C 侧 wire 探针输出")
    ap.add_argument("--b-output", required=True, help="B 侧 wire 探针输出")
    ap.add_argument("--proto", default=str(DEFAULT_PROTO), help="契约 proto 文件")
    ap.add_argument("--expected", default=str(DEFAULT_EXPECTED), help="Go 真源冻结字节（expected.txt）")
    args = ap.parse_args()

    proto_path = Path(args.proto).resolve()
    c_path = Path(args.c_output).resolve()
    b_path = Path(args.b_output).resolve()
    expected_path = Path(args.expected).resolve()

    with tempfile.TemporaryDirectory(prefix="wire-anchor-") as tmp:
        pb2 = load_pb2(proto_path, Path(tmp))

        c_rows = parse_probe_output(c_path)
        b_rows = parse_probe_output(b_path)
        expected = parse_expected(expected_path)

        # ── ① 用例集双向对账（防「悄悄少验几条」）──────────────────────────────
        c_names = [r[0] for r in c_rows]
        b_names = [r[0] for r in b_rows]
        if sorted(c_names) != sorted(b_names):
            die(f"两侧用例集不同：\n  C: {sorted(c_names)}\n  B: {sorted(b_names)}")
        if sorted(c_names) != sorted(CASE_SPEC):
            missing = set(CASE_SPEC) - set(c_names)
            extra = set(c_names) - set(CASE_SPEC)
            die(f"用例集与锚点表不匹配（缺 {sorted(missing)} · 多 {sorted(extra)}）")

        print(f"ℹ️  契约 = {proto_path}")
        print(f"ℹ️  第三方实现 = protoc + google.protobuf（与两侧 grpc-java 独立）")
        print(f"ℹ️  Go 真源锚 = {expected_path}")
        print(f"ℹ️  用例数 = {len(c_rows)}（C/B 双向对账通过）")
        print("")

        failed = 0
        anchored = 0        # 真正挂上 Go 锚的条数
        unanchored = 0      # 没有 Go 锚（Go 侧无同名 HTTP 用例）
        for name, c_status, c_hex in c_rows:
            b_status = next(s for n, s, _ in b_rows if n == name)
            b_hex = next(h for n, _, h in b_rows if n == name)

            # 错误态：没有响应体 ⇒ 无 wire 字节可解
            if name in ERROR_CASES or c_status.startswith("ERROR_"):
                if c_status != b_status:
                    failed += 1
                    print(f"❌ {name}  gRPC status 不一致：C={c_status} · B={b_status}")
                else:
                    print(f"✅ {name}  gRPC status 一致（{c_status}）· 无响应体 ⇒ 锚不适用")
                continue

            if c_status != "OK":
                die(f"{name} 的 C 侧状态既不是 OK 也不在 ERROR_CASES 里：{c_status}")

            type_name, go_case = CASE_SPEC[name]
            cls = getattr(pb2, type_name, None)
            if cls is None:
                die(f"契约里没有消息类型 {type_name}")

            c_raw = bytes.fromhex(c_hex)
            b_raw = bytes.fromhex(b_hex)

            c_msg = cls()
            b_msg = cls()
            try:
                c_msg.ParseFromString(c_raw)
                b_msg.ParseFromString(b_raw)
            except Exception as e:  # noqa: BLE001
                failed += 1
                print(f"❌ {name}  **第三方解码失败**：{e}")
                continue

            c_sem = summarize(c_msg)
            b_sem = summarize(b_msg)

            # ── ② C vs B 语义一致 ────────────────────────────────────────────
            if c_sem != b_sem:
                failed += 1
                print(f"❌ {name}  第三方解码后语义不一致：\n     C: {c_sem}\n     B: {b_sem}")
                continue

            # ── ③ 编码规范性：第三方规范重编码 == 原始字节 ─────────────────────
            #    ⚠️ 抓的是「两侧一致但都不是契约规范形态」（字段顺序乱 / 非最小 varint）
            c_canon = c_msg.SerializeToString()
            b_canon = b_msg.SerializeToString()
            if c_canon != c_raw or b_canon != b_raw:
                failed += 1
                print(f"❌ {name}  **非规范编码** ⇒ 原始字节与第三方规范重编码不同"
                      f"（C={'同' if c_canon == c_raw else '异'} · B={'同' if b_canon == b_raw else '异'}）")
                continue

            # ── ④ Go 真源锚对齐 ───────────────────────────────────────────────
            if go_case is None:
                unanchored += 1
                print(f"✅ {name}  {type_name:16s} C == B · 编码规范 · {len(c_raw)} 字节"
                      f" · ⚠️ **Go 锚无同名 HTTP 用例 ⇒ 仅双边+规范性**")
                print(f"      语义：{c_sem}")
                continue

            if go_case not in expected:
                failed += 1
                print(f"❌ {name}  引用的 Go 锚用例 `{go_case}` 不在 expected.txt 里")
                continue

            _, go_body = expected[go_case]
            go_sem = go_anchor_semantics(go_body)
            if c_sem != go_sem:
                failed += 1
                print(f"❌ {name}  **与 Go 真源冻结语义不一致**（锚 `{go_case}`）：\n"
                      f"     wire: {c_sem}\n     Go  : {go_sem}")
                continue

            anchored += 1
            print(f"✅ {name}  {type_name:16s} **三方一致**（C == B == Go 锚 `{go_case}`）"
                  f" · 编码规范 · {len(c_raw)} 字节")
            print(f"      语义：{c_sem}")

        print("")
        if anchored == 0:
            die("零条挂上 Go 锚 ⇒ 锚空跑（不得据此说「已闭合」）")

        print(f"{'PASS' if failed == 0 else 'FAIL'}  第三方锚："
              f"三方一致 {anchored} 条 · 仅双边+规范性 {unanchored} 条 · 失败 {failed} 条")
        if failed:
            return 1
        print(f"✅ wire 层达成 **三方一致**（{anchored}/{len(c_rows)} 条挂上 Go 真源冻结锚）"
              f"；其余 {unanchored} 条为 Go 侧无同名 HTTP 用例 ⇒ 仅双边+规范性")
        return 0


if __name__ == "__main__":
    sys.exit(main())
