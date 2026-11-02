#!/usr/bin/env python3
"""user 域双侧活体对拍 · 比对器（B 侧 user-api ↔ C 侧 user-service）。

用法：compare.py <B_BASE> <C_BASE> [用户名后缀]
退出码：0 = 结构级一致 · 1 = 有结构级不一致 · 2 = 环境问题（连不上）
"""
import json
import os
import re
import subprocess
import sys
import tempfile

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


_TMPDIR = tempfile.mkdtemp(prefix="e4user-parity-")


def call(base, method, path, body=None, token=None):
    """⇒ (http_status, content_type, raw_body_text)

    ⚠️ **用 curl 而不是 urllib**（本探针踩过的两个坑）：
    ① **1xx 中间响应**：B 侧的 `HttpMiddleware` 有「**1xx 探针行数预算**」机制
       ⇒ 会**主动发 1xx 探针字节**（首次请求实测收到 `103`）⇒
       Python `urllib` **只跳过 100** ⇒ 会把 `103` 当成最终响应（空 body + 无 Content-Type）
       ⇒ 误判成「两侧不一致」；
       ⇒ `http.client` 也不行（读 1xx 后 `close()` 会**关掉 socket** ⇒ 下一次 `getresponse()` 失败）；
       ⇒ ⇒ **`curl` 天然跳过 1xx**（等最终响应）⇒ 且与 follow 域探针同款。
    ② **Content-Type 全串**：`%{content_type}` 直接给出 ⇒ 不用解析头。
    """
    hdr = os.path.join(_TMPDIR, "hdr")
    bdy = os.path.join(_TMPDIR, "bdy")
    cmd = ["curl", "-s", "-D", hdr, "-o", bdy,
           "-w", "%{http_code}|%{content_type}", "-m", "30",
           "-X", method, base + path, "-H", "Content-Type: application/json"]
    if body is not None:
        cmd += ["--data-binary", body]
    if token:
        cmd += ["-H", "Authorization: Bearer " + token]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True).stdout.strip()
    except Exception as e:  # noqa: BLE001
        return 0, "", "EXC:%s" % e
    code, _, ctype = out.partition("|")
    try:
        with open(bdy, "r", encoding="utf-8", errors="replace") as fh:
            raw = fh.read()
    except Exception as e:  # noqa: BLE001
        return 0, "", "EXC:%s" % e
    if not code.isdigit():
        return 0, "", "EXC:curl=%s" % out
    return int(code), ctype or "", raw


def bizcode(body):
    m = re.search(r'"code"\s*:\s*(\d+)', body)
    return m.group(1) if m else "-"


STRUCT_FAIL = 0
BYTE_FAIL = 0
TOTAL = 0
KNOWN_DRIFT = 0   # 🔶 仅键序不同（DRIFT-USER-PARITY-003 · 共享层待收口）
TEXT_DIFF = 0     # 文案形态不同（已登记 D-01 · 不计入失败）


def mentioned_field(text):
    """从 400 文案里抽出「被点名的字段」。
    B 侧（go-zero）：`field "username" is not set` ⇒ `username`
    C 侧（Bean Validation）：`username 不能为null` ⇒ `username`
    ⇒ 抽出后即可**跨文案形态**比对「报的是哪个字段」。
    """
    m = re.search(r'field\s+"([^"]+)"', text)
    if m:
        return m.group(1)
    m = re.match(r'\s*([A-Za-z_][A-Za-z0-9_]*)\s', text)
    return m.group(1) if m else None


