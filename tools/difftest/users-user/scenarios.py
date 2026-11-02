#!/usr/bin/env python3
"""
user 域**场景轮**（双侧）—— 超时 / gRPC deadline / 取消传播 / 恢复 / 外部依赖失败 / 恢复 / 并发。

⚠️ **为什么场景轮排在 HTTP + wire 两轮之后**：本轮会**停 / 挂起两侧的 Postgres**
   ⇒ ⇒ 前两轮的期望值不受影响。

🔴 **判据 = 两侧对称**（对拍的判据是「两侧一致」，不是「系统恢复」）：
   · 两侧**同样**失败 ⇒ 那是**系统级现象**（下游挂起 / 依赖不可用）
     ⇒ 两侧对称 ⇒ **不是 users-user 域的漂移**；
   · 但必须**如实报出** ⇒ 绝不让「两边都坏」变成一条无声的假绿。
   ⇒ 所以「恢复轮失败」**不置 FAILED**，但会进 `SCEN_WARN` 并在收尾摘要里显眼打出。

🔴 **故障注入必须两侧完全对称**：
   · 两侧**各自**的 PG 容器（`e4user-{b,c}-pg-$$`）⇒ 挂起 / 停的**只有自己那侧**
   · ⇒ ⇒ 两侧走**完全同形**的路径 ⇒ 比出来的差异才是**行为差异**。
   ⚠️ **绝不去碰共享栈的 `Sea-postgres`**（别的 lane 在用）。

用法：
    python3 scenarios.py --b-http http://127.0.0.1:18301 --c-http http://127.0.0.1:18304 \\
        --b-grpc 18302 --c-grpc 18305 --b-pg <容器> --c-pg <容器> \\
        --probe-cp "<classpath>" --probe-classes <目录> --username <前缀>
退出码：0 = 场景轮全绿 · 1 = 有行为差异 · 2 = 环境问题
"""
import argparse
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import compare  # noqa: E402  ⇒ 复用同一套 call / norm / bizcode（口径不许分叉）

# ══════════════════════════ 阶段开关（可单独跑某一段）══════════════════════════

STAGE_TIMEOUT = True       # 超时（HTTP 层出口）
STAGE_DEADLINE = True      # gRPC deadline（服务端预算先到）
STAGE_CANCEL = True        # gRPC 取消传播
STAGE_DEP_DOWN = True      # 外部依赖失败（PG 停）
STAGE_CONCURRENT = True    # 并发

# 恢复轮的「稳定态」判据：**连续** N 轮两侧都正常（下游刚回来时处于过渡态）
RECOVER_STREAK = 3
RECOVER_BUDGET = 90

FAILED = 0
WARN = []

# ⚠️ **按容器记库名**（两侧库名不同 ⇒ 查错库 ⇒ 读数是假的）
PG_DB_BY_CONTAINER = {}


def say(msg=""):
    print(msg, flush=True)


def warn(msg):
    WARN.append(msg)
    say("      ⚠️ %s" % msg)


def fail(msg):
    global FAILED
    FAILED += 1
    say("      ❌ %s" % msg)


# ══════════════════════════ 基础设施 ═══════════════════════════════════════════

def docker(*args, **kw):
    return subprocess.run(["docker"] + list(args), capture_output=True, text=True, **kw)


def pg_exec(container, sql):
    """在指定 PG 容器里执行 SQL ⇒ 返回 stdout（去空白）。

    ⚠️ **两侧的库名不同**（`diff_b` / `diff_c`）⇒ ⇒ 必须**按容器**取库名，
       否则会在 B 侧容器上查 C 侧的库名 ⇒ 查不到 ⇒ 读数变成假的。
    """
    db = PG_DB_BY_CONTAINER.get(container, PG_DB)
    r = docker("exec", container, "psql", "-U", PG_USER, "-d", db, "-tAc", sql)
    return r.stdout.strip()


def pg_count(container, sql):
    out = pg_exec(container, sql)
    return out if out else "ERR"


