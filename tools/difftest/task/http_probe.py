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

判定口径（**四档**，任一档不满足即 EXIT=1）：
    · **普通用例**：B 与 C 的读数**必须逐字节相同**；
      且（若给了 `--expected`）C 与**冻结锚**（B 侧读数）也必须逐字节相同 ⇒ oracle 漂移会被抓到。
    · **已知差异用例**（登记在 `--known-cases`）：B 与 C **允许**不同，但
      ① C 的读数必须**逐字节等于** `--known-readings` 里登记的形状（⇒ 差异形状变了 ⇒ 失败）；
      ② B 的读数必须逐字节等于冻结锚（⇒ B 侧漂移会被抓到）。
      ⇒ **白名单不是「豁免」** —— 它把「允许不同」钉成「只允许差这一处，且必须还是当初那一处」。
    · **无效读数档**（2026-11-10 补）：**oracle 侧（B）**本轮没给出业务应答时，
      本条**不可判** ⇒ 记 `INVALID` ⇒ **不计入一致数、也不计真差异** ⇒
      整轮结论是「环境不稳 ⇒ 需重跑」（EXIT=3），**不是**「C 侧有差异」。
      ⇒ 详见 {@link invalid_reason}。

退出码：
    0 = 全部可判用例逐字节一致
    1 = 有真差异（B↔C 逐字节不同 / 与冻结锚不同 / 已登记形状变了）
    3 = **有不可判用例**（oracle 侧至少一条没给出业务应答）⇒ 结论不可信 ⇒ 需重跑
    2 = 探针本身跑不起来（读数缺失 / 用例表非法）

🔴 **为什么必须单独有「无效读数」这一档**（2026-11-10 实测出来的坑）：
    B 侧（oracle）是 **task-api → task-rpc 的真实跨进程 gRPC 调用**
    ⇒ B 侧**自己**有内部 hop 超时（B 侧 `observability.rpc-timeout: 2s`，
    见 `service/task/task-rpc/src/main/resources/application.yml:317`；
    `DEADLINE_EXCEEDED ⇒ 504` 的映射见
    `service/task/task-api/src/main/java/com/rag/task/task/api/advice/GlobalExceptionHandler.java:130-131`）。
    ⇒ 机器一忙，B 侧就会自己吐 **HTTP 504**，而同刻 C 侧（单进程直调，**没有这一跳**）返回 200
    ⇒ ⇒ **若不识别这一档，oracle 自己的失败会被记成「C 侧与 B 侧不一致」⇒ 冤枉 C 侧**。
    ⇒ ⇒ 本档的判据必须是「**这一侧有没有给出业务应答**」，而不是「两个读数是否相等」。

🔴 **为什么判据只认「状态码 ≥ 500 / 连不上」，不认 body 文案**：
    实测到的那个 504 body 文案（B 侧内部 hop 超时那一版）**在 B 仓源码树里搜不到**
    ⇒ ⇒ 它**没有 `文件:行号` 可钉** ⇒ ⇒ **不能把它写成判据**（写进去就是「拿无法复核的
    字符串当规则」⇒ 下次 B 侧换了文案就静默失效）。
    ⇒ ⇒ 判据只用**状态码语义**（RFC 9110：5xx = 服务端没能完成请求）⇒ 稳定、可复核。

🔴 **为什么「C 侧 5xx」仍然是真差异、而「B 侧 5xx」是不可判**（两侧角色不同）：
    · **C 侧 5xx 而 B 侧正常** ⇒ 基准存在（B 侧给了一个业务应答）⇒
      「B=200 / C=500」**本身就是一个可观测的行为差异**（不管根因是 bug 还是环境）
      ⇒ ⇒ 记 `DIFF`（C 侧是**受测对象**，它挂了就该被记下来）。
    · **B 侧 5xx 而 C 侧正常** ⇒ **没有基准**（不知道 B 正常时会答什么）⇒
      ⇒ 本条**不可判** ⇒ 记 `INVALID` ⇒ 但**仍然**拿 C 去比冻结锚
      （冻结锚 = B 侧历史读数 ⇒ 与「oracle 正常时的行为」比）⇒
      ⇒ **C 侧若与锚不符 ⇒ 仍是真差异**（不会被「不可判」吃掉）。
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
    except socket.timeout:
        # 🔴 **读超时 ⇒ 这一侧没给出业务应答** ⇒ 记成「无效读数」，**不是**崩探针。
        #    ⚠️ 之前这里直接抛异常 ⇒ 整个 compare 崩掉（退出码 1）⇒
        #    ⇒ 「oracle 卡住」被误报成「真差异」⇒ 与本档的立意正好相反。
        return {
            "status_line": "<READ-TIMEOUT>",
            "headers": {h: "" for h in COMPARED_HEADERS},
            "body": b"",
            "error": "read timeout",
        }
    except OSError as exc:
        return {
            "status_line": f"<IO-ERROR:{exc.errno}>",
            "headers": {h: "" for h in COMPARED_HEADERS},
            "body": b"",
            "error": str(exc),
        }
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


