#!/usr/bin/env python3
"""``http_probe.py`` 的**解析器自检** —— 每次探针运行都会被执行。

为什么需要它：
    M5（超时面）**依赖**解析器正确跳过 1xx（RFC 9110 §15.2）
    ⇒ 而本仓共享层的 ``ClientDisconnectProbe`` 会**直接往 socket 写**
    ``HTTP/1.1 103 Early Hints`` ⇒ **慢请求**的响应流前面带若干个 1xx 块
    ⇒ 解析器一旦回退到「按第一个 ``\\r\\n\\r\\n`` 切分」，
    **超时面 100% 读数错位**（且症状是「形状对但字段乱」⇒ 极难一眼看出）。

⇒ 故把解析器从「藏在探针里」提成**有自检的判据**：
   本脚本用**合成字节流**喂给解析器，断言各种形态的切分结果
   ⇒ 解析器回退 ⇒ **本自检红** ⇒ 探针**在起服务之前**就死（不是跑到一半才发现读数是错的）。

退出码：0 = 全部通过 · 1 = 有断言失败。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import http_probe  # noqa: E402

FAILURES: list[str] = []


def check(name: str, got, expected) -> None:
    """断言并记录失败。"""
    if got != expected:
        FAILURES.append(f"{name}: 期望 {expected!r} · 实得 {got!r}")


def main() -> int:
    # ── ① 无 1xx 前缀的普通响应 ────────────────────────────────────────────
    raw = (b"HTTP/1.1 200 \r\n"
           b"Content-Type: application/json;charset=utf-8\r\n"
           b"Content-Length: 2\r\n"
           b"\r\n{}")
    status, ctype, body = http_probe._split_final_response(raw)
    check("① 状态行", status, "HTTP/1.1 200 ")
    check("① Content-Type", ctype, "application/json;charset=utf-8")
    check("① body", body, b"{}")

    # ── ② 🔴 带 1xx 前缀（共享层 ClientDisconnectProbe 写的 103）────────────
    raw = (b"HTTP/1.1 103 Early Hints\r\n\r\n"
           b"HTTP/1.1 103 Early Hints\r\n\r\n"
           b"HTTP/1.1 503 \r\n"
           b"Content-Type: text/plain;charset=utf-8\r\n"
           b"Content-Length: 16\r\n"
           b"\r\n"
           b"Request Timeout\n")
    status, ctype, body = http_probe._split_final_response(raw)
    check("② 跳过 1xx 后的状态行", status, "HTTP/1.1 503 ")
    check("② Content-Type", ctype, "text/plain;charset=utf-8")
    check("② body", body, b"Request Timeout\n")

    # ── ③ 1xx 前缀 + chunked 最终响应 ─────────────────────────────────────
    raw = (b"HTTP/1.1 103 Early Hints\r\n\r\n"
           b"HTTP/1.1 200 \r\n"
           b"Content-Type: application/json;charset=utf-8\r\n"
           b"Transfer-Encoding: chunked\r\n"
           b"\r\n"
           b"2\r\n{}\r\n"
           b"0\r\n\r\n")
    status, ctype, body = http_probe._split_final_response(raw)
    check("③ 状态行", status, "HTTP/1.1 200 ")
    check("③ chunked 已解块", body, b"{}")

    # ── ④ 连状态行都没拿到（连接层失败）────────────────────────────────────
    status, ctype, body = http_probe._split_final_response(b"")
    check("④ 无响应时的状态行", status, "<NO-RESPONSE>")

    # ── ⑤ 100 Continue（B 侧 go-zero 的 net/http 也会发）────────────────────
    raw = (b"HTTP/1.1 100 Continue\r\n\r\n"
           b"HTTP/1.1 200 \r\n"
           b"Content-Length: 2\r\n"
           b"\r\n{}")
    status, _ctype, body = http_probe._split_final_response(raw)
    check("⑤ 跳过 100 后的状态行", status, "HTTP/1.1 200 ")
    check("⑤ body", body, b"{}")

    # ── ⑥ 🔴 **传输分帧面**（chunked / Content-Length）────────────────────────
    # ⚠️ **为什么要在这里钉**：新增的「传输编码面」判据（两侧分帧方式必须相等）
    #    **完全依赖这个解析器** ⇒ 解析器回退 ⇒ 该面读数错位而**判据恒绿**
    #    ⇒ ⇒ 解析器一旦改坏，这里必须先红。
    raw = (b"HTTP/1.1 200 \r\n"
           b"Content-Type: application/json;charset=utf-8\r\n"
           b"Transfer-Encoding: chunked\r\n"
           b"\r\n"
           b"2\r\n{}\r\n"
           b"0\r\n\r\n")
    status, headers, body = http_probe._split_final_response_headers(raw)
    check("⑥ chunked 的状态行", status, "HTTP/1.1 200 ")
    check("⑥ chunked 的 Transfer-Encoding", headers.get("transfer-encoding"), "chunked")
    check("⑥ chunked 没有 Content-Length", headers.get("content-length", "-"), "-")
    check("⑥ chunked 已解块", body, b"{}")

    raw = (b"HTTP/1.1 200 \r\n"
           b"Content-Type: application/json;charset=utf-8\r\n"
           b"Content-Length: 2\r\n"
           b"\r\n{}")
    _status, headers, body = http_probe._split_final_response_headers(raw)
    check("⑥ 定长的 Transfer-Encoding", headers.get("transfer-encoding", "-"), "-")
    check("⑥ 定长的 Content-Length", headers.get("content-length"), "2")
    check("⑥ 定长的 body", body, b"{}")

    # ── ⑦ 🔴 **1xx 前缀不能把头读串位**（分帧面同样要跳过 1xx）──────────────
    raw = (b"HTTP/1.1 103 Early Hints\r\n"
           b"Transfer-Encoding: chunked\r\n"
           b"\r\n"
           b"HTTP/1.1 200 \r\n"
           b"Content-Length: 2\r\n"
           b"\r\n{}")
    status, headers, body = http_probe._split_final_response_headers(raw)
    check("⑦ 跳过 1xx 后的状态行", status, "HTTP/1.1 200 ")
    check("⑦ 1xx 的 Transfer-Encoding 没串位", headers.get("transfer-encoding", "-"), "-")
    check("⑦ 最终响应的 Content-Length", headers.get("content-length"), "2")
    check("⑦ body", body, b"{}")

    if FAILURES:
        for line in FAILURES:
            sys.stderr.write("   ❌ " + line + "\n")
        sys.stderr.write(f"❌ http_probe 解析器自检：{len(FAILURES)} 条断言失败\n")
        return 1
    sys.stdout.write("   ✓ http_probe 解析器自检通过（含 1xx 前缀 / chunked / 无响应 四类形态）\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