def run_case(name, method, path, body=None, token=None, ctoken=None, text_only=False):
    """token/ctoken = B 侧 / C 侧各自的 token（两侧自签 ⇒ token 必然不同 ⇒ 各自持有）。

    text_only=True  ⇒ **已登记的文案形态偏离**（D-01：go-zero `field "x" is not set`
                      ↔ Bean Validation `x 不能为null`）⇒ 判据放宽为「状态码 + content-type
                      + **被点名的字段**」⇒ ⇒ 文案形态的偏离**不计入失败**，
                      但**报哪个字段**必须一致（那是对齐 B 的硬要求）。
    known_drift=**自动识别**，不逐例硬标 ⇒ 三档：
      ① 字节级一致                      ⇒ ✅ 完全一致
      ② 仅 **JSON 键序** 不同           ⇒ 🔶 **已知跨域漂移** DRIFT-USER-PARITY-003
                                        （共享层 Jackson 2/3 默认差异 ⇒ 收口域待修）
                                        ⇒ 单列计数、显眼报出，**不计入失败**
      ③ 其余差异                        ⇒ ❌ 真差异 ⇒ **计入失败**
    """
    global STRUCT_FAIL, BYTE_FAIL, TOTAL, KNOWN_DRIFT, TEXT_DIFF
    TOTAL += 1
    bs, bct, bb = call(B, method, path, body, token)
    cs, cct, cb = call(C, method, path, body, ctoken)
    nb, nc = norm(bb), norm(cb)
    byte_ok = (bs == cs) and (nb == nc) and (bct == cct)
    if text_only:
        # 文案形态不同（D-01 已登记）⇒ 结构级判据换成「状态码 + content-type + 点名字段」
        fb, fc = mentioned_field(bb), mentioned_field(cb)
        # ⚠️ Content-Type 的 **charset 值大小写不敏感**（RFC 9110）⇒ 比较时归一化
        #    （B: `text/plain;charset=UTF-8` ↔ C: `text/plain; charset=utf-8` ⇒ 语义等价）
        ctb = bct.replace(" ", "").lower()
        ctc = cct.replace(" ", "").lower()
        struct_ok = (bs == cs) and (ctb == ctc) and (fb == fc) and (fb is not None)
        if not struct_ok:
            print("      点名字段 B=%s C=%s ｜ ct B=%r C=%r" % (fb, fc, bct, cct))
    else:
        struct_ok = byte_ok or ((bs == cs) and (keyorder(nb) == keyorder(nc)))
    extra = ""
    if not struct_ok:
        STRUCT_FAIL += 1
        if not byte_ok:
            BYTE_FAIL += 1
    elif not byte_ok:
        BYTE_FAIL += 1
        if text_only:
            TEXT_DIFF += 1
            extra = "  [文案形态差异 = 已登记 D-01]"
        else:
            KNOWN_DRIFT += 1
            extra = "  [🔶 仅键序不同 = DRIFT-USER-PARITY-003 · 共享层待收口]"
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
    # ⚠️ 这 4 例的**文案形态**两侧必不同（go-zero `field "x" is not set` ↔ Bean Validation
    #    `x 不能为null`）⇒ **已登记的 L3 降级 D-01 / DEV-USER-002** ⇒
    # ⇒ 判据放宽为「状态码 + content-type + **被点名的字段**」
    #    ⇒ ⇒ **文案形态的偏离不计入失败**，而「报哪个字段」仍是对齐 B 的硬要求（必须一致）。
    run_case("register 空 body", "POST", "/user/v1/register", "{}", text_only=True)
    run_case("register 缺 password", "POST", "/user/v1/register",
             '{"username":"%s"}' % uniq, text_only=True)
    run_case("login 空 body", "POST", "/user/v1/login", "{}", text_only=True)
    run_case("login 缺 password", "POST", "/user/v1/login",
             '{"username":"%s"}' % uniq, text_only=True)

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

    print("\n—— 汇总：%d 例 ｜ ❌ 真差异 %d ｜ 字节级差异 %d（🔶 键序 %d ＋ 文案形态 %d）——" % (
        TOTAL, STRUCT_FAIL, BYTE_FAIL, KNOWN_DRIFT, TEXT_DIFF))
    print("   口径：真差异 = 归一化 ID/token/时间戳 + JSON 键序 + **文案形态**后仍不一致")
    print("         （400 的文案形态按**已登记 D-01** 放宽为「状态码 + content-type + 点名字段」）；")
    print("         字节级差异含 🔶 键序（DRIFT-USER-PARITY-003 · 共享层待收口）⇒ 收口域修掉后该计数会降。")
    return 1 if STRUCT_FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