def wire_probe(host, port, out_file, username, extra_args):
    """跑 gRPC wire 探针 ⇒ 返回 (case_name -> (status, payload), meta行)。"""
    cmd = ["java", "-cp", "%s:%s" % (PROBE_CP, PROBE_CLASSES),
           "com.rag.user.parity.UserWireProbe", host, str(port), out_file, username] + extra_args
    # ⚠️ **必须给子进程加超时**：探针万一挂住 ⇒ 整个场景轮就跟着挂 ⇒ 读数变成「等超时」
    #    ⇒ ⇒ 宁可抛异常（= 环境问题，exit 2），也不要无声地等。
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        raise RuntimeError("wire 探针 120s 未退出（挂住）：%s" % " ".join(cmd))
    if r.returncode != 0:
        raise RuntimeError("wire 探针执行失败（rc=%s）：%s" % (r.returncode, r.stderr[-800:]))
    cases, meta = {}, {}
    with open(out_file, "r", encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("|")
            if parts[0] == "CASE" and len(parts) >= 4:
                cases[parts[1]] = (parts[2], "|".join(parts[3:]))
            elif parts[0] == "META" and len(parts) >= 2:
                meta[parts[1]] = "|".join(parts[2:])
    return cases, meta


def http(base, method, path, body=None, token=None):
    return compare.call(base, method, path, body, token)


def http_timed(base, method, path, body=None, token=None):
    """⇒ (status, content_type, body, elapsed_seconds)

    ⚠️ **为什么要单独测耗时**：超时场景下**光看状态码不够** ——
       「C 侧 503」有两种完全不同的成因：
       ① **HTTP 中间件的 3s 预算**兜底（⇒ worker 还在跑）⇒ 503；
       ② **内层 deadline（2s）先把 SQL 取消了** ⇒ 领域层拿到 DEADLINE_EXCEEDED
          ⇒ 映射成 1015 ⇒ **200 + 1015 @ ~2s**（admin 域修完后的读数就是这个）。
       ⇒ ⇒ **耗时是判别这两者的唯一读数**（3s vs 2s）。
    """
    import tempfile
    hdr = tempfile.NamedTemporaryFile(delete=False)
    bdy = tempfile.NamedTemporaryFile(delete=False)
    cmd = ["curl", "-s", "-D", hdr.name, "-o", bdy.name,
           "-w", "%{http_code}|%{content_type}|%{time_total}", "-m", "40",
           "-X", method, base + path, "-H", "Content-Type: application/json"]
    if body is not None:
        cmd += ["--data-binary", body]
    if token:
        cmd += ["-H", "Authorization: Bearer " + token]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout.strip()
    code, _, rest = out.partition("|")
    ctype, _, elapsed = rest.partition("|")
    try:
        with open(bdy.name, "r", encoding="utf-8", errors="replace") as fh:
            raw = fh.read()
    except Exception:  # noqa: BLE001
        raw = ""
    os.unlink(hdr.name)
    os.unlink(bdy.name)
    try:
        return int(code), ctype or "", raw, float(elapsed)
    except ValueError:
        return 0, "", raw, -1.0


def biz_of(body):
    return compare.bizcode(body)


def norm_of(body):
    return compare.norm(body)


def keyorder(body):
    return compare.keyorder(body)


# ══════════════════════════ 场景工具 ═══════════════════════════════════════════

def both_get():
    """两侧各打一次 /user/v1/get（带各自 token）⇒ 返回 (B_raw, C_raw, B_code, C_code)。"""
    b = http(B_HTTP, "POST", "/user/v1/get", "{}", B_TOKEN)
    c = http(C_HTTP, "POST", "/user/v1/get", "{}", C_TOKEN)
    return b, c, biz_of(b[2]), biz_of(c[2])


def wait_both_recovered(tag):
    """轮询到**两侧连续 N 轮**都正常 ⇒ 返回 True/False（**不置 FAILED**，只 warn）。"""
    waited = 0
    streak = 0
    last = ("?", "?")
    while waited < RECOVER_BUDGET:
        _, _, bc, cc = both_get()
        last = (bc, cc)
        if bc == "200" and cc == "200":
            streak += 1
            if streak >= RECOVER_STREAK:
                say("      ✅ %s：两侧**连续 %d 轮**都回 200（用时 %ds）⇒ 已进入**稳定态**"
                    % (tag, RECOVER_STREAK, waited))
                return True
        else:
            streak = 0
        time.sleep(1)
        waited += 1
    warn("%s：%ds 内两侧**没有连续 %d 轮**都回 200（C=%s · B=%s）⇒ **两侧对称** ⇒ 系统级现象，"
         "非本域漂移" % (tag, RECOVER_BUDGET, RECOVER_STREAK, last[1], last[0]))
    return False


def compare_pair(name, b, c, note=""):
    """两侧 HTTP 响应对比：结构级（归一化 + 键序）⇒ 相同即绿；不同即 ❌ 真差异。"""
    bs, bct, bb = b
    cs, cct, cb = c
    nb, nc = norm_of(bb), norm_of(cb)
    byte_ok = (bs == cs) and (nb == nc) and (bct == cct)
    struct_ok = byte_ok or ((bs == cs) and (keyorder(nb) == keyorder(nc)))
    if struct_ok:
        say("      ✅ %s%s  两侧一致（HTTP B=%s C=%s · 业务码 B=%s C=%s）"
            % (name, note, bs, cs, biz_of(bb), biz_of(cb)))
        if not byte_ok:
            warn("%s：仅 JSON 键序不同（DRIFT-USER-PARITY-003 · 共享层待收口）" % name)
        return True
    fail("%s：两侧不一致（HTTP B=%s C=%s · 业务码 B=%s C=%s）" % (name, bs, cs, biz_of(bb), biz_of(cb)))
    say("         B body: %s" % nb[:300])
    say("         C body: %s" % nc[:300])
    return False


def login_tokens():
    """两侧各注册 + 登录一次拿自己的 token ⇒ 写全局 B_TOKEN / C_TOKEN。

    ⚠️ **必须先注册**：场景轮用的是**独立**用户名（`e4us*`）⇒ HTTP 矩阵里那个
       （`e4u*`）**不存在** ⇒ 直接登录会拿到「用户不存在」⇒ **取不到 token**
       ⇒ ⇒ 场景轮第一步就 die。
    """
    global B_TOKEN, C_TOKEN
    reg = '{"username":"%s","password":"pw123456","email":"%s@example.test"}' % (USERNAME, USERNAME)
    for base in (B_HTTP, C_HTTP):
        http(base, "POST", "/user/v1/register", reg)
    body = '{"username":"%s","password":"pw123456"}' % USERNAME
    bs, _, bb = http(B_HTTP, "POST", "/user/v1/login", body)
    cs, _, cb = http(C_HTTP, "POST", "/user/v1/login", body)
    B_TOKEN = ""
    C_TOKEN = ""
    try:
        import json
        B_TOKEN = json.loads(bb)["data"]["token"]
        C_TOKEN = json.loads(cb)["data"]["token"]
    except Exception:  # noqa: BLE001
        pass
    if not B_TOKEN or not C_TOKEN:
        raise SystemExit("!! 取 token 失败（B=%s C=%s）⇒ 场景轮无法进行" % (bs, cs))
    say("   ✓ 两侧各注册 + 登录一次 ⇒ 拿到各自的 token")


# ══════════════════════════ 阶段 ═════════════════════════════════════════════

def stage_timeout():
    """**超时**：挂起两侧 PG ⇒ HTTP 读路径挂住 ⇒ 共享层 http-timeout(3s) 预算耗尽。"""
    say("   · 阶段B【超时】：挂起两侧 PG ⇒ 打 HTTP 读路径 ⇒ 看预算的对外出口（**含耗时**）")
    docker("pause", B_PG)
    docker("pause", C_PG)
    time.sleep(1)
    b = http_timed(B_HTTP, "POST", "/user/v1/get", "{}", B_TOKEN)
    c = http_timed(C_HTTP, "POST", "/user/v1/get", "{}", C_TOKEN)
    compare_pair("scn_http_timeout", b[:3], c[:3])
    say("      ℹ️ **耗时**：B=%.2fs · C=%.2fs  ← 判别「3s HTTP 预算兜底」vs「2s 内层 deadline 先取消 SQL」的唯一读数"
        % (b[3], c[3]))
    say("      ℹ️ 两侧 content-type：B=%r · C=%r" % (b[1], c[1]))


def stage_deadline():
    """**gRPC deadline**：PG 仍挂起 ⇒ 服务端工作超过自己的 rpc-timeout(2s) 预算。"""
    say("   · 阶段C【gRPC deadline】：PG 仍挂起 ⇒ 服务端预算(2s) vs 客户端 deadline(20s)")
    cases_b, meta_b = wire_probe("127.0.0.1", B_GRPC, WORK + "/dl-b.txt", USERNAME + "_dl",
                                  ["--only=scn_get_user_missing", "--deadline=20000"])
    cases_c, meta_c = wire_probe("127.0.0.1", C_GRPC, WORK + "/dl-c.txt", USERNAME + "_dl",
                                  ["--only=scn_get_user_missing", "--deadline=20000"])
    kb = cases_b.get("scn_get_user_missing", ("?", ""))
    kc = cases_c.get("scn_get_user_missing", ("?", ""))
    say("      B: %s | %s   （%s）" % (kb[0], kb[1], meta_b.get("scn_get_user_missing", "")))
    say("      C: %s | %s   （%s）" % (kc[0], kc[1], meta_c.get("scn_get_user_missing", "")))
    if kb[0] == kc[0]:
        say("      ✅ 两侧 gRPC 状态码一致（%s）" % kb[0])
    else:
        fail("gRPC deadline 场景两侧状态码不一致：B=%s · C=%s" % (kb[0], kc[0]))


def stage_cancel():
    """**gRPC 取消传播**：PG 挂起 ⇒ 发写路径调用 ⇒ 客户端中途 cancel ⇒ 看副作用是否落库。"""
    say("   · 阶段D【取消传播】：PG 挂起 ⇒ 发写路径调用 ⇒ 1s 后客户端取消 ⇒ 看副作用")
    name = USERNAME + "_cancel"
    cases_b, meta_b = wire_probe("127.0.0.1", B_GRPC, WORK + "/cx-b.txt", name,
                                  ["--only=scn_register", "--deadline=60000", "--cancel-after=1000"])
    cases_c, meta_c = wire_probe("127.0.0.1", C_GRPC, WORK + "/cx-c.txt", name,
                                  ["--only=scn_register", "--deadline=60000", "--cancel-after=1000"])
    kb = cases_b.get("scn_register", ("?", ""))
    kc = cases_c.get("scn_register", ("?", ""))
    say("      B: %s | %s   （%s）" % (kb[0], kb[1], meta_b.get("scn_register", "")))
    say("      C: %s | %s   （%s）" % (kc[0], kc[1], meta_c.get("scn_register", "")))
    if kb[0] == kc[0]:
        say("      ✅ 两侧取消后的 gRPC 状态码一致（%s）" % kb[0])
    else:
        fail("取消场景两侧状态码不一致：B=%s · C=%s" % (kb[0], kc[0]))

    # ⇒ 放行 PG，让被卡住的那条 SQL 有机会完成 ⇒ **看副作用到底落不落**
    docker("unpause", B_PG)
    docker("unpause", C_PG)
    time.sleep(3)
    sql = "SELECT count(*) FROM users_user WHERE username='%s'" % name
    cb = pg_count(B_PG, sql)
    cc = pg_count(C_PG, sql)
    say("      取消后的副作用（username=%s 的行数）：B=%s · C=%s" % (name, cb, cc))
    if cb == cc:
        say("      ✅ 两侧副作用**一致**（%s）⇒ 取消传播行为等价" % cb)
        if cb == "1":
            warn("两侧在客户端取消后**仍然提交了写副作用**（行数=1）⇒ "
                 "「取消 ⇒ 服务端停止工作」在两侧**都不成立** ⇒ 属**继承自 B 侧**的行为")
    else:
        fail("取消传播后副作用不一致：B=%s 行 · C=%s 行" % (cb, cc))

    # ── 判别性变体：**在服务端自己的 2s 预算之后**才取消 ────────────────────────
    # ⚠️ **为什么要有这一条**：上面的变体在 1s 取消 ⇒ 此时**两侧的 2s 服务端预算都还没到**
    #    ⇒ ⇒ 两侧的差异究竟是「客户端取消 ⇒ 服务端停不停手」，还是「2s 预算 ⇒ SQL 被取消」？
    #    ⇒ **分不开** ⇒ ⇒ 再跑一条 **cancel-after=5000ms**（必定晚于 2s 预算）：
    #    ⇒ 若此时两侧仍是 B=0 / C=1 ⇒ ⇒ 差异来自「**服务端预算到点后有没有把在途 SQL 取消掉**」
    #    ⇒ ⇒ **落点在共享层**（与 gRPC 路径的 deadline 绑定有关），**不是**「客户端取消」本身。
    say("      · 判别性变体：cancel-after=5000ms（**必定晚于服务端 2s 预算**）⇒ 分离两种成因")
    name2 = USERNAME + "_cancel2"
    # ⚠️ **必须先重新挂起 PG** ⇒ 否则这个 register 会**立刻成功**（PG 是活的）
    #    ⇒ ⇒ 5s 后的取消就是个空操作 ⇒ ⇒ 变体**分不出**任何东西 ⇒ 变体必须是「PG 挂起时发」。
    docker("pause", B_PG)
    docker("pause", C_PG)
    time.sleep(1)
    cases_b2, meta_b2 = wire_probe("127.0.0.1", B_GRPC, WORK + "/cx2-b.txt", name2,
                                    ["--only=scn_register", "--deadline=60000", "--cancel-after=5000"])
    cases_c2, meta_c2 = wire_probe("127.0.0.1", C_GRPC, WORK + "/cx2-c.txt", name2,
                                    ["--only=scn_register", "--deadline=60000", "--cancel-after=5000"])
    kb2 = cases_b2.get("scn_register", ("?", ""))
    kc2 = cases_c2.get("scn_register", ("?", ""))
    say("      B: %s | %s   （%s）" % (kb2[0], kb2[1], meta_b2.get("scn_register", "")))
    say("      C: %s | %s   （%s）" % (kc2[0], kc2[1], meta_c2.get("scn_register", "")))
    # ⇒ 放行 PG，让在途 SQL 有机会完成 ⇒ 看副作用
    docker("unpause", B_PG)
    docker("unpause", C_PG)
    time.sleep(3)
    sql2 = "SELECT count(*) FROM users_user WHERE username='%s'" % name2
    cb2 = pg_count(B_PG, sql2)
    cc2 = pg_count(C_PG, sql2)
    say("      变体副作用（username=%s 的行数）：B=%s · C=%s" % (name2, cb2, cc2))
    if cb2 == cc2:
        say("      ✅ 变体两侧副作用一致（%s）" % cb2)
    else:
        fail("变体（cancel-after=5s）副作用不一致：B=%s 行 · C=%s 行" % (cb2, cc2))


def stage_recover_after_pause():
    say("   · 阶段E【恢复】：PG 已放行 ⇒ 轮询到两侧**连续 %d 轮**都回 200" % RECOVER_STREAK)
    wait_both_recovered("恢复轮（放行后）")
    b, c, _, _ = both_get()
    compare_pair("scn_recover_after_pause", b, c)


def stage_dep_down():
    """**外部依赖失败**：**停**两侧 PG ⇒ 连接被拒 ⇒ 快失败。"""
    say("   · 阶段F【外部依赖失败】：停两侧 PG ⇒ 连接被拒 ⇒ 看两侧对外出口")
    docker("stop", B_PG)
    docker("stop", C_PG)
    time.sleep(1)
    for rnd in (1, 2, 3):
        b, c, bc, cc = both_get()
        say("      第 %d 轮：HTTP B=%s C=%s · 业务码 B=%s C=%s" % (rnd, b[0], c[0], bc, cc))
        if rnd == 1:
            compare_pair("scn_dep_down", b, c)
        elif (b[0], norm_of(b[2])) != (c[0], norm_of(c[2])):
            fail("外部依赖失败场景第 %d 轮两侧不一致" % rnd)
            say("         B body: %s" % norm_of(b[2])[:300])
            say("         C body: %s" % norm_of(c[2])[:300])


def stage_recover_after_down():
    say("   · 阶段G【恢复】：PG 重新 start ⇒ 轮询到两侧**连续 %d 轮**都回 200" % RECOVER_STREAK)
    docker("start", B_PG)
    docker("start", C_PG)
    time.sleep(2)
    wait_both_recovered("恢复轮（重启后）")
    b, c, _, _ = both_get()
    compare_pair("scn_recover_after_restart", b, c)


def stage_concurrent():
    """**并发**：两侧各 8 路并发 register **同一用户名** ⇒ 码集合相同 + 各自恰好 1 行。"""
    say("   · 阶段H【并发】：两侧各 8 路并发 register 同一用户名 ⇒ 码集合 + 行数")
    name = USERNAME + "_cc"
    body = '{"username":"%s","password":"pw123456","email":"%s@example.test"}' % (name, name)

    def burst(base, out):
        # ⚠️ **每个 curl 必须写自己的 body 文件** ⇒ 共用一个会被并发覆盖
        #    ⇒ 读出来的 body 与 http code **可能来自不同的那次请求** ⇒ 读数是假的。
        procs = []
        bodies = []
        for i in range(8):
            bf = "%s.body.%d" % (out, i)
            bodies.append(bf)
            procs.append(subprocess.Popen(
                ["curl", "-s", "-o", bf, "-w", "%{http_code}|%{content_type}",
                 "-m", "30", "-X", "POST", base + "/user/v1/register",
                 "-H", "Content-Type: application/json", "--data-binary", body],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True))
        codes = []
        for i, p in enumerate(procs):
            out_s = p.communicate()[0].strip()
            code = out_s.split("|")[0]
            try:
                with open(bodies[i], "r", encoding="utf-8", errors="replace") as fh:
                    bcode = biz_of(fh.read())
            except Exception:  # noqa: BLE001
                bcode = "-"
            codes.append("%s/%s" % (code, bcode))
        return codes

    cb = burst(B_HTTP, WORK + "/cc-b")
    cc = burst(C_HTTP, WORK + "/cc-c")
    set_b = sorted(set(cb))
    set_c = sorted(set(cc))
    say("      响应集合：B=[%s] · C=[%s]" % (",".join(set_b), ",".join(set_c)))
    sql = "SELECT count(*) FROM users_user WHERE username='%s'" % name
    rb = pg_count(B_PG, sql)
    rc = pg_count(C_PG, sql)
    say("      行数：B=%s · C=%s" % (rb, rc))
    if set_b != set_c:
        fail("并发场景两侧响应集合不同：B=[%s] · C=[%s]" % (",".join(set_b), ",".join(set_c)))
    else:
        say("      ✅ 两侧响应集合相同")
    if rb != rc:
        fail("并发场景两侧行数不同：B=%s · C=%s" % (rb, rc))
    elif rb != "1":
        fail("并发场景行数不是 1（B=%s · C=%s）⇒ 并发语义不一致" % (rb, rc))
    else:
        say("      ✅ 两侧各自恰好 1 行 ⇒ 并发语义一致")


# ══════════════════════════ 主流程 ═════════════════════════════════════════════

def main():
    global B_HTTP, C_HTTP, B_GRPC, C_GRPC, B_PG, C_PG, PG_USER, PG_DB, WORK
    global PG_DB_BY_CONTAINER
    global PROBE_CP, PROBE_CLASSES, USERNAME

    ap = argparse.ArgumentParser()
    ap.add_argument("--b-http", required=True)
    ap.add_argument("--c-http", required=True)
    ap.add_argument("--b-grpc", type=int, required=True)
    ap.add_argument("--c-grpc", type=int, required=True)
    ap.add_argument("--b-pg", required=True)
    ap.add_argument("--c-pg", required=True)
    ap.add_argument("--pg-user", default="sea")
    ap.add_argument("--pg-db", default="diff_b")
    ap.add_argument("--c-pg-db", default="diff_c")
    ap.add_argument("--probe-cp", required=True)
    ap.add_argument("--probe-classes", required=True)
    ap.add_argument("--username", required=True)
    ap.add_argument("--work", required=True)
    args = ap.parse_args()

    B_HTTP, C_HTTP = args.b_http, args.c_http
    B_GRPC, C_GRPC = args.b_grpc, args.c_grpc
    B_PG, C_PG = args.b_pg, args.c_pg
    PG_USER = args.pg_user
    PG_DB = args.pg_db
    # ⚠️ **按容器记库名**（两侧库名不同 ⇒ 查错库 ⇒ 读数是假的）
    PG_DB_BY_CONTAINER[B_PG] = args.pg_db
    PG_DB_BY_CONTAINER[C_PG] = args.c_pg_db
    PROBE_CP, PROBE_CLASSES = args.probe_cp, args.probe_classes
    USERNAME = args.username
    WORK = args.work

    compare.B = B_HTTP
    compare.C = C_HTTP

    say("== 场景轮（双侧对称）==")
    say("   B: HTTP=%s gRPC=%d PG=%s ｜ C: HTTP=%s gRPC=%d PG=%s"
        % (B_HTTP, B_GRPC, B_PG, C_HTTP, C_GRPC, C_PG))

    # 阶段 0：登录拿 token（**在注入故障之前**）
    login_tokens()

    if STAGE_TIMEOUT:
        stage_timeout()
    if STAGE_DEADLINE:
        stage_deadline()
    if STAGE_CANCEL:
        stage_cancel()
    else:
        docker("unpause", B_PG)
        docker("unpause", C_PG)
    stage_recover_after_pause()
    if STAGE_DEP_DOWN:
        stage_dep_down()
        stage_recover_after_down()
    if STAGE_CONCURRENT:
        stage_concurrent()

    say("")
    if WARN:
        say("—— 场景轮摘要：❌ 真差异 %d ｜ ⚠️ 对称异常/已知现象 %d ——" % (FAILED, len(WARN)))
        for w in WARN:
            say("   ⚠️ %s" % w)
    else:
        say("—— 场景轮摘要：❌ 真差异 %d ｜ ⚠️ 对称异常/已知现象 0 ——" % FAILED)
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
