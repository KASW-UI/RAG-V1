#!/usr/bin/env python3
"""hot 域双侧活体对拍 —— **裸 socket** 的 HTTP 字节探针。

为什么不用 curl：
    curl 的 ``-w '%{http_code}'`` 只给**状态码**，不给**状态行**（reason phrase）。
    而本域的 F-09 关注的正是「404 的状态行长什么样」⇒ 必须拿到**原始状态行**。
    ⇒ 这里直接开 socket 手写请求、自己读原始响应 ⇒ 状态行 / Content-Type / body 全部逐字节可见。

输出（单行，``|`` 分隔）：
    HTTP|<原始状态行>|<Content-Type 头>|<body 的 hex>

    · 状态行**原样**保留（含 HTTP 版本与 reason phrase）⇒ Tomcat 10.1 与 11 的差异在这里显形；
    · body 若为 ``Transfer-Encoding: chunked`` ⇒ **先解块**再取 hex
      （否则比的是分块帧而不是业务字节 ⇒ 假红）；
    · ``Date`` / ``Server`` 等**易变头**刻意不比（见 README「比对口径」）。

⚠️ **另有一个 ``probe_framing``**（**传输分帧面**）：只吐状态行 + Transfer-Encoding +
Content-Length，**不动**上面这个 4 段输出格式 ⇒ ⇒ 既有判据（M1 面逐字节比对）不受影响。

⚠️ **1xx informational 会被跳过**（RFC 9110 §15.2）：本仓共享层的 ``ClientDisconnectProbe``
会**直接往 socket 写** ``HTTP/1.1 103 Early Hints`` ⇒ **慢请求**的响应流前面会带若干个 1xx 块
⇒ 必须**跳过所有 1xx、只取最终响应** ⇒ 否则整份读数错位（详见 ``_split_final_response`` 的注释）。
⇒ 解析器的自检见 ``http_probe_selftest.py``（每次探针运行都会被执行）。

用法：
    python3 http_probe.py <port> <method> <path> [body]
"""

import socket
import sys


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


def _status_code(status_line: str) -> int | None:
    """从状态行里取状态码（取不到就返回 None）。"""
    parts = status_line.split(" ")
    if len(parts) < 2:
        return None
    try:
        return int(parts[1])
    except ValueError:
        return None


def _split_final_response_headers(raw: bytes) -> tuple[str, dict[str, str], bytes]:
    """从原始响应字节里切出**最终响应**（跳过所有 1xx informational）⇒ (状态行, 头字典, body)。

    🔴 **为什么必须跳过 1xx（RFC 9110 §15.2）**：
        1xx（informational）**不终结**响应 ⇒ 后面还有真正的响应。
        而本仓共享层的 ``ClientDisconnectProbe`` 会**直接往 socket 写**
        ``HTTP/1.1 103 Early Hints\\r\\n\\r\\n`` 作断连探针
        ⇒ **慢请求**（本域的超时面就要 2~3s）的响应流**前面会带若干个 1xx 块**。

    ⚠️ **旧实现按第一个 ``\\r\\n\\r\\n`` 切分** ⇒ 遇到 1xx 前缀时**整份读数错位**
    （把 1xx 当成最终响应 ⇒ 状态行取到 "103 Early Hints"、body 变成后续全部字节）。
    ⇒ 那不是「偶发」：请求越慢越容易触发 ⇒ **超时面 100% 触发**。

    ⚠️ **头字典的键一律小写**（HTTP 头名大小写不敏感 ⇒ 不小写就没法比）。

    返回：(原始状态行, 头字典, body 字节)
    """
    rest = raw
    while True:
        head, sep, payload = rest.partition(b"\r\n\r\n")
        if not sep:
            # 连状态行都没拿到 ⇒ 连接层失败（探针据此判定「挂死」）
            return "<NO-RESPONSE>", {}, raw
        lines = head.split(b"\r\n")
        status_line = lines[0].decode("latin-1")
        code = _status_code(status_line)
        if code is not None and 100 <= code < 200:
            # 1xx informational ⇒ 丢弃本块，继续读下一段（**不终结响应**）
            rest = payload
            continue
        headers: dict[str, str] = {}
        for line in lines[1:]:
            if b":" not in line:
                continue
            name, value = line.split(b":", 1)
            headers[name.decode("latin-1").strip().lower()] = value.decode("latin-1").strip()
        if "chunked" in headers.get("transfer-encoding", "").lower():
            payload = _decode_chunked(payload)
        return status_line, headers, payload


def _split_final_response(raw: bytes) -> tuple[str, str, bytes]:
    """同 :func:`_split_final_response_headers`，但只吐 (状态行, Content-Type, body)。

    ⚠️ **这个 3 元组的形状是 M1 面的冻结读数基础** ⇒ **不许改签名、不许改字段含义**。
    """
    status_line, headers, payload = _split_final_response_headers(raw)
    return status_line, headers.get("content-type", ""), payload


def _send_raw(port: int, method: str, path: str, body: str | None) -> bytes:
    """发一次请求并读回**原始**响应字节（不解析）。

    ⚠️ **单一口径**：``probe`` 与 ``probe_framing`` 都走这里 ⇒
    ⇒ 不会出现「两份探针各写一遍 socket 逻辑 ⇒ 口径悄悄分叉」。
    """
    req = f"{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
    if body is not None:
        payload = body.encode("utf-8")
        req += ("Content-Type: application/json\r\n"
                f"Content-Length: {len(payload)}\r\n")
    req += "Connection: close\r\n\r\n"
    if body is not None:
        req += body

    sock = socket.create_connection(("127.0.0.1", port), timeout=30)
    try:
        sock.sendall(req.encode("utf-8"))
        return _read_all(sock)
    finally:
        sock.close()


def probe(port: int, method: str, path: str, body: str | None) -> str:
    raw = _send_raw(port, method, path, body)
    status_line, content_type, payload = _split_final_response(raw)
    return f"HTTP|{status_line}|{content_type}|{payload.hex()}"


def probe_framing(port: int, method: str, path: str, body: str | None) -> str:
    """只读**传输分帧面**：状态行 + Transfer-Encoding + Content-Length。

    ⚠️ **为什么单独一个函数（而不是给 :func:`probe` 加字段）**：
        ``probe`` 的输出格式被 run.sh 直接切分比对 ⇒ 那是 **M1 面的冻结读数形状**
        ⇒ 往里加字段会动到判据基础 ⇒ ⇒ 另起一个函数，另起一个探针脚本。

    返回：``FRAMING|<状态行>|<Transfer-Encoding 或 ->|<Content-Length 或 ->``
    """
    raw = _send_raw(port, method, path, body)
    status_line, headers, _payload = _split_final_response_headers(raw)
    transfer_encoding = headers.get("transfer-encoding", "-")
    content_length = headers.get("content-length", "-")
    return f"FRAMING|{status_line}|{transfer_encoding}|{content_length}"


def main() -> int:
    if len(sys.argv) < 4 or len(sys.argv) > 5:
        sys.stderr.write("usage: http_probe.py <port> <method> <path> [body]\n")
        return 2
    port = int(sys.argv[1])
    method = sys.argv[2]
    path = sys.argv[3]
    body = sys.argv[4] if len(sys.argv) == 5 else None
    sys.stdout.write(probe(port, method, path, body) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
