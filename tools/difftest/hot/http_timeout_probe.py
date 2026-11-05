#!/usr/bin/env python3
"""hot 域双侧活体对拍 —— **带计时**的 HTTP 字节探针（超时面专用）。

为什么要有第二份探针（而不是给 ``http_probe.py`` 加个字段）：
    M1 面的读数口径是「**逐字节相等**」⇒ 它的输出格式被 run.sh 直接切分比对
    ⇒ 往里加字段会动到**已冻结的读数形状**（那是 M1 的判据基础）。
    ⇒ 故另起一份，**只多一个耗时**，输出独立格式。

与 ``http_probe.py`` 的关系：
    **直接 import 复用**同一套裸 socket 收发与 chunked 解码逻辑
    ⇒ 两侧的「读原始状态行 / Content-Type / body hex」口径**逐字同一份代码**
    ⇒ 不会出现「两份探针各写一遍 socket 逻辑 ⇒ 口径悄悄分叉」。

输出（单行，``|`` 分隔）：
    TIMEOUT|<原始状态行>|<Content-Type 头>|<body 的 hex>|<耗时毫秒>

    · 探针没拿到任何响应时，状态行 = ``<NO-RESPONSE>``（与 http_probe.py 同款口径）。

用法：
    python3 http_timeout_probe.py <port> <method> <path> [body]
"""

import os
import sys
import time

# ⚠️ **必须把本文件所在目录加进 sys.path** ⇒ 才能 import 同目录的 http_probe。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import http_probe  # noqa: E402  （路径设置在前 ⇒ 这行必须后置）


def probe_timed(port: int, method: str, path: str, body: str | None) -> str:
    """发一次请求并计时。

    :param port: 端口
    :param method: HTTP 方法
    :param path: 路径（含 query）
    :param body: body（``None`` = 无 body）
    :return: ``TIMEOUT|...|耗时毫秒`` 单行
    """
    started = time.monotonic()
    raw = http_probe.probe(port, method, path, body)
    elapsed_ms = int((time.monotonic() - started) * 1000)
    # http_probe.probe 的输出恒为 "HTTP|status|content_type|body_hex"（4 段）。
    parts = raw.split("|", 3)
    if len(parts) != 4:
        return "TIMEOUT|<PARSE-ERROR>|||" + str(elapsed_ms)
    return "TIMEOUT|{}|{}|{}|{}".format(parts[1], parts[2], parts[3], elapsed_ms)


def main() -> int:
    if len(sys.argv) < 4 or len(sys.argv) > 5:
        sys.stderr.write("usage: http_timeout_probe.py <port> <method> <path> [body]\n")
        return 2
    port = int(sys.argv[1])
    method = sys.argv[2]
    path = sys.argv[3]
    body = sys.argv[4] if len(sys.argv) == 5 else None
    sys.stdout.write(probe_timed(port, method, path, body) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
