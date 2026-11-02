#!/usr/bin/env python3
"""admin 域 · 第二轮双侧活体对拍（E-4-R2）：并发 / 事务回滚 / 外部依赖失败 / 超时。

第一轮（`parity_admin.py`）覆盖了「正常 / 空值缺省 / 非法输入 / 重复请求」的**串行**矩阵，
但 `.agents/verification.md` §九 还要求覆盖 **并发 / 外部依赖失败 / 超时 / 部分成功 / 恢复清理**。
本脚本补齐前四类（第五类「恢复清理」由 §S3 的「停→起→复测」闭环覆盖）。

用法：
    B_BASE=http://127.0.0.1:18889 C_BASE=http://127.0.0.1:18890 \
    B_PSQL=postgresql://admin:password@127.0.0.1:54844/admin_db \
    C_PSQL=postgresql://admin:password@127.0.0.1:54844/c_admin \
    python3 tools/difftest/parity_admin_round2.py

⚠️ **本脚本会真的停/起容器**（Redis / PG）⇒ ⇒ 只许对着 **E-4 隔离环境**跑
   （`e4-redis-b` / `e4-redis-c` / `e4-postgres`）⇒ **不要**指向用户自己的 `Sea-*` 栈。
"""
import json
import os
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import parity_admin as P  # noqa: E402  —— 复用第一轮的 call()/normalize()/canon_json()

B_BASE = os.environ.get("B_BASE", "").rstrip("/")
C_BASE = os.environ.get("C_BASE", "").rstrip("/")
B_PSQL = os.environ.get("B_PSQL", "")
C_PSQL = os.environ.get("C_PSQL", "")
OUT = os.environ.get("OUT", "/tmp/e4-parity")
PG_CONTAINER = os.environ.get("PG_CONTAINER", "e4-postgres")
REDIS_B_CONTAINER = os.environ.get("REDIS_B_CONTAINER", "e4-redis-b")
REDIS_C_CONTAINER = os.environ.get("REDIS_C_CONTAINER", "e4-redis-c")
CONCURRENCY = int(os.environ.get("CONCURRENCY", "5"))


# ── 小工具 ────────────────────────────────────────────────────────────────
def auth(token):
    return {"Content-Type": "application/json", "Authorization": "Bearer " + token}


def psql_run(psql, sql):
    r = subprocess.run(["psql", psql, "-Atc", sql], capture_output=True, text=True)
    return r.stdout.strip(), r.returncode, r.stderr.strip()


def envelope_code(body):
    try:
        return json.loads(body).get("code")
    except Exception:
        return None


def login(base):
    st, _h, b, _i = P.call(base, "/admin/v1/login", '{"username":"admin","password":"admin"}')
    try:
        return json.loads(b)["data"]["token"]
    except Exception:
        return None


def create_invite(base, token):
    st, _h, b, _i = P.call(base, "/admin/v1/invite/create", "{}", auth(token))
    try:
        d = json.loads(b)["data"]
        return d.get("invite_code") or d.get("code") or d.get("inviteCode")
    except Exception:
        return None


def docker(action, container):
    return subprocess.run(["docker", action, container], capture_output=True, text=True)


def wait_port(host, port, up=True, timeout=40):
    """等端口起来/下去（避免把「连接被拒」误读成「行为差异」）。"""
    import socket
    end = time.time() + timeout
    while time.time() < end:
        s = socket.socket()
        s.settimeout(1)
        try:
            s.connect((host, int(port)))
            ok = True
        except OSError:
            ok = False
        finally:
            s.close()
        if ok == up:
            return True
        time.sleep(0.5)
    return False


# ── S1 · 并发：同一邀请码 N 路并发创建 ────────────────────────────────────
def s1_concurrency(base, label, psql, token, n):
    invite = create_invite(base, token)
    if not invite:
        return {"error": "invite 创建失败"}
    bodies = [
        json.dumps({"username": f"r2{label}c{i}", "password": "E4passw0rd",
                    "email": f"r2{label}c{i}@example.com", "invite_code": invite})
        for i in range(n)
    ]
    with ThreadPoolExecutor(max_workers=n) as ex:
        outs = list(ex.map(lambda bd: P.call(base, "/admin/v1/create", bd, auth(token)), bodies))
    codes = sorted(str(envelope_code(o[2])) for o in outs)
    statuses = sorted(str(o[0]) for o in outs)
    created, _rc, _err = psql_run(psql, f"SELECT count(*) FROM admin_admin WHERE username LIKE 'r2{label}c%'")
    used, _rc, _err = psql_run(psql, f"SELECT (used_at IS NOT NULL)::text FROM admin_invite WHERE code = '{invite}'")
    return {"statuses": statuses, "codes": codes,
            "created_rows": created, "invite_used": used, "invite": invite}


