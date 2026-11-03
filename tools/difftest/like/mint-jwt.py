#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""签发 like 域探针用的 JWT（HS256）—— **两侧共用同一枚 token**。

**为什么自己签而不是去调 user-api**：like 域**只验签、不签发**
（B 侧 `service/like/like-api/src/main/resources/application.yml:88`
 写的是 `access-secret: ${USER_JWT_SECRET}`，**故意没有默认值** ⇒ 缺变量 ⇒ 进程起不来；
 签发方是 user-api）。探针要的是一个「**两侧都能验过**」的 token
⇒ 用**同一把 secret** 自签即可，不必把整个 user 域拉起来。

**两侧的验签口径（必须同时成立）**：
- B 侧 `LikeJwtSignatureVerifier.verify(...)`（jjwt 0.12）
  → `Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(secret))`。
  ⚠️ 探针用的 secret 是 **≥32 字节** ⇒ padding 是 **no-op** ⇒ 与裸 secret 同形。
- C 侧 `interceptor/LikeJwtAuthInterceptor` + `service/LikeApiSupport#userId(...)`
  → 同一个 claim 名 `userId`。

⚠️ **`userId` 必须是 JSON 数字（不是字符串）** —— 这是 like 域与 follow 域的**关键差别**：
- B 侧 `LikeApiSupport#userId(...)` 只接受整型（Byte/Short/Integer/Long/BigInteger）
  ⇒ 字符串 ⇒ **1006**。
- Go 侧（冻结锚的产出方）用 `golang-jwt/jwt/v4` 的 `MapClaims` + `UseNumber()`
  ⇒ JSON 数字 ⇒ `json.Number` ⇒ 断言成功；字符串 ⇒ 断言失败 ⇒ Go 返 **1006**。
⇒ 两侧一致 ⇒ **必须签数字**。
（follow 域的 `mint-jwt.py` 签的是字符串 —— **那在本域会直接 1006** ⇒ 不能照抄。）

⚠️ `iat` 必须 ≤ now：`golang-jwt/jwt/v4` 的 `MapClaims.Valid()` 会校验 `iat`
（`req=false` ⇒ 缺失不算错，但**存在就必须是过去**）。

用法：`python3 mint-jwt.py <secret> <userId>`
"""

import base64
import hashlib
import hmac
import json
import sys
import time


def b64(raw: bytes) -> bytes:
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


def main() -> int:
    if len(sys.argv) != 3:
        sys.stderr.write("usage: mint-jwt.py <secret> <userId>\n")
        return 2
    secret = sys.argv[1].encode("utf-8")
    # ⚠️ **必须是整型**：字符串 / 小数 ⇒ 两侧都返 1006（见模块 docstring）。
    user_id = int(sys.argv[2])
    now = int(time.time())
    claims = {"userId": user_id, "iat": now, "exp": now + 86400}
    header = {"alg": "HS256", "typ": "JWT"}
    h = b64(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    p = b64(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    sig = b64(hmac.new(secret, h + b"." + p, hashlib.sha256).digest())
    sys.stdout.write((h + b"." + p + b"." + sig).decode("ascii"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
