#!/usr/bin/env python3
"""task 域双侧活体对拍 —— **裸 socket** 的 HTTP 字节探针。

为什么不用 curl：
    curl 的 ``-w '%{http_code}'`` 只给**状态码**，不给**状态行**（reason phrase）。
    而本域要逐字节比的就是状态行本身（Tomcat 10.1 与 11 的 reason phrase 不同）。
    ⇒ 这里直接开 socket 手写请求、自己读原始响应 ⇒ 状态行 / Content-Type / body 全部逐字节可见。

被比对的面（**刻意排除**易变头）：
    · **状态行**（含 HTTP 版本与 reason phrase）
    · **Content-Type**
    · **X-Content-Type-Options**（go-zero 的 ``http.Error`` 会带 ``nosniff``）
    · **Allow**（405 时的方法清单）
    · **body 的原始字节**（hex）
    ⇒ ``Date`` / ``Server`` / ``Content-Length`` / ``Connection`` **不比**：
      前两者每次都变；后两者要么由 body 长度决定（body 已比）、
      要么由连接复用策略决定（两侧框架版本不同 ⇒ 必然不同 ⇒ 比它是假红）。

子命令：
    probe    单发一次，打印一行读数
             usage: http_probe.py probe <port> <method> <path> [content-type] [body]
    compare  按用例表跑完两侧并逐条比对
             usage: http_probe.py compare --b-port P --c-port Q --cases cases.txt
                                       [--freeze expected.txt] [--expected expected.txt]
                                       [--known-cases known-cases.txt]
                                       [--known-readings known-readings.txt]
                                       [--out report.txt]

判定口径（**三档**，任一档不满足即 EXIT=1）：
    · **普通用例**：B 与 C 的读数**必须逐字节相同**；
      且（若给了 `--expected`）C 与**冻结锚**（B 侧读数）也必须逐字节相同 ⇒ oracle 漂移会被抓到。
    · **已知差异用例**（登记在 `--known-cases`）：B 与 C **允许**不同，但
      ① C 的读数必须**逐字节等于** `--known-readings` 里登记的形状（⇒ 差异形状变了 ⇒ 失败）；
      ② B 的读数必须逐字节等于冻结锚（⇒ B 侧漂移会被抓到）。
      ⇒ **白名单不是「豁免」** —— 它把「允许不同」钉成「只允许差这一处，且必须还是当初那一处」。
"""

from __future__ import annotations

import argparse
import difflib
import os
import socket
import sys

# ── 被比对的头（顺序固定 ⇒ 输出可比）──────────────────────────────────────────
COMPARED_HEADERS = ("content-type", "x-content-type-options", "allow")


def _read_all(sock: socket.socket) -> bytes:
    chunks = []
    while True:
        data = sock.recv(65536)
        if not data:
            break
        chunks.append(data)
    return b"".join(chunks)


def _decode_chunked(raw: bytes) -> bytes:
    """极简 chunked 解码（只处理 chunk-size + CRLF + data + CRLF，末尾 0 块）。"""
    out = bytearray()
    pos = 0
    while pos < len(raw):
        eol = raw.find(b"\r\n", pos)
        if eol < 0:
            break
        size_line = raw[pos:eol].split(b";", 1)[0].strip()
        try:
            size = int(size_line, 16)
        except ValueError:
            return raw
        pos = eol + 2
        if size == 0:
            break
        out += raw[pos:pos + size]
        pos += size + 2
    return bytes(out)


def _parse_final_response(raw: bytes) -> tuple[str, dict[str, str], bytes]:
    """解析**最终**响应（跳过所有 1xx informational 响应）。

    🔴 **为什么必须跳过 1xx**：本仓共享层的 ``common/observability/ClientDisconnectProbe``
    会**直接往 socket 写** ``HTTP/1.1 103 Early Hints\\r\\n\\r\\n``（ASCII · 无 body）
    作为「客户端是否还在」的断连探针 ⇒ **每个响应前面可能有 1~N 行 1xx**
    （行数取决于 socket 写时机 ⇒ **两侧不必相同** ⇒ 不可比）。
    ⇒ 按 RFC 9110，客户端**必须忽略 1xx** ⇒ 探针只取最终响应。

    ⇒ 也正因如此：**「1xx 行数」不属于比对面**（易变），
    而 **103 的存在本身两侧都有**（同一份共享层）⇒ 机制面一致。
    """
    offset = 0
    while True:
        head, sep, rest = raw[offset:].partition(b"\r\n\r\n")
        if not sep:
            return "<NO-RESPONSE>", {h: "" for h in COMPARED_HEADERS}, raw[offset:]
        lines = head.split(b"\r\n")
        status_line = lines[0].decode("latin-1")
        parts = status_line.split(" ")
        code = parts[1] if len(parts) > 1 else ""
        if len(code) == 3 and code[0] == "1":
            # 1xx ⇒ informational ⇒ 跳过，继续解析下一个响应
            offset += len(head) + 4
            continue
        headers = {h: "" for h in COMPARED_HEADERS}
        chunked = False
        content_length: int | None = None
        for line in lines[1:]:
            lowered = line.lower()
            if lowered.startswith(b"transfer-encoding:") and b"chunked" in lowered:
                chunked = True
            if lowered.startswith(b"content-length:"):
                try:
                    content_length = int(line.split(b":", 1)[1].strip())
                except ValueError:
                    content_length = None
            for name in COMPARED_HEADERS:
                if lowered.startswith(name.encode() + b":"):
                    headers[name] = line.split(b":", 1)[1].decode("latin-1").strip()
        if chunked:
            payload = _decode_chunked(rest)
        elif content_length is not None:
            payload = rest[:content_length]
        else:
            payload = rest
        return status_line, headers, payload