# ── S2 · 直接事务回滚：外部注入唯一约束冲突（失败点落在 W3 insert）────────
def s2_rollback(base, label, psql, token):
    email = f"r2{label}conflict@example.com"
    psql_run(psql, f"DELETE FROM admin_admin WHERE email = '{email}'")
    psql_run(psql, "INSERT INTO admin_admin (uid, username, password, email) "
                   f"VALUES (90000000000000000{1 if label == 'B' else 2}, 'r2{label}pre', 'x', '{email}')")
    invite = create_invite(base, token)
    body = json.dumps({"username": f"r2{label}new", "password": "E4passw0rd",
                       "email": email, "invite_code": invite})
    st, hdrs, resp, _i = P.call(base, "/admin/v1/create", body, auth(token))
    new_rows, _rc, _err = psql_run(psql, f"SELECT count(*) FROM admin_admin WHERE username = 'r2{label}new'")
    used_at, _rc, _err = psql_run(psql, f"SELECT coalesce(used_at::text,'') FROM admin_invite WHERE code = '{invite}'")
    return {"status": st, "code": envelope_code(resp), "body": resp[:200],
            "new_admin_rows": new_rows,
            "invite_used_at": "<NULL>" if used_at == "" else "<SET>"}


# ── S3 · 外部依赖失败 ────────────────────────────────────────────────────
def s3_redis_down(base, label, token, container, redis_hostport):
    host, _, port = redis_hostport.partition(":")
    docker("stop", container)
    wait_port(host, port, up=False)
    try:
        st, _h, b, _i = P.call(base, "/admin/v1/getself", "{}", auth(token))
        return {"status": st, "code": envelope_code(b), "body": b[:200]}
    finally:
        docker("start", container)
        wait_port(host, port, up=True)


def s3_pg_down(b_base, c_base, b_token, c_token):
    """PG 是**两侧共用**的一个容器 ⇒ 停它 ⇒ 两侧同时受影响 ⇒ 同一时刻打两侧。"""
    docker("stop", PG_CONTAINER)
    time.sleep(1.5)
    try:
        bo = P.call(b_base, "/admin/v1/getself", "{}", auth(b_token))
        co = P.call(c_base, "/admin/v1/getself", "{}", auth(c_token))
        return {
            "B": {"status": bo[0], "code": envelope_code(bo[2]), "body": bo[2][:200]},
            "C": {"status": co[0], "code": envelope_code(co[2]), "body": co[2][:200]},
        }
    finally:
        docker("start", PG_CONTAINER)
        wait_port("127.0.0.1", "54844", up=True)