def invalid_reason(reading: dict) -> str | None:
    """这一侧**有没有给出业务应答**？⇒ 返回「无效原因」；`None` = 有效读数。

    🔴 **判据只看状态码语义，不看 body 文案**（理由见模块 docstring）：
        · **连不上 / 没响应** ⇒ 根本没问到 ⇒ 无效；
        · **5xx**（RFC 9110：服务端没能完成请求）⇒ **不是一次业务应答** ⇒ 无效；
        · 其余（2xx / 3xx / 4xx）⇒ 都是**业务应答**（本域的错误路径也是 400 + text/plain，
          见 B 侧 `GlobalExceptionHandler`）⇒ 有效。

    ⚠️ **4xx 一律有效**：本域的失败路径**故意**用 400/404/405 表达
       （B 侧 `GlobalExceptionHandler.handleGrpcStatus` 走 `httpStatusOf` 的 4xx 映射），
       ⇒ 若把 4xx 也算「无效」，**所有错误用例都会被降级成不可判** ⇒ 判据直接失效。
    """
    status_line = reading["status_line"]
    if status_line.startswith("<"):
        # `<CONNECT-FAILED:...>` / `<NO-RESPONSE>` / `<READ-TIMEOUT>` / `<IO-ERROR:...>`
        return status_line
    parts = status_line.split(" ")
    if len(parts) < 2 or not parts[1].isdigit():
        return f"<UNPARSEABLE-STATUS:{status_line}>"
    code = int(parts[1])
    if code >= 500:
        return f"HTTP {code}"
    return None


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
    invalid_n = 0     # **不可判**（oracle 侧本轮没给出业务应答 ⇒ 不计入任何一侧）

    for name, method, path_, ctype, body in cases:
        b = probe(args.b_port, method, path_, ctype, body)
        c = probe(args.c_port, method, path_, ctype, body)
        rb, rc = render(b), render(c)
        b_inv = invalid_reason(b)
        c_inv = invalid_reason(c)
        # 🔴 **冻结时只收「oracle 侧有业务应答」的读数** —— 把 5xx 冻进锚里
        #    ⇒ 锚就变成「一条 5xx」⇒ 下一轮所有 C 侧 200 都会被误判成「与冻结锚不一致」
        #    ⇒ ⇒ **冻结一个无效读数 = 毒化锚** ⇒ 这里直接跳过（并计数）。
        if b_inv is None:
            out_expected.append(f"{name}\t{rb}")
        if name in known_reasons:
            out_known.append(f"{name}\t{rc}")

        same = rb == rc
        notes: list[str] = []
        verdict = "SAME" if same else "DIFF"

        # ── 🔴 「oracle 无效读数」档（2026-11-10 补）─────────────────────────
        # B 侧没给出业务应答 ⇒ **没有基准** ⇒ 本条不可判 ⇒ 不计入 SAME / 真差异；
        # 但 **C 侧仍要与冻结锚比**（锚 = B 侧历史读数 = 「oracle 正常时的行为」）
        # ⇒ ⇒ C 侧若与锚不符 ⇒ **仍是真差异**（不会被「不可判」吃掉）。
        if b_inv is not None:
            invalid_n += 1
            verdict = "INVALID"
            notes.append(f"**oracle 侧无业务应答**（{b_inv}）⇒ 本条不可判")
            # 🔴 **「不可判」只免 B↔C 那一对，不免 C 侧的独立判据**：
            #    · **普通用例** ⇒ C 的基准是**冻结锚**（= oracle 的历史读数）；
            #    · **已登记差异用例** ⇒ C 的基准是**登记形状**（`known_readings`），
            #      **不是**冻结锚（⇒ C 本来就与锚不同 ⇒ 拿锚判它 = **必然假红**）。
            #    ⇒ ⇒ 两类用例必须用**各自的基准**，否则这条分支会误报。
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
            # C 侧没给出业务应答、而 B 侧给了 ⇒ **基准存在** ⇒
            # 「B 有应答 / C 没有」本身就是可观测的行为差异 ⇒ 记真差异。
            failed += 1
            verdict = "DIFF"
            notes.append(f"**C 侧无业务应答**（{c_inv}）而 oracle 侧有 ⇒ 行为差异")
        elif name in known_reasons:
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
        lines.append(f"  => {verdict}"
                     + ("  [" + " ｜ ".join(notes) + "]" if notes else ""))

    report = "\n".join(lines) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(report)
    # 🔴 **冻结时若存在「不可判」条目 ⇒ 拒绝写锚**（2026-11-10 补）：
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
    # 🔴 **退出码三档**（2026-11-10 补）：真差异（1）优先于不可判（3）——
    #    真差异是「C 侧确实与 oracle 不一致」，不可判是「oracle 自己没答上来」⇒
    #    两者都要报，但**不能把不可判记成差异**（那是冤枉 C 侧）。
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
    if sub == "probe":
        return cmd_probe(sys.argv[2:])
    if sub == "compare":
        return cmd_compare(sys.argv[2:])
    sys.stderr.write(f"unknown subcommand: {sub}\n")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
