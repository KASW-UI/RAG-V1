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


def probe(port: int, method: str, path: str, body: str | None) -> str:
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
        raw = _read_all(sock)
    finally:
        sock.close()

    head, sep, payload = raw.partition(b"\r\n\r\n")
    if not sep:
        # 连状态行都没拿到 ⇒ 连接层失败（探针据此判定「挂死」）
        return "HTTP|<NO-RESPONSE>||" + payload.hex()

    lines = head.split(b"\r\n")
    status_line = lines[0].decode("latin-1")
    content_type = ""
    chunked = False
    for line in lines[1:]:
        lowered = line.lower()
        if lowered.startswith(b"content-type:"):
            content_type = line.split(b":", 1)[1].decode("latin-1").strip()
        elif lowered.startswith(b"transfer-encoding:") and b"chunked" in lowered:
            chunked = True
    if chunked:
        payload = _decode_chunked(payload)
    return f"HTTP|{status_line}|{content_type}|{payload.hex()}"


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
