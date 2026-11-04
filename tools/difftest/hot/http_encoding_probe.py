#!/usr/bin/env python3
"""hot 域双侧活体对拍 —— **传输编码面**探针（Transfer-Encoding / Content-Length）。

为什么需要它（独立于业务字节面）：
    M1 面比的是「状态行 + Content-Type + body hex」⇒ **不含**「响应怎么分帧」。
    ⇒ ⇒ 「一侧用 chunked、另一侧用 Content-Length」这种差异**在 M1 面看不见**
    ⇒ ⇒ 而它恰恰是**客户端能不能直接按 Content-Length 读 body**的分野
    ⇒ ⇒ 必须把分帧方式提成**独立可判的面**。

🔴 **判定口径：两侧相等，不断言绝对值**：
    · B 侧与 C 侧**都是 Java / Spring**（Boot 4 vs Boot 3.5）⇒ 传输编码**必须一致**
      ⇒ ⇒ 判据 = 「两侧读数相等」。
    · ⚠️ **不写死「必须是 chunked」**：Go 真源那边是 Content-Length
      ⇒ 那是**两侧共同继承的、已登记的差异**（相对 Go）
      ⇒ ⇒ 写死绝对值会把「继承的差异」和「C 侧自己的漂移」混成同一条
      ⇒ ⇒ 本面**只判两侧相等**；绝对值那一面由冻结锚（Go 侧逐字节读数）承担。

与 ``http_probe.py`` 的关系：
    **直接 import 复用**同一套裸 socket 收发与响应头解析逻辑
    ⇒ 两侧的分帧读数**逐字同一份代码** ⇒ 不会出现口径分叉。

输出（单行，``|`` 分隔）：
    FRAMING|<原始状态行>|<Transfer-Encoding 或 ->|<Content-Length 或 ->|

用法：
    python3 http_encoding_probe.py <port> <method> <path> [body]
"""

import os
import sys

# ⚠️ **必须把本文件所在目录加进 sys.path** ⇒ 才能 import 同目录的 http_probe。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import http_probe  # noqa: E402  （路径设置在前 ⇒ 这行必须后置）


def main() -> int:
    if len(sys.argv) < 4 or len(sys.argv) > 5:
        sys.stderr.write("usage: http_encoding_probe.py <port> <method> <path> [body]\n")
        return 2
    port = int(sys.argv[1])
    method = sys.argv[2]
    path = sys.argv[3]
    body = sys.argv[4] if len(sys.argv) == 5 else None
    sys.stdout.write(http_probe.probe_framing(port, method, path, body) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
