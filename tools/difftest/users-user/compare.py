#!/usr/bin/env python3
"""user 域双侧活体对拍 · 比对器（B 侧 user-api ↔ C 侧 user-service）。

用法：compare.py <B_BASE> <C_BASE> [用户名后缀]
退出码：0 = 结构级一致 · 1 = 有结构级不一致 · 2 = 环境问题（连不上）
"""
import json
import re
import sys
import urllib.error
import urllib.request

B = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18301"
C = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:18304"
UNIQ = sys.argv[3] if len(sys.argv) > 3 else "pb"

# 归一化：两侧**必然**不同的量（雪花 uid / history_id / 自签 JWT / 时间戳 / 密码哈希）
NORM = [
    (re.compile(r'"uid"\s*:\s*"?\d+"?'), '"uid":"<UID>"'),
    (re.compile(r'"history_id"\s*:\s*"?\d+"?'), '"history_id":"<HISTORY_ID>"'),
    (re.compile(r'"id"\s*:\s*"?\d+"?'), '"id":"<ID>"'),
    (re.compile(r'"token"\s*:\s*"[^"]*"'), '"token":"<TOKEN>"'),
    (re.compile(r'"(create_time|update_time|expire|iat)"\s*:\s*"?[^",}]*"?'), r'"\1":"<TS>"'),
    (re.compile(r'"password"\s*:\s*"[^"]*"'), '"password":"<HASH>"'),
    (re.compile(r'\d{4}-\d{2}-\d{2}T[\d:.]+([Zz]|[+-]\d{2}:\d{2})?'), '<TS>'),
]


def norm(s):
    for rx, rep in NORM:
        s = rx.sub(rep, s)
    return s


