#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""签发 favorite 域探针用的 JWT（HS256）—— **两侧共用同一枚 token**。

**为什么自己签而不是去调 user-api**：favorite 域**只验签、不签发**
（B 侧 `service/favorite/favorite-api/src/main/resources/application.yml`
 写的是 `access-secret: ${...}`，**故意没有默认值** ⇒ 缺变量 ⇒ 进程起不来；
 C 侧 `favorite-api.user-auth.access-secret: ${FAVORITE_JWT_SECRET}` 同款 ⇒ 同样没有默认值）。
探针要的是一个「**两侧都能验过**」的 token ⇒ 用**同一把 secret** 自签即可。

**两侧的验签口径（必须同时成立）**：
- B 侧 `FavoriteJwtSignatureVerifier.verify(...)`（jjwt）→ HMAC-SHA256，
  key 由 `JwtUtil.padToHmacKeySize(secret)` 补齐；
- C 侧 `interceptor/FavoriteJwtAuthInterceptor` + `service/FavoriteApiSupport#userId(...)`
  → **同一个 claim 名 `userId`**，取法与 B 侧逐字一致（见下）。
⚠️ **secret 必须 ≥32 字节** ⇒ 两侧的 padding 都是 **no-op** ⇒ 落到同一份 32 字节 key 上
⇒ ⇒ 同一枚 token 两侧都能验过。

⚠️ **`userId` 必须是 JSON 数字（不是字符串）** —— 两侧的 `userId(request)` 取法是：
- 字符串 ⇒ 走 `Long.parseLong` 分支（**能过**）；
- 整型（Integer / Long / BigInteger）⇒ 直接 `longValue()`（**能过**）；
- `Double` / `Float` ⇒ **判失败**（Go 侧 `json.Number("1.5").Int64()` 同款 ⇒ 失败）。
⇒ ⇒ 探针一律签**整数** ⇒ 两侧都走整型分支 ⇒ 一致。

⚠️ `iat` 必须 ≤ now：`golang-jwt/jwt/v4` 的 `MapClaims.Valid()` 会校验 `iat`
（`req=false` ⇒ 缺失不算错，但**存在就必须是过去**）。

用法：
    python3 mint-jwt.py <secret> <userId> [ttlSeconds] [--expired] [--iat-offset N]

⚠️ **`--iat-offset` 是必需的（当你要造「黑名单对照组」那一枚时）**：
    同一秒内用**同样的 secret + 同样的 userId** 签出来的 token 是**逐字节相同**的
    ⇒ ⇒ 若把其中一枚写进黑名单，**另一枚也会被拉黑**
    ⇒ ⇒ 探针的「正常 token」与「被拉黑 token」会**同时 401**
    ⇒ ⇒ 症状 = 「所有带 token 的用例都 401」⇒ **极像产品回归，实则是探针自伤**
    ⇒ ⇒ 用 `--iat-offset` 把两枚的 `iat` 错开 ⇒ 两枚 token 必然不同。
"""

import base64
import hashlib
import hmac
import json
import sys
import time


def b64(raw: bytes) -> bytes:
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


def mint(secret: str, user_id: int, ttl: int, expired: bool, iat_offset: int) -> str:
    key = secret.encode("utf-8")
    now = int(time.time()) + iat_offset
    claims = {"userId": user_id, "iat": now, "exp": now + ttl}
    if expired:
        # exp 已在过去 ⇒ 两侧都判「过期」⇒ 401
        claims["exp"] = now - 3600
    header = {"alg": "HS256", "typ": "JWT"}
    h = b64(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    p = b64(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    sig = b64(hmac.new(key, h + b"." + p, hashlib.sha256).digest())
    return (h + b"." + p + b"." + sig).decode("ascii")


def main() -> int:
    if len(sys.argv) < 3:
        sys.stderr.write(
            "usage: mint-jwt.py <secret> <userId> [ttlSeconds] [--expired] [--iat-offset N]\n")
        return 2
    secret = sys.argv[1]
    user_id = int(sys.argv[2])
    ttl = 86400
    expired = False
    iat_offset = 0
    rest = sys.argv[3:]
    while rest:
        arg = rest.pop(0)
        if arg == "--expired":
            expired = True
        elif arg == "--iat-offset":
            iat_offset = int(rest.pop(0))
        else:
            ttl = int(arg)
    if len(secret.encode("utf-8")) < 32:
        # ⚠️ 不拦死：jjwt 侧会 pad 到 32 字节（zero-fill）⇒ 两侧仍一致
        #    ⇒ 但 Go 侧不 pad ⇒ 只有当 secret ≥32 字节时两侧才真正同形
        sys.stderr.write(
            "warning: secret < 32 bytes ⇒ jjwt 会 zero-fill 补齐，Go 侧不会 ⇒ 建议用 ≥32 字节\n")
    sys.stdout.write(mint(secret, user_id, ttl, expired, iat_offset))
    return 0


if __name__ == "__main__":
    sys.exit(main())
