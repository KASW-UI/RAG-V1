#!/usr/bin/env python3
"""admin 域 · B 侧 ↔ C 侧 双侧活体对拍探针（E-4）。

按 `.agents/verification.md` §九「第六阶段：行为对拍和验证」执行：
对**同一输入**分别打 B 侧（RAG 仓 · Boot 4）与 C 侧（RAG-V1 · Boot 3.5），
比较 **HTTP 响应（状态/头/body）**，并比对两侧 **数据库最终状态** 与 **Redis 状态**。

用法：
    B_BASE=http://127.0.0.1:18889 C_BASE=http://127.0.0.1:18890 \
        python3 tools/difftest/parity_admin.py

环境变量：
    B_BASE / C_BASE   两侧 HTTP 根地址（必填）
    B_PSQL / C_PSQL   两侧 PG 连接串（形如 postgresql://user:pass@host:port/db），用于状态比对
    B_REDIS / C_REDIS 两侧 Redis 地址（host:port）
    OUT               报告输出目录（默认 /tmp/e4-parity）

⚠️ **归一化规则**（只归一化**非确定性**与**已登记降级**，不归一化行为差异）：
    ① JWT token      —— 两侧 iat/exp 不同
    ② uid            —— 两侧 Snowflake 生成值不同
    ③ 邀请码         —— 随机 hex
    ④ epoch/时间戳    —— 过期时间等
    ⑤ **D-01 降级项** —— 必填字段缺失的 400 **文本内容**（B: go-zero `field "x" is not set`
       ↔ C: Hibernate Validator 消息）：**形状**（400 + text/plain + nosniff + 尾随换行）必须一致，
       **文本**按 `docs/L3-降级清单.md` D-01 **显式排除**，否则会把已签字的降级误判成回归。
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

B_BASE = os.environ.get("B_BASE", "").rstrip("/")
C_BASE = os.environ.get("C_BASE", "").rstrip("/")
B_PSQL = os.environ.get("B_PSQL", "")
C_PSQL = os.environ.get("C_PSQL", "")
B_REDIS = os.environ.get("B_REDIS", "")
C_REDIS = os.environ.get("C_REDIS", "")
OUT = os.environ.get("OUT", "/tmp/e4-parity")

# ── 归一化 ────────────────────────────────────────────────────────────────
RE_JWT = re.compile(r"eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+")
RE_INVITE = re.compile(r"ADM-[0-9A-F]{16}")
RE_EPOCH = re.compile(r"\b1[6-9]\d{8,}\b")          # epoch 秒 / 毫秒
RE_UID = re.compile(r"\b\d{15,20}\b")              # snowflake uid
RE_DATE = re.compile(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?Z?")


def normalize(text):
    if text is None:
        return None
    t = RE_JWT.sub("<JWT>", text)
    t = RE_INVITE.sub("<INVITE>", t)
    t = RE_UID.sub("<UID>", t)
    t = RE_EPOCH.sub("<EPOCH>", t)
    t = RE_DATE.sub("<DATE>", t)
    return t


def norm_headers(h):
    """只留 wire 可见且稳定的头（逐字节对比）。"""
    out = {}
    for k in ("Content-Type", "X-Content-Type-Options", "Allow"):
        for key, val in h.items():
            if key.lower() == k.lower():
                out[k.lower()] = val
    return out


# ── HTTP 调用 ────────────────────────────────────────────────────────────
def call(base, path, body=None, headers=None, method="POST"):
    """发一次请求，**正确处理 1xx 中间响应**（如 103 Early Hints），返回最终响应。

    ⚠️ **为什么用 curl 而不是 urllib / http.client**：
      · Tomcat 会发 `103 Early Hints`（HTTP/2 的 Early Hints 特性）。
      · `urllib` 会把 **103 当成最终响应**（body 为空）⇒ 后续 `json.loads` 失败
        ⇒ **token 取不到** ⇒ 整轮 protected 用例全成 401 ⇒ **那是探针的锅，不是行为差异**。
      · `http.client` 更糟：读完 1xx 后再 `getresponse()` 直接抛 `ResponseNotReady: Idle`。
      · `curl` 的语义正确：**只把最终响应写进 body / `%{http_code}`**，
        而 `-D <file>` 会把**所有**响应头块（含 1xx）落盘 ⇒ ⇒ 中间响应仍可作为 **wire 观测**保留。
    """
    import subprocess
    import tempfile
    url = base + path
    hdrs = {"Content-Type": "application/json"} if headers is None else dict(headers)
    with tempfile.NamedTemporaryFile("w+", suffix=".hdr", delete=False) as hf:
        hdr_path = hf.name
    cmd = ["curl", "-s", "--max-time", "30", "-D", hdr_path, "-o", "-",
           "-w", "\n__META__%{http_code}", "-X", method]
    for k, v in hdrs.items():
        cmd += ["-H", f"{k}: {v}"]
    if body is not None and method not in ("GET", "HEAD"):
        cmd += ["--data-binary", body]
    cmd += [url]          # ⚠️ URL 必须放最后追加 —— 漏了它 curl 会报 (2) no URL specified，
    #                        而**两侧同错**会被读成「一致」⇒ 假绿（见 compare() 里的 status==0 守卫）
    try:
        r = subprocess.run(cmd, capture_output=True, text=True)
        raw = r.stdout
        marker = "\n__META__"
        payload, _, meta = raw.rpartition(marker)
        status = int(meta.strip() or 0)
        # 头块：最后一个是最终响应；前面的 1xx 块单独统计
        blocks = []
        with open(hdr_path) as f:
            txt = f.read()
        for blk in [b for b in txt.split("\r\n\r\n") if b.strip()]:
            lines = [ln for ln in blk.split("\r\n") if ln]
            if not lines:
                continue
            code = lines[0].split()[1] if len(lines[0].split()) > 1 else "?"
            headers = {}
            for ln in lines[1:]:
                if ":" in ln:
                    k, _, v = ln.partition(":")
                    headers[k.strip()] = v.strip()
            blocks.append((code, headers))
        interim = [c for c, _ in blocks if c.startswith("1")]
        final = blocks[-1][1] if blocks else {}
        return status, norm_headers(final), payload, interim
    finally:
        try:
            os.unlink(hdr_path)
        except OSError:
            pass


def canon_json(text):
    """JSON 规范化：**按键排序**后序列化。

    ⚠️ **为什么要排序**：B 侧的 DTO 是 **POJO** ⇒ Jackson 默认按**字母序**输出属性；
    C 侧的 DTO 是 **record** ⇒ Jackson 按**声明序**输出 ⇒ ⇒ 两侧**键序不同但语义相同**
    ⇒ RFC 8259 里 JSON 对象的成员顺序**不承载语义** ⇒ ⇒ 比对时按键排序，
    键序差异由 `key_order` 单独记录（不静默忽略）。
    """
    try:
        return json.dumps(json.loads(text), sort_keys=True, ensure_ascii=False)
    except Exception:
        return text


def key_order(text):
    """记录 JSON 的**原始键序**（仅用于「键序不同」的显式登记）。"""
    try:
        obj = json.loads(text)
    except Exception:
        return None
    if not isinstance(obj, dict):
        return None
    return list(obj.keys())


def envelope_shape(body):
    """信封形状：键名 + 类型（不比较值）—— 值里有 uid/token 等非确定量。"""
    try:
        obj = json.loads(body)
    except Exception:
        return None
    if not isinstance(obj, dict):
        return type(obj).__name__
    return {k: type(v).__name__ for k, v in obj.items()}


# ── 请求矩阵（两侧逐条同序执行）───────────────────────────────────────────
# auth: none | token | bad_token
# body_only_compare: True ⇒ 只比形状/状态，不比文本（用于已登记的 D-01 降级）
def build_cases():
    return [
        # —— 登录面（public）——
        ("login_ok",              "/admin/v1/login", '{"username":"admin","password":"admin"}', "none", False),
        ("login_wrong_password",  "/admin/v1/login", '{"username":"admin","password":"wrong"}', "none", False),
        ("login_unknown_user",    "/admin/v1/login", '{"username":"nosuchuser","password":"x"}', "none", False),
        ("login_missing_field",   "/admin/v1/login", '{}', "none", True),
        # ↓ 「400 文本」类：B 侧 go-zero 报 `field "x" is not set`，C 侧报 Spring/Hibernate 消息
        #   ⇒ 属 **已登记降级 D-01**（形状一致：400 + text/plain + nosniff + 尾随 \n）⇒ 只比形状
        ("login_empty_body",      "/admin/v1/login", '', "none", True),
        ("login_non_json_ct",     "/admin/v1/login", '{"username":"admin","password":"admin"}',
                                  "none", True, {"Content-Type": "text/plain"}),
        # —— 鉴权面（protected）——
        ("getself_no_auth",       "/admin/v1/getself", '{}', "none", False),
        ("getself_bad_token",     "/admin/v1/getself", '{}', "bad_token", False),
        ("getself_ok",            "/admin/v1/getself", '{}', "token", False),
        ("getuserlist_ok",        "/admin/v1/getuserlist", '{"page":1,"page_size":10,"keyword":""}', "token", False),
        ("getuserlist_missing_keyword", "/admin/v1/getuserlist", '{"page":1,"page_size":10}', "token", True),
        ("getuser_ok",            "/admin/v1/getuser", '{"uid":"1000000000000000001"}', "token", False),
        # —— 路由面 ——
        ("unknown_path_404",      "/admin/v1/not-exist", '{}', "none", False),
        ("method_not_allowed",    "/admin/v1/login", None, "none", False, None, "GET"),
        # —— 邀请码 / 创建管理员（含状态变更）——
        ("invite_create_ok",      "/admin/v1/invite/create", '{}', "token", False),
        ("create_admin_with_invite", "/admin/v1/create",
                                  '{"username":"e4admin","password":"E4passw0rd","email":"e4@example.com",'
                                  '"invite_code":"<INVITE>","extra_info":{"k":"e4"}}', "token", False),
        ("create_admin_reuse_invite", "/admin/v1/create",
                                     '{"username":"e4admin2","password":"E4passw0rd","email":"e4b@example.com",'
                                     '"invite_code":"<INVITE>","extra_info":{"k":"e4"}}', "token", False),
        ("create_admin_duplicate_username", "/admin/v1/create",
                                            '{"username":"e4admin","password":"E4passw0rd","email":"e4c@example.com",'
                                            '"invite_code":"<INVITE2>","extra_info":{"k":"e4"}}', "token", False),
        # —— 用户管理面 ——
        ("update_user_ok",        "/admin/v1/updateuser",
                                  '{"uid":"1000000000000000001","email":"e4upd@example.com"}', "token", True),
        ("ban_user_ok",           "/admin/v1/banuser", '{"uid":"1000000000000000001"}', "token", False),
        ("getuser_after_ban",     "/admin/v1/getuser", '{"uid":"1000000000000000001"}', "token", False),
        ("unban_user_ok",         "/admin/v1/unbanuser", '{"uid":"1000000000000000001"}', "token", False),
        ("reset_password_ok",     "/admin/v1/resetuserpassword", '{"uid":"1000000000000000001"}', "token", False),
        ("delete_user_ok",        "/admin/v1/delete", '{"uid":"1000000000000000001"}', "token", False),
        ("getuser_after_delete",  "/admin/v1/getuser", '{"uid":"1000000000000000001"}', "token", False),
        # —— 登出 + 黑名单 ——
        ("logout_ok",             "/admin/v1/logout", '{}', "token", False),
        ("getself_after_logout",  "/admin/v1/getself", '{}', "token", False),
    ]


def run_side(base, label):
    """按序跑一遍矩阵，返回 {case: (status, headers, body)} 与 token。"""
    token = None
    invite = None
    results = {}
    for case in build_cases():
        name, path, body, auth, shape_only = case[0], case[1], case[2], case[3], case[4]
        headers = case[5] if len(case) > 5 else None
        method = case[6] if len(case) > 6 else "POST"
        if body and "<INVITE>" in body:
            if invite is None:
                continue
            body = body.replace("<INVITE>", invite).replace("<INVITE2>", invite)
        if headers is None and auth != "none":
            h = {"Content-Type": "application/json"}
            if auth == "token":
                h["Authorization"] = "Bearer " + (token or "")
            else:
                h["Authorization"] = "Bearer not-a-valid-token"
            headers = h
        status, hdrs, resp_body, interim = call(base, path, body, headers, method)
        results[name] = (status, hdrs, resp_body, interim)
        if name == "login_ok":
            try:
                token = json.loads(resp_body)["data"]["token"]
            except Exception:
                token = None
        if name == "invite_create_ok":
            try:
                d = json.loads(resp_body)["data"]
                invite = d.get("invite_code") or d.get("code") or d.get("inviteCode")
            except Exception:
                invite = None
        print(f"  [{label}] {name:32s} -> {status}", flush=True)
    return results, token, invite


# ── 比对 ──────────────────────────────────────────────────────────────────
def compare(b, c):
    rows = []
    for name, path, body, auth, shape_only in [(x[0], x[1], x[2], x[3], x[4]) for x in build_cases()]:
        if name not in b or name not in c:
            rows.append((name, "MISSING", "MISSING", "两侧都应出现该用例"))
            continue
        bs, bh, bb, bi = b[name]
        cs, ch, cb, ci = c[name]
        diffs = []
        if bs == 0 or cs == 0:
            diffs.append(f"⚠️ 请求失败（curl 报错 / 连接异常）B={bs} ↔ C={cs} ⇒ 本用例无有效读数")
        if bs != cs:
            diffs.append(f"状态码 B={bs} ↔ C={cs}")
        if bi != ci:
            diffs.append(f"1xx 中间响应 B={bi} ↔ C={ci}")
        if bh != ch:
            diffs.append(f"响应头 B={bh} ↔ C={ch}")
        if shape_only:
            # 「400 文本」类：比**形状**（状态 + 头 + body 是否文本/是否 JSON 信封），不比文本
            bs_text = "text" if (bh.get("content-type") or "").startswith("text/plain") else "json"
            cs_text = "text" if (ch.get("content-type") or "").startswith("text/plain") else "json"
            if bs_text != cs_text:
                diffs.append(f"body 形态 B={bs_text} ↔ C={cs_text}")
            else:
                diffs.append("__TEXT_ONLY__")
        else:
            # ⚠️ 顺序：**先 canon（JSON 排序）再 normalize** —— 反了会坏：
            #   normalize 会把 epoch 换成 `<EPOCH>` ⇒ JSON 变非法 ⇒ canon 解析失败退回原文
            nb, nc = normalize(canon_json(bb)), normalize(canon_json(cb))
            if nb != nc:
                diffs.append(f"body B={nb[:300]!r} ↔ C={nc[:300]!r}")
            else:
                ko_b, ko_c = key_order(bb), key_order(cb)
                if ko_b != ko_c:
                    diffs.append(f"__KEY_ORDER__ B={ko_b} ↔ C={ko_c}")
        rows.append((name, "OK" if not diffs else "DIFF", "; ".join(diffs), ""))
    return rows


def dump_state(psql, redis, label):
    """导出 DB 最终状态 + Redis 键（归一化后）。"""
    import subprocess
    out = {"db": {}, "redis": []}
    if psql:
        q = ("SELECT table_name FROM information_schema.tables WHERE table_schema='public' "
             "AND table_name IN ('admin_admin','admin_invite','users_user','users_user_avatar_history') "
             "ORDER BY table_name")
        tables = subprocess.run(["psql", psql, "-Atc", q], capture_output=True, text=True).stdout.split()
        for t in tables:
            rows = subprocess.run(
                ["psql", psql, "-Atc", f"SELECT * FROM {t}"], capture_output=True, text=True).stdout
            norm = []
            for line in rows.strip().splitlines():
                cells = [normalize(c) for c in line.split("|")]
                # bcrypt 的 salt 是随机的 ⇒ 两侧哈希值本就不同 ⇒ 归一化（非行为差异）
                for i, c in enumerate(cells):
                    if c.startswith("$2a$") or c.startswith("$2b$") or c.startswith("$2y$"):
                        cells[i] = "<BCRYPT>"
                norm.append("|".join(cells))
            out["db"][t] = sorted(norm)
    if redis:
        host, _, port = redis.partition(":")
        keys = subprocess.run(["redis-cli", "-h", host, "-p", port, "--scan"],
                              capture_output=True, text=True).stdout.split()
        for k in sorted(keys):
            typ = subprocess.run(["redis-cli", "-h", host, "-p", port, "TYPE", k],
                                 capture_output=True, text=True).stdout.strip()
            val = subprocess.run(["redis-cli", "-h", host, "-p", port, "GET", k],
                                 capture_output=True, text=True).stdout.strip()
            out["redis"].append((normalize(k), typ, normalize(val)))
    return out


def main():
    if not B_BASE or not C_BASE:
        print("用法：B_BASE=... C_BASE=... python3 tools/difftest/parity_admin.py", file=sys.stderr)
        return 2
    os.makedirs(OUT, exist_ok=True)

    print("=== B 侧跑矩阵 ===")
    b_res, b_token, b_invite = run_side(B_BASE, "B")
    print("=== C 侧跑矩阵 ===")
    c_res, c_token, c_invite = run_side(C_BASE, "C")

    rows = compare(b_res, c_res)
    same = [r for r in rows if r[1] == "OK"]
    text_only = [r for r in rows if r[1] == "DIFF" and r[2] == "__TEXT_ONLY__"]
    key_order_diff = [r for r in rows if r[1] == "DIFF" and r[2].startswith("__KEY_ORDER__")]
    diff = [r for r in rows if r[1] == "DIFF" and r[2] != "__TEXT_ONLY__"
            and not r[2].startswith("__KEY_ORDER__")]

    print("\n=== 状态比对 ===")
    b_state = dump_state(B_PSQL, B_REDIS, "B")
    c_state = dump_state(C_PSQL, C_REDIS, "C")
    db_same = b_state["db"] == c_state["db"]
    redis_same = b_state["redis"] == c_state["redis"]

    print("\n" + "=" * 78)
    print(f"用例总数 {len(rows)} · 完全一致 {len(same)} · 仅文本差异(D-01 已登记降级) {len(text_only)}"
          f" · 键序差异(表达层 · JSON 键序无语义) {len(key_order_diff)} · **行为差异 {len(diff)}**")
    print(f"DB 最终状态一致：{'✅' if db_same else '❌'}    Redis 状态一致：{'✅' if redis_same else '❌'}")
    print("=" * 78)
    for name, st, detail, _ in rows:
        mark = "✅" if st == "OK" else ("➖" if detail == "__TEXT_ONLY__"
                                          else ("🔤" if detail.startswith("__KEY_ORDER__") else "❌"))
        print(f"{mark} {name:32s} {st:6s} {'' if st == 'OK' else detail[:400]}")
    if not db_same:
        for t in sorted(set(b_state["db"]) | set(c_state["db"])):
            br, cr = b_state["db"].get(t, []), c_state["db"].get(t, [])
            if br != cr:
                print(f"\n--- DB 表 {t} 不一致 ---")
                print(f"  B({len(br)} 行): {br[:6]}")
                print(f"  C({len(cr)} 行): {cr[:6]}")
    if not redis_same:
        print(f"\n--- Redis 不一致 ---\n  B: {b_state['redis']}\n  C: {c_state['redis']}")

    with open(f"{OUT}/parity-report.json", "w") as f:
        json.dump({"cases": rows, "db_same": db_same, "redis_same": redis_same,
                   "b_state": b_state, "c_state": c_state}, f, ensure_ascii=False, indent=2)
    print(f"\n报告：{OUT}/parity-report.json")
    return 0 if (not diff and db_same and redis_same) else 1


if __name__ == "__main__":
    sys.exit(main())