def probe(port: int, method: str, path: str, content_type: str | None, body: str | None) -> dict:
    """发一次请求 ⇒ 返回可逐字段比对的读数字典。"""
    req = f"{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
    if body is not None:
        payload = body.encode("utf-8")
        if content_type is not None:
            req += f"Content-Type: {content_type}\r\n"
        req += f"Content-Length: {len(payload)}\r\n"
    req += "Connection: close\r\n\r\n"
    if body is not None:
        req += body

    try:
        sock = socket.create_connection(("127.0.0.1", port), timeout=30)
    except OSError as exc:
        return {
            "status_line": f"<CONNECT-FAILED:{exc.errno}>",
            "headers": {h: "" for h in COMPARED_HEADERS},
            "body": b"",
            "error": str(exc),
        }
    try:
        sock.sendall(req.encode("utf-8"))
        raw = _read_all(sock)
    finally:
        sock.close()

    status_line, headers, payload = _parse_final_response(raw)
    return {"status_line": status_line, "headers": headers, "body": payload, "error": ""}


def render(reading: dict) -> str:
    """把读数渲染成**单行**（便于 expected.txt 逐行 diff）。"""
    parts = ["HTTP", reading["status_line"]]
    for name in COMPARED_HEADERS:
        parts.append(reading["headers"][name])
    parts.append(reading["body"].hex())
    return "|".join(parts)


def load_cases(path: str) -> list[tuple[str, str, str, str | None, str | None]]:
    """读用例表 ⇒ [(名称, method, path, content_type, body)]。"""
    cases: list[tuple[str, str, str, str | None, str | None]] = []
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, start=1):
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) != 5:
                raise SystemExit(f"{path}:{lineno} 期望 5 个制表符分隔字段，实得 {len(fields)}")
            name, method, path_, ctype, body = fields
            cases.append((
                name,
                method,
                path_,
                None if ctype == "-" else ctype,
                None if body == "@none" else body,
            ))
    return cases


def cmd_probe(argv: list[str]) -> int:
    if len(argv) < 3 or len(argv) > 5:
        sys.stderr.write("usage: http_probe.py probe <port> <method> <path> [content-type] [body]\n")
        return 2
    port = int(argv[0])
    method = argv[1]
    path = argv[2]
    ctype = argv[3] if len(argv) > 3 else None
    body = argv[4] if len(argv) > 4 else None
    if ctype == "-":
        ctype = None
    if body == "@none":
        body = None
    print(render(probe(port, method, path, ctype, body)))
    return 0


def _read_frozen(path: str | None) -> dict[str, str]:
    """读「用例名 → 读数」的冻结文件。"""
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


def cmd_compare(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--b-port", type=int, required=True)
    ap.add_argument("--c-port", type=int, required=True)
    ap.add_argument("--cases", required=True)
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

    lines: list[str] = []
    out_expected: list[str] = []
    out_known: list[str] = []
    failed = 0        # **真差异**：B 与 C 不一致，或与冻结锚/登记形状不一致
    known_n = 0       # **已登记的表达层差异**（计数，不算失败）

    for name, method, path_, ctype, body in cases:
        b = probe(args.b_port, method, path_, ctype, body)
        c = probe(args.c_port, method, path_, ctype, body)
        rb, rc = render(b), render(c)
        out_expected.append(f"{name}\t{rb}")
        if name in known_reasons:
            out_known.append(f"{name}\t{rc}")

        same = rb == rc
        notes: list[str] = []
        if name in known_reasons:
            # 已知差异：形状必须与登记时**逐字节一致**
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
        lines.append(f"  => {'SAME' if same else 'DIFF'}"
                     + ("  [" + " ｜ ".join(notes) + "]" if notes else ""))

    report = "\n".join(lines) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(report)
    if args.freeze:
        with open(args.freeze, "w", encoding="utf-8") as fh:
            fh.write("\n".join(out_expected) + "\n")
    if args.freeze_known:
        with open(args.freeze_known, "w", encoding="utf-8") as fh:
            fh.write("\n".join(out_known) + "\n")

    total = len(cases)
    print(f"用例 {total} 条 · B↔C 逐字节一致 {total - known_n - failed} 条 · "
          f"已登记的表达层差异 {known_n} 条 · 真差异 {failed} 条")
    if failed:
        for idx, line in enumerate(lines):
            if "=> DIFF" in line and ("不一致" in line or "漂移" in line
                                      or "缺登记形状" in line or "形状变了" in line):
                print("\n".join(lines[max(0, idx - 3):idx + 1]))
                bh = lines[idx - 2].split("|")[-1]
                ch = lines[idx - 1].split("|")[-1]
                if bh != ch:
                    b_txt = bytes.fromhex(bh).decode("utf-8", "replace")
                    c_txt = bytes.fromhex(ch).decode("utf-8", "replace")
                    print("  --- body 明文 diff（B → C）---")
                    for d in difflib.unified_diff(
                            [b_txt], [c_txt], fromfile="B", tofile="C", lineterm=""):
                        print("  " + d)
                print()
    return 1 if failed else 0


def main() -> int:
    if len(sys.argv) < 2:
        sys.stderr.write(__doc__ or "")
        return 2
    sub = sys.argv[1]
    if sub == "probe":
        return cmd_probe(sys.argv[2:])
    if sub == "compare":
        return cmd_compare(sys.argv[2:])
    sys.stderr.write(f"unknown subcommand: {sub}\n")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