def keyorder(body):
    """JSON 键序归一化：递归按 key 排序 ⇒ 消除「声明序 vs 字母序」这一表达层差异。"""
    try:
        return json.dumps(json.loads(body), sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    except Exception:  # noqa: BLE001
        return body


def call(base, method, path, body=None, token=None):
    """⇒ (http_status, content_type, raw_body_text)"""
    data = body.encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.headers.get("Content-Type", ""), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return 0, "", "EXC:%s" % e


def bizcode(body):
    m = re.search(r'"code"\s*:\s*(\d+)', body)
    return m.group(1) if m else "-"


STRUCT_FAIL = 0
BYTE_FAIL = 0
TOTAL = 0


def run_case(name, method, path, body=None, token=None, ctoken=None):
    """token/ctoken = B 侧 / C 侧各自的 token（两侧自签 ⇒ token 必然不同 ⇒ 各自持有）。"""
    global STRUCT_FAIL, BYTE_FAIL, TOTAL
    TOTAL += 1
    bs, bct, bb = call(B, method, path, body, token)
    cs, cct, cb = call(C, method, path, body, ctoken)
    nb, nc = norm(bb), norm(cb)
    byte_ok = (bs == cs) and (nb == nc) and (bct == cct)
    struct_ok = byte_ok or ((bs == cs) and (keyorder(nb) == keyorder(nc)))
    if not struct_ok:
        STRUCT_FAIL += 1
    if not byte_ok:
        BYTE_FAIL += 1
    extra = "  [仅键序不同]" if (struct_ok and not byte_ok) else ""
    print("%s %-40s HTTP B=%s C=%s | ct B=%s C=%s | biz B=%s C=%s%s" % (
        "✅" if struct_ok else "❌", name, bs, cs,
        bct.split(";")[0], cct.split(";")[0], bizcode(bb), bizcode(cb), extra))
    if not struct_ok:
        print("      B body: %s" % nb[:400])
        print("      C body: %s" % nc[:400])
    return struct_ok


def main():
    uniq = "%s%s" % (UNIQ, sys.argv[4] if len(sys.argv) > 4 else "1")

    # ── 1. 参数校验面 ────────────────────────────────────────
    run_case("register 空 body", "POST", "/user/v1/register", "{}")
    run_case("register 缺 password", "POST", "/user/v1/register", '{"username":"%s"}' % uniq)
    run_case("login 空 body", "POST", "/user/v1/login", "{}")
    run_case("login 缺 password", "POST", "/user/v1/login", '{"username":"%s"}' % uniq)

    # ── 2. 注册面 ────────────────────────────────────────────
    reg = '{"username":"%s","password":"pw123456","email":"%s@example.test"}' % (uniq, uniq)
    run_case("register 正常", "POST", "/user/v1/register", reg)
    run_case("register 重名", "POST", "/user/v1/register", reg)

    # ── 3. 登录面 ────────────────────────────────────────────
    run_case("login 用户不存在", "POST", "/user/v1/login",
             '{"username":"nosuch_%s","password":"pw123456"}' % uniq)
    run_case("login 密码错", "POST", "/user/v1/login",
             '{"username":"%s","password":"wrongpw"}' % uniq)

    # 两侧各自的 token（登录成功才取）
    btok = ctok = ""
    bs, _, bb = call(B, "POST", "/user/v1/login",
                     '{"username":"%s","password":"pw123456"}' % uniq)
    if bs == 200:
        try:
            btok = json.loads(bb)["data"]["token"]
        except Exception:  # noqa: BLE001
            pass
    cs, _, cb = call(C, "POST", "/user/v1/login",
                     '{"username":"%s","password":"pw123456"}' % uniq)
    if cs == 200:
        try:
            ctok = json.loads(cb)["data"]["token"]
        except Exception:  # noqa: BLE001
            pass
    print("   ℹ️ 两侧各自 token 长度 B=%d C=%d" % (len(btok), len(ctok)))
    if not btok or not ctok:
        print("   ❌ 取 token 失败（B=%s C=%s）" % (bs, cs))
        return 2

    # 两侧各自的 uid
    buid = cuid = ""
    _, _, bb2 = call(B, "POST", "/user/v1/get", "{}", btok)
    try:
        buid = json.loads(bb2)["data"]["user"]["uid"]
    except Exception:  # noqa: BLE001
        pass
    _, _, cb2 = call(C, "POST", "/user/v1/get", "{}", ctok)
    try:
        cuid = json.loads(cb2)["data"]["user"]["uid"]
    except Exception:  # noqa: BLE001
        pass
    print("   ℹ️ 两侧各自 uid B=%s C=%s" % (buid, cuid))

    # ── 4. 鉴权面 ────────────────────────────────────────────
    run_case("get 无 token", "POST", "/user/v1/get", "{}")
    run_case("get 坏签名", "POST", "/user/v1/get", "{}", token="not-a-jwt", ctoken="not-a-jwt")
    run_case("get 合法 token + 不存在 uid", "POST", "/user/v1/get", '{"uid":"999999999999"}',
             token=btok, ctoken=ctok)
    run_case("get 合法 token + 存在 uid", "POST", "/user/v1/get",
             '{"uid":"%s"}' % (buid or "0"), token=btok, ctoken=ctok)
    run_case("avatar/history 合法 token", "POST", "/user/v1/avatar/history", "{}",
             token=btok, ctoken=ctok)

    # ── 5. 路由面 ────────────────────────────────────────────
    run_case("未知路径 /user/v1 内", "POST", "/user/v1/nope", "{}")
    run_case("未知路径 /user/v1 外", "POST", "/nope", "{}")
    run_case("GET 打 POST 端点", "GET", "/user/v1/login")

    # ── 6. 写路径 ────────────────────────────────────────────
    run_case("update 改用户名", "POST", "/user/v1/update",
             '{"username":"%s_upd"}' % uniq, token=btok, ctoken=ctok)
    run_case("update 后 get", "POST", "/user/v1/get",
             '{"uid":"%s"}' % (buid or "0"), token=btok, ctoken=ctok)

    # ── 7. 登出 + 黑名单 ─────────────────────────────────────
    run_case("logout", "POST", "/user/v1/logout", "{}", token=btok, ctoken=ctok)
    run_case("logout 后再用 token", "POST", "/user/v1/get",
             '{"uid":"%s"}' % (buid or "0"), token=btok, ctoken=ctok)

    print("\n—— 汇总：%d 例 ｜ 结构级不一致 %d ｜ 字节级不一致 %d ——" % (TOTAL, STRUCT_FAIL, BYTE_FAIL))
    print("   口径：结构级 = 归一化 ID/token/时间戳 + JSON 键序后逐字节相等；")
    print("         字节级 = 只归一化 ID/token/时间戳（键序参与比较）。")
    return 1 if STRUCT_FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
