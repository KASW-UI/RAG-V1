#!/usr/bin/env python3
"""C 侧单边探针 · S4（超时 ⇒ 响应面 + 副作用必须为 0 行）。

复刻 tools/difftest/parity_admin_round2.py 的 s4_timeout()，但**只跑 C 侧**
（本机没有 B 侧的 e4-postgres 容器 ⇒ B 侧读数取 admin 台账已记录的
「HTTP 200 + 信封 1015 @ 2.03s，DB 0 行」）。

⚠️ **为什么复用 parity_admin.call() 而不是自己写 urllib**：
   Tomcat 会发 `103 Early Hints` ⇒ `urllib` 会把它当成最终响应（body 为空）
   ⇒ 客户端提前断开 ⇒ 服务端记 499 ⇒ **那是探针的锅，不是行为差异**
   （见 tools/difftest/parity_admin.py 的 call() docstring）。

用法：
    C_BASE=http://127.0.0.1:8889 \
    C_PSQL=postgresql://sea:***@127.0.0.1:49432/admin_probe \
    python3 tools/difftest/parity_admin_c_s4.py
"""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import parity_admin as P  # noqa: E402

C_BASE = os.environ.get("C_BASE", "http://127.0.0.1:8889").rstrip("/")
C_PSQL = os.environ.get("C_PSQL", "")
TAG = os.environ.get("TAG", "C")


def auth(token):
    return {"Content-Type": "application/json", "Authorization": "Bearer " + token}


def envelope_code(body):
    try:
        return json.loads(body).get("code")
    except Exception:
        return None


def main():
    st, _h, b, _i = P.call(C_BASE, "/admin/v1/login", '{"username":"admin","password":"admin"}')
    try:
        token = json.loads(b)["data"]["token"]
    except Exception:
        token = None
    print(f"login: HTTP {st} token={'OK' if token else 'FAIL'}")
    if not token:
        return 2

    st, _h, b, _i = P.call(C_BASE, "/admin/v1/invite/create", "{}", auth(token))
    try:
        invite = json.loads(b)["data"].get("invite_code")
    except Exception:
        invite = None
    print(f"invite: HTTP {st} code={invite}")
    if not invite:
        print("❌ 邀请码创建失败 ⇒ 退出")
        return 2

    username = f"r2{TAG}to"
    body = json.dumps({"username": username, "password": "E4passw0rd",
                       "email": f"{username}@example.com", "invite_code": invite})

    # 外部会话持锁（FOR UPDATE 的锁活到事务结束）
    holder = subprocess.Popen(
        ["psql", C_PSQL, "-c",
         f"BEGIN; SELECT id FROM admin_invite WHERE code = '{invite}' FOR UPDATE; "
         "SELECT pg_sleep(25); COMMIT;"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)
    t0 = time.time()
    try:
        st, _h, b, _i = P.call(C_BASE, "/admin/v1/create", body, auth(token))
        elapsed = round(time.time() - t0, 2)
    finally:
        holder.kill()
        holder.wait(timeout=10)

    code = envelope_code(b)
    print(f"\nS4 响应: HTTP {st}  code={code}  用时 {elapsed}s")
    print(f"   body: {b[:200]}")

    r = subprocess.run(["psql", C_PSQL, "-Atc",
                        "SELECT count(*) FROM admin_admin WHERE username LIKE 'r2%to'"],
                       capture_output=True, text=True)
    rows = r.stdout.strip()
    print(f"\n副作用检查: admin_admin 里 username LIKE 'r2%to' ⇒ {rows} 行")
    print("   （B 侧台账读数：HTTP 200 + 信封 1015 @ 2.03s，DB 0 行）")

    ok = (st == 200 and code == 1015 and rows == "0")
    print(f"\n判定: {'✅ 通过' if ok else '❌ 不通过'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