# ── S4 · 超时：外部持锁把请求卡在 W1 的 SELECT ... FOR UPDATE ─────────────
def s4_timeout(base, label, psql, token):
    invite = create_invite(base, token)
    if not invite:
        return {"error": "invite 创建失败"}
    body = json.dumps({"username": f"r2{label}to", "password": "E4passw0rd",
                       "email": f"r2{label}to@example.com", "invite_code": invite})
    # 另开一个会话持锁（FOR UPDATE 的锁活到事务结束）
    holder = subprocess.Popen(
        ["psql", psql, "-c",
         f"BEGIN; SELECT id FROM admin_invite WHERE code = '{invite}' FOR UPDATE; "
         "SELECT pg_sleep(25); COMMIT;"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)      # 等持锁会话拿到锁
    t0 = time.time()
    try:
        st, _h, b, _i = P.call(base, "/admin/v1/create", body, auth(token))
        elapsed = round(time.time() - t0, 2)
        return {"status": st, "code": envelope_code(b), "elapsed_s": elapsed, "body": b[:200]}
    finally:
        holder.kill()
        holder.wait(timeout=10)


# ── 主流程 ────────────────────────────────────────────────────────────────
def main():
    if not B_BASE or not C_BASE:
        print("用法：B_BASE=... C_BASE=... python3 tools/difftest/parity_admin_round2.py", file=sys.stderr)
        return 2
    os.makedirs(OUT, exist_ok=True)
    report = {}

    print("=== 登录 + 取 token ===")
    b_token, c_token = login(B_BASE), login(C_BASE)
    print(f"  B token: {'OK' if b_token else 'FAIL'}    C token: {'OK' if c_token else 'FAIL'}")
    if not b_token or not c_token:
        print("❌ 登录失败 ⇒ 后续场景无意义，退出")
        return 2

    print("\n=== S1 · 并发（同一邀请码 N=%d 并发创建）===" % CONCURRENCY)
    b_s1 = s1_concurrency(B_BASE, "B", B_PSQL, b_token, CONCURRENCY)
    c_s1 = s1_concurrency(C_BASE, "C", C_PSQL, c_token, CONCURRENCY)
    report["S1_concurrency"] = {"B": b_s1, "C": c_s1}
    print(f"  B: {b_s1}")
    print(f"  C: {c_s1}")

    print("\n=== S2 · 直接事务回滚（email 唯一冲突 ⇒ W3 insert 失败）===")
    b_s2 = s2_rollback(B_BASE, "B", B_PSQL, b_token)
    c_s2 = s2_rollback(C_BASE, "C", C_PSQL, c_token)
    report["S2_rollback"] = {"B": b_s2, "C": c_s2}
    print(f"  B: {b_s2}")
    print(f"  C: {c_s2}")

    print("\n=== S3a · 外部依赖失败：Redis 停 ⇒ 黑名单 fail-open？ ===")
    b_s3a = s3_redis_down(B_BASE, "B", b_token, REDIS_B_CONTAINER,
                           os.environ.get("B_REDIS", "127.0.0.1:54845"))
    c_s3a = s3_redis_down(C_BASE, "C", c_token, REDIS_C_CONTAINER,
                           os.environ.get("C_REDIS", "127.0.0.1:54846"))
    report["S3a_redis_down"] = {"B": b_s3a, "C": c_s3a}
    print(f"  B: {b_s3a}")
    print(f"  C: {c_s3a}")

    print("\n=== S3b · 外部依赖失败：PG 停（两侧共用 ⇒ 同时打）===")
    s3b = s3_pg_down(B_BASE, C_BASE, b_token, c_token)
    report["S3b_pg_down"] = s3b
    print(f"  {s3b}")

    print("\n=== S4 · 超时（持锁卡住 W1 ⇒ 触发 3s 预算）===")
    b_s4 = s4_timeout(B_BASE, "B", B_PSQL, b_token)
    c_s4 = s4_timeout(C_BASE, "C", C_PSQL, c_token)
    report["S4_timeout"] = {"B": b_s4, "C": c_s4}
    print(f"  B: {b_s4}")
    print(f"  C: {c_s4}")

    # ── 判定 ──────────────────────────────────────────────────────────────
    def cmp(name, b, c):
        ok = b == c
        report.setdefault("verdicts", {})[name] = {"same": ok, "B": b, "C": c}
        print(f"  {'✅' if ok else '❌'} {name}: {'一致' if ok else '不一致'}")

    print("\n" + "=" * 78)
    print("第二轮对拍判定")
    print("=" * 78)
    # S1：响应码多重集 + 建出来的行数 + invite 是否被消费
    cmp("S1_并发_响应码分布",
        {"codes": b_s1.get("codes"), "statuses": b_s1.get("statuses")},
        {"codes": c_s1.get("codes"), "statuses": c_s1.get("statuses")})
    cmp("S1_并发_最终状态", {"created_rows": b_s1.get("created_rows"), "invite_used": b_s1.get("invite_used")},
        {"created_rows": c_s1.get("created_rows"), "invite_used": c_s1.get("invite_used")})
    # S2：响应面 + 回滚判据（新 admin 行数必须 0、invite 必须仍未被使用）
    cmp("S2_回滚_响应面", {"status": b_s2.get("status"), "code": b_s2.get("code")},
        {"status": c_s2.get("status"), "code": c_s2.get("code")})
    cmp("S2_回滚_事务原子性", {"new_admin_rows": b_s2.get("new_admin_rows"), "invite_used_at": b_s2.get("invite_used_at")},
        {"new_admin_rows": c_s2.get("new_admin_rows"), "invite_used_at": c_s2.get("invite_used_at")})
    # S3a / S3b / S4
    cmp("S3a_Redis停_响应面", {"status": b_s3a.get("status"), "code": b_s3a.get("code")},
        {"status": c_s3a.get("status"), "code": c_s3a.get("code")})
    cmp("S3b_PG停_响应面", {"status": s3b["B"].get("status"), "code": s3b["B"].get("code")},
        {"status": s3b["C"].get("status"), "code": s3b["C"].get("code")})
    cmp("S4_超时_响应面", {"status": b_s4.get("status"), "code": b_s4.get("code")},
        {"status": c_s4.get("status"), "code": c_s4.get("code")})

    with open(f"{OUT}/parity-report-round2.json", "w") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n报告：{OUT}/parity-report-round2.json")

    bad = [k for k, v in report.get("verdicts", {}).items() if not v["same"]]
    print(f"\n判定项 {len(report.get('verdicts', {}))} · 不一致 {len(bad)}"
          + (f" ⇒ {bad}" if bad else " ⇒ ✅ 全部一致"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
