#!/usr/bin/env python3
"""签发 follow 域探针用的 JWT（HS256）。

**为什么自己签而不是去调 user-api**：follow 域**只验签、不签发**
（Go `service/follow/api/etc/followcenter.yaml` 的 `UserAuth.AccessSecret` 只被
`rest.WithJwt` 消费；签发方是 user-api）。探针要的是一个
「**两侧都能验过**」的 token ⇒ 用**同一把 secret** 自签即可，
不必把整个 user 域拉起来。

**两侧的验签口径（必须同时成立）**：
- Go：`go-zero@v1.10.2/rest/token/tokenparser.go:81-86`
  → `request.ParseFromRequest(r, request.AuthorizationHeaderExtractor, keyfunc)`，
  keyfunc 无条件返回 `[]byte(secret)`；
  parser = `jwt.NewParser(jwt.WithJSONNumber())` ⇒ **数字 claim 是 json.Number**。
  `rest/handler/authhandler.go:71-79` 把**非标准 claim**（aud/exp/jti/iat/iss/nbf/sub 除外）
  逐个塞进 ctx ⇒ `ctx.Value("userId")` 拿到的是**原始对象**。
  `golang-jwt/jwt/v4@v4.4.2 map_claims.go` 的 `Valid()`：
  `exp` / `iat` 存在时会被校验（`req=false` ⇒ 缺失不算错）⇒ `iat` 必须 ≤ now。
- RAG：`FollowJwtSignatureVerifier`（jjwt 0.12）
  → `Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(secret))`。
  ⚠️ secret 是 **36 字节 UUID**（≥32）⇒ padding 是 **no-op** ⇒ 与 Go 用的是**同一把 key**。

**claim `userId` 用字符串**：Go 的 `api/internal/logic/follow/helper.go:13-33`
`extractUserID` 有 `case string: strconv.ParseInt(...)` 分支 ⇒ 字符串 claim 合法；
RAG 的 `FollowApiSupport#userId` 同样接受字符串（见该类注释「本域比 message 域宽」）。

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
    user_id = sys.argv[2]
    now = int(time.time())
    # ⚠️ `iat` 必须 ≤ now（golang-jwt MapClaims.Valid 会校验），
    #    `exp` 放远期（jjwt 会校验；Go 侧因 WithJSONNumber 走 json.Number 分支也会校验）。
    claims = {"userId": user_id, "iat": now, "exp": now + 86400}
    header = {"alg": "HS256", "typ": "JWT"}
    h = b64(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    p = b64(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    sig = b64(hmac.new(secret, h + b"." + p, hashlib.sha256).digest())
    sys.stdout.write((h + b"." + p + b"." + sig).decode("ascii"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
