#!/usr/bin/env python3
"""
favorite 域 · **场景轮**（双侧活体）—— create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障。

🔴 **为什么要有这一层**：
    32 case 的 HTTP 矩阵是「**单请求 × 请求面**」的矩阵 ⇒ ⇒ 它**故意**让下游缺席
    ⇒ ⇒ 「下游缺席」那条分支被验得很透，但下面 6 类场景它**结构性覆盖不到**：

    | 场景 | 矩阵为什么覆盖不到 |
    |---|---|
    | create 成功路径 | 矩阵里 user-rpc 缺席 ⇒ 建夹必然失败（5001） |
    | 并发 | 矩阵是**串行**的 ⇒ 碰不到「先查重后插入」的竞态 |
    | 超时 | 下游缺席是**立即失败** ⇒ 压根不触发**出向预算** ⇒ 测不出「两侧预算同不同」 |
    | 部分成功 | 矩阵没有「多步写在中途失败」 |
    | Redis 故障 | 矩阵里 Redis 一直在 |
    | 重复请求（幂等重放） | 矩阵是**单请求**的 ⇒ 同一个请求发两次会得到什么，测不到 |

🔴 **判据 = 绝对断言 + 两侧对称**（**不是**只比两侧相等）：
    · **两侧相等**是**必要**条件，但**不充分** —— 两侧**都坏**也算相等 ⇒ ⇒ 假绿；
    · ⇒ 所以每个场景都另给一条**绝对断言**（期望的行为是什么）⇒ ⇒ 两侧对称 + 绝对断言**两条都**要过。

🔴 **⚠️ 本轮没有第三方锚**：
    Go 真源跑不起来（本机无 `Sea-etcd`）⇒ ⇒ 这 6 类场景**只有 B↔C 双侧活体**
    ⇒ ⇒ 证据强度**低于** 32 case 的「三方」⇒ ⇒ 台账里必须如实标注，**不得**混称 E-4 三方。

🔴 **故障注入必须两侧对称**：
    · 两侧**各自**的库（`first_db` / `fav_c_db`）⇒ 触发器 / 种子**只动自己那侧**；
    · 两侧**共用**同一个 Redis（探针自带容器）⇒ ⇒ 停它**就是**同时停两侧 ⇒ ⇒ 天然对称；
    · 慢下游是**一个** stub ⇒ ⇒ 两侧连同一个 ⇒ ⇒ 天然对称。

退出码：0 = 场景轮全绿 · 1 = 有行为差异 · 2 = 环境问题
"""
import argparse
import os
import subprocess
import sys
import time

FAILED = 0
WARN = []
NOTES = []


def say(msg=""):
    print(msg, flush=True)


def fail(msg):
    global FAILED
    FAILED += 1
    say("      ❌ %s" % msg)


def warn(msg):
    WARN.append(msg)
    say("      ⚠️ %s" % msg)


def note(msg):
    NOTES.append(msg)
    say("      · %s" % msg)


# ══════════════════════════ 已知差异登记（🔴 **不是**「已对齐」）══════════════════
# 🔴 **为什么要有这张表**：两侧行为**不等**时，有两种可能 ——
#    （a）**判据错了 / 判据没跑到** ⇒ 必须 FAILED；
#    （b）**已实测、已登记的差异**（漂移）⇒ 报出来（**绝不静默**）但**不**把流水线打成红的。
#    ⇒ ⇒ 这张表区分 (b) ⇒ ⇒ 它**是「已知差异的指纹」而不是「豁免」**：
#    ⇒ ⇒ 只有 (stage, 字段) **与两侧的具体值都逐字相符**时才走 WARN
#    ⇒ ⇒ 一旦行为变了（值对不上）⇒ ⇒ 立刻变成 FAILED ⇒ ⇒ **它也有牙齿**。
# 🔴 **2026-11-05 · DRIFT-FAV-014 已修 ⇒ 本表清空**
#
#   修法 = C 侧 api 面**自持**这一跳的出向上界（同值 2000）＋ 领域层**保留 gRPC cause**
#          ⇒ api 面能判出「这是 DEADLINE_EXCEEDED」⇒ ⇒ 业务码落 **1015**（与 B 侧同码同文案）
#          ⇒ ⇒ S3 两侧业务码**逐字相同** ⇒ ⇒ 本表**不再需要**这一条。
#
# ⚠️ **表结构保留**：这张表是「**已知差异的指纹**」而不是豁免机制 ⇒ ⇒ 将来若再有已实测、
#    已登记的差异，照旧往这里加一条（(stage, 字段) + 两侧**具体值**）⇒ ⇒ 值一变立刻 FAILED。
#    ⇒ 保持空表 = 「当前没有已登记的已知差异」⇒ ⇒ 任何两侧不等都会**真的**报红。
KNOWN_DIFFS = {
}


def known_diff(stage, field_key, b_value, c_value):
    """两侧不等时：命中「已知差异指纹」⇒ WARN（大声报出）· 否则 FAILED。"""
    spec = KNOWN_DIFFS.get((stage, field_key))
    if spec and spec.get("b") == b_value and spec.get("c") == c_value:
        warn("%s 的 %s 两侧不等 ⇒ **已登记的已知差异**（%s）：B=%s / C=%s"
             % (stage, field_key, spec.get("ledger"), b_value, c_value))
        say("        原因：%s" % spec.get("why"))
        return True
    return False


# ══════════════════════════ 基础设施 ══════════════════════════

def docker(*args, check=True):
    p = subprocess.run(["docker"] + list(args), capture_output=True, text=True)
    if check and p.returncode != 0:
        raise RuntimeError("docker %s 失败: %s" % (" ".join(args), (p.stderr or p.stdout).strip()))
    return p


def psql(container, db, sql):
    """在探针的 PG 容器里跑 SQL，返回去掉空白的 stdout。

    🔴 走 **stdin（-f -）** 而不是 `-c`：
       故障注入要建 PL/pgSQL 函数（函数体里有分号、且用 `$$` 包裹）
       ⇒ ⇒ `-c` 会把整串当**一个 simple query** 发出去 ⇒ 多语句 + dollar-quoting 容易踩解析坑
       ⇒ ⇒ `-f -` 是 psql 读脚本的标准姿势 ⇒ ⇒ 行为可预期。
    """
    p = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "postgres", "-h", "127.0.0.1",
         "-d", db, "-t", "-A", "-v", "ON_ERROR_STOP=1", "-f", "-"],
        input=sql, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError("psql(%s, %s) 失败: %s" % (container, db, (p.stderr or p.stdout).strip()))
    return p.stdout.strip()


def read_tsv(path):
    """读探针产出的 TSV（{@code id\tvalue}）⇒ 返回 dict。"""
    out = {}
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh.read().splitlines():
            if not line.strip():
                continue
            k, _, v = line.partition("\t")
            out[k] = v
    return out


def field(rendered, key, default="-"):
    for part in rendered.split("|"):
        if part.startswith(key + "="):
            return part[len(key) + 1:]
    return default


def run_stage(args, stage, tmp):
    """跑一个 stage 的探针 ⇒ 返回 (b_rendered, c_rendered)。"""
    b_out = os.path.join(tmp, "sc-%s-b.txt" % stage)
    c_out = os.path.join(tmp, "sc-%s-c.txt" % stage)
    env = dict(os.environ)
    env["PROBE_B_API"] = args.b_http
    env["PROBE_C_API"] = args.c_http
    env["PROBE_TOKEN_VALID_USER1"] = args.token
    env["PROBE_OUT_B"] = b_out
    env["PROBE_OUT_C"] = c_out
    cmd = ["java", "-cp", args.probe_cp, "probe.FavoriteScenarioProbe", "--stage", stage]
    p = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError("场景探针 stage=%s 异常退出(%s): %s"
                           % (stage, p.returncode, (p.stderr or p.stdout).strip()[-800:]))
    b = read_tsv(b_out).get(stage, "")
    c = read_tsv(c_out).get(stage, "")
    if not b or not c:
        raise RuntimeError("场景探针 stage=%s 没产出结果" % stage)
    return b, c


def set_stub_mode(args, mode, slow_ms=0):
    """切假 user-rpc 的行为（**每次请求都重读控制文件** ⇒ 不用重启 stub）。"""
    with open(args.stub_mode_file, "w", encoding="utf-8") as fh:
        fh.write("mode=%s\nslow_ms=%d\n" % (mode, slow_ms))


# ══════════════════════════ 场景 ══════════════════════════

def scenario_create_ok(args, tmp):
    """S1 · create 成功路径 —— 下游在场 ⇒ 建夹**真的成功**（且真的落库）。"""
    say("  ── S1 · create 成功路径（下游在场）")
    set_stub_mode(args, "normal")
    b, c = run_stage(args, "create_ok", tmp)

    for side, r in (("B", b), ("C", c)):
        if field(r, "biz") != "200":
            fail("S1 %s 侧建夹业务码 = %s（期望 200）⇒ 下游在场时**建夹本该成功**"
                 % (side, field(r, "biz")))
        if field(r, "folderIdPositive") != "true":
            fail("S1 %s 侧响应里没有正的 folderId（%s）⇒ **成功但没给 id**"
                 % (side, field(r, "folderIdPositive")))
    if field(b, "body") != field(c, "body"):
        fail("S1 两侧响应体（id 归一化后）不等 ⇒ %r vs %r" % (field(b, "body"), field(c, "body")))
    else:
        say("      ✅ 两侧响应体归一化后逐字节相同")

    # 🔴 绝对断言的另一半：**真的落库了**（不是只回了个 200）
    for side, db in (("B", args.b_db), ("C", args.c_db)):
        n = psql(args.pg_container, db,
                 "SELECT count(*) FROM favorite_folder WHERE user_id=1001 AND name='ScenarioFolder'")
        if n != "1":
            fail("S1 %s 侧库里 ScenarioFolder 行数 = %s（期望 1）⇒ **响应说成功但没落库**" % (side, n))
        else:
            say("      ✅ %s 侧 ScenarioFolder 真的落了 1 行" % side)


def scenario_concurrent(args, tmp):
    """S2 · 并发 —— N 个同名建夹**同时**打 ⇒ 恰好 1 成功 + 其余 5203。"""
    say("  ── S2 · 并发（8 个同名建夹同时发起）")
    set_stub_mode(args, "normal")
    b, c = run_stage(args, "concurrent", tmp)

    for side, r in (("B", b), ("C", c)):
        dist = field(r, "dist")
        if dist != "200:1,5203:7,":
            fail("S2 %s 侧并发结果分布 = %s（期望 200:1,5203:7）⇒ 并发下**唯一键兜底**或**竞态**有问题"
                 % (side, dist))
        # 🔴 成功体与失败体**必然不同**（成功信封 vs 错误信封）⇒ ⇒ 不能断言「所有体相同」
        #   ⇒ ⇒ 改成：成功体只有 1 种（恰好 1 个成功 ⇒ 1 个体）· 失败体也只有 1 种（7 个失败同形）
        if field(r, "okBodiesDistinct") != "1":
            fail("S2 %s 侧成功响应体有 %s 种（期望 1）⇒ 并发下**不止一个**建夹成功"
                 % (side, field(r, "okBodiesDistinct")))
        if field(r, "failBodiesDistinct") != "1":
            fail("S2 %s 侧失败响应体有 %s 种（期望 1）⇒ 失败方的错误形态不统一 ⇒ %s"
                 % (side, field(r, "failBodiesDistinct"), field(r, "okBody")))
    if field(b, "dist") != field(c, "dist"):
        fail("S2 两侧并发结果分布不等 ⇒ %s vs %s" % (field(b, "dist"), field(c, "dist")))
    else:
        say("      ✅ 两侧并发结果分布相同：%s" % field(b, "dist"))

    for side, db in (("B", args.b_db), ("C", args.c_db)):
        n = psql(args.pg_container, db,
                 "SELECT count(*) FROM favorite_folder WHERE user_id=1001 AND name='ConcurrentFolder'")
        if n != "1":
            fail("S2 %s 侧库里 ConcurrentFolder 行数 = %s（期望 1）⇒ **并发写出了重名收藏夹**" % (side, n))
        else:
            say("      ✅ %s 侧 ConcurrentFolder 只有 1 行（无重名）" % side)


def scenario_timeout(args, tmp):
    """S3 · 超时 —— 下游**慢** ⇒ 撞出向 deadline ⇒ 问「两侧的出向上界同不同」。"""
    say("  ── S3 · 超时（下游慢 5s ⇒ 应撞出向 deadline）")
    # 🔴 慢到**超过**两侧的出向预算（2s）⇒ ⇒ 必须触发超时，而不是「慢但成功」
    set_stub_mode(args, "slow", slow_ms=5000)
    b, c = run_stage(args, "timeout", tmp)
    set_stub_mode(args, "normal")

    for side, r in (("B", b), ("C", c)):
        biz = field(r, "biz")
        if biz == "200":
            fail("S3 %s 侧慢下游下**居然成功**了 ⇒ 出向预算没生效（biz=200）" % side)
        bucket = field(r, "elapsedBucket")
        try:
            lo = int(bucket.split("-")[0])
        except ValueError:
            lo = -1
        if lo < 1000:
            fail("S3 %s 侧耗时桶 = %s（期望 ≥1000ms）⇒ 这是**立即失败**（没撞预算）" % (side, bucket))
        else:
            say("      ✅ %s 侧：biz=%s · 耗时桶=%s（确实等到了预算）" % (side, biz, bucket))
    if field(b, "elapsedBucket") != field(c, "elapsedBucket"):
        fail("S3 两侧耗时桶不同 ⇒ %s vs %s ⇒ **出向预算不同值**" % (field(b, "elapsedBucket"), field(c, "elapsedBucket")))
    else:
        say("      ✅ 两侧耗时桶相同：%s ⇒ 出向上界同值" % field(b, "elapsedBucket"))
    if field(b, "biz") != field(c, "biz"):
        if not known_diff("S3", "biz", field(b, "biz"), field(c, "biz")):
            fail("S3 两侧业务码不等 ⇒ %s vs %s" % (field(b, "biz"), field(c, "biz")))
    else:
        say("      ✅ 两侧业务码相同：%s" % field(b, "biz"))
    note("S3 观测：慢下游下两侧业务码 = %s（B） / %s（C）" % (field(b, "biz"), field(c, "biz")))


def scenario_partial_success(args, tmp):
    """S4 · 部分成功 —— 多步写**中途**失败 ⇒ 断言「**没有半截状态**」。

    🔴 **注入点选在哪（很关键）**：
        favorite 域能在**一次请求**里写两行的地方只有一处 ⇒ ⇒
        {@code POST /item/create} = 「写 favorite_item」＋「写 favorite_fact_outbox 事实」
        （**同一个事务** · {@code FavoriteFactWriteService#insertFavorite} 的
        {@code @Transactional(REQUIRED, READ_COMMITTED, rollbackFor=Exception.class)}）。

        ⇒ ⇒ 在 {@code favorite_fact_outbox} 上挂 **BEFORE INSERT 触发器** ⇒ ⇒
        第 1 步（写 item）**已经成功**、第 2 步（写 outbox）**被打断**
        ⇒ ⇒ 若事务边界正确，第 1 步也必须**回滚** ⇒ ⇒ **一行都不能留下**。

    🔴 **为什么不用「删收藏夹」那条路**：
        级联删除要先过 {@code FavoriteAssertionSchemaResolver}（legacy 分支要 marker 行）
        ⇒ ⇒ 种子里那两条 item 没有 marker ⇒ ⇒ 会在**任何写入之前**就以 5304 失败
        ⇒ ⇒ 那样这个场景**根本走不到写入** ⇒ ⇒ **断言会空转**（"没留下半截状态" 恒真）。
        ⇒ ⇒ 选「写路径」而不是「删路径」，才能真正打到**第二步失败**。
    """
    say("  ── S4 · 部分成功（outbox 写失败 ⇒ item 必须回滚 ⇒ 不得留半截状态）")
    trigger_sql = """
        CREATE OR REPLACE FUNCTION scenario_block_outbox_insert() RETURNS trigger AS $$
        BEGIN
            RAISE EXCEPTION 'scenario: outbox insert blocked';
        END;
        $$ LANGUAGE plpgsql;
        DROP TRIGGER IF EXISTS scenario_block_outbox ON favorite_fact_outbox;
        CREATE TRIGGER scenario_block_outbox
            BEFORE INSERT ON favorite_fact_outbox
            FOR EACH ROW EXECUTE FUNCTION scenario_block_outbox_insert();
    """
    drop_sql = "DROP TRIGGER IF EXISTS scenario_block_outbox ON favorite_fact_outbox;"
    count_sql = ("SELECT count(*) FROM favorite_item "
                 "WHERE folder_id=9001 AND target_id='90001' AND target_type='article'")

    for db in (args.b_db, args.c_db):
        psql(args.pg_container, db, trigger_sql)

    try:
        b, c = run_stage(args, "create_item", tmp)
        for side, r in (("B", b), ("C", c)):
            if field(r, "biz") == "200":
                fail("S4 %s 侧建条目**居然成功**了 ⇒ 触发器没生效 ⇒ 场景空转" % side)
            else:
                say("      ✅ %s 侧：第 2 步（写 outbox）被打断（biz=%s）" % (side, field(r, "biz")))
        if field(b, "biz") != field(c, "biz"):
            fail("S4 两侧业务码不等 ⇒ %s vs %s" % (field(b, "biz"), field(c, "biz")))
        else:
            say("      ✅ 两侧业务码相同：%s" % field(b, "biz"))

        # 🔴 绝对断言：**第 1 步写的 item 必须回滚**（一行都不能留）
        for side, db in (("B", args.b_db), ("C", args.c_db)):
            n = psql(args.pg_container, db, count_sql)
            if n != "0":
                fail("S4 %s 侧留下了 %s 行 favorite_item ⇒ **出现了部分成功**（第 1 步没回滚）" % (side, n))
            else:
                say("      ✅ %s 侧：没有半截 item 行（=0）⇒ **没有部分成功**" % side)
    finally:
        for db in (args.b_db, args.c_db):
            psql(args.pg_container, db, drop_sql)

    # 恢复轮：故障撤掉 ⇒ 同一条请求应当成功 ⇒ 且**只留 1 行**
    b, c = run_stage(args, "create_item", tmp)
    for side, r in (("B", b), ("C", c)):
        if field(r, "biz") != "200":
            fail("S4 恢复轮 %s 侧业务码 = %s（期望 200）⇒ 故障撤掉后**没恢复**" % (side, field(r, "biz")))
        else:
            say("      ✅ 恢复轮 %s 侧：biz=200" % side)
    for side, db in (("B", args.b_db), ("C", args.c_db)):
        n = psql(args.pg_container, db, count_sql)
        if n != "1":
            fail("S4 恢复轮 %s 侧 item 行数 = %s（期望 1）" % (side, n))
        else:
            say("      ✅ 恢复轮 %s 侧：恰好 1 行（写入恢复正常）" % side)


def scenario_redis_down(args, tmp):
    """S5 · Redis 故障 —— 停掉 Redis ⇒ 看两侧的失败形态 ⇒ 再恢复。"""
    say("  ── S5 · Redis 故障（停掉探针自带的 Redis ⇒ 两侧同时受影响）")
    docker("stop", args.redis_container)

    try:
        b, c = run_stage(args, "read_folders", tmp)
        for side, r in (("B", b), ("C", c)):
            say("      · %s 侧：status=%s · biz=%s" % (side, field(r, "status"), field(r, "biz")))
        if field(b, "status") != field(c, "status"):
            fail("S5 Redis 挂掉时两侧 HTTP 状态码不等 ⇒ %s vs %s"
                 % (field(b, "status"), field(c, "status")))
        elif field(b, "biz") != field(c, "biz"):
            fail("S5 Redis 挂掉时两侧业务码不等 ⇒ %s vs %s" % (field(b, "biz"), field(c, "biz")))
        else:
            say("      ✅ 两侧在 Redis 挂掉时的形态相同：status=%s · biz=%s"
                % (field(b, "status"), field(b, "biz")))
        note("S5 观测：Redis 不可用时 ⇒ 两侧 HTTP %s / 业务码 %s（**fail-open 还是 fail-close 已由读数给出**）"
             % (field(b, "status"), field(b, "biz")))
    finally:
        docker("start", args.redis_container)
        # 等 Redis 真正回来（容器起来 ≠ 可连）
        for _ in range(60):
            p = subprocess.run(["docker", "exec", args.redis_container, "redis-cli", "ping"],
                               capture_output=True, text=True)
            if "PONG" in (p.stdout or ""):
                break
            time.sleep(1)

    # 恢复轮：Redis 回来 ⇒ 两侧都该恢复正常
    b, c = run_stage(args, "read_folders", tmp)
    for side, r in (("B", b), ("C", c)):
        if field(r, "biz") != "200":
            fail("S5 恢复轮 %s 侧业务码 = %s（期望 200）⇒ Redis 回来后**没恢复**" % (side, field(r, "biz")))
    if field(b, "biz") != field(c, "biz"):
        fail("S5 恢复轮两侧业务码不等")
    else:
        say("      ✅ 恢复轮：两侧都正常（biz=%s）" % field(b, "biz"))


def scenario_replay(args, tmp):
    """S6 · 重复请求（幂等重放）—— 同一请求连发两次。

    🔴 **为什么要有这一层**：verification.md §九 明列「幂等和重复请求」且「对拍必须覆盖：重复请求」
       ⇒ ⇒ 而 32 case 矩阵是**单请求**的 ⇒ ⇒ 同一个请求发两次会得到什么，矩阵**结构性测不到**。

    🔴 **这里覆盖的是三种不同的重复语义**（favorite 域真实存在的）：
       ① **建夹重放** ⇒ 第二次撞**重名预查** ⇒ **5203**（重复 ⇒ 不产生第二条副作用）
       ② **收藏重放** ⇒ 第二次撞**重复收藏判定** ⇒ **5302**（同上）
       ③ **改名幂等** ⇒ 第二次是**同名** ⇒ **直接成功、且不写库** ⇒ **200** 且 update_time **不动**
          ⇒ ⇒ 这一条才是**真·幂等**（重复 ⇒ 返回同样结果**且无任何副作用**，连 updated_at 都不推进）

    ⇒ ⇒ 只测一个都答不出「重复请求」这一行 ⇒ ⇒ 三个都要打。
    """
    say("  ── S6 · 重复请求（幂等重放：同一请求连发两次）")
    set_stub_mode(args, "normal")

    # 🔴 改名幂等的「**不写库**」断言 ⇒ 必须**先**取 update_time（重命名前）
    update_time_sql = ("SELECT coalesce(to_char(update_time at time zone 'UTC', "
                       "'YYYY-MM-DD HH24:MI:SS.US'), '-') FROM favorite_folder "
                       "WHERE folder_id=9002")
    before = {side: psql(args.pg_container, db, update_time_sql)
              for side, db in (("B", args.b_db), ("C", args.c_db))}

    b, c = run_stage(args, "replay", tmp)

    # ── 绝对断言：三个子场景各自的业务码 ──────────────────────────────
    for side, r in (("B", b), ("C", c)):
        if field(r, "folderCreate1") != "200" or field(r, "folderCreate2") != "5203":
            fail("S6 %s 侧建夹重放 = %s/%s（期望 200/5203）⇒ **重复建夹没被拒**"
                 % (side, field(r, "folderCreate1"), field(r, "folderCreate2")))
        else:
            say("      ✅ %s 侧建夹重放：200 → 5203（重名预查命中）" % side)

        if field(r, "itemCreate1") != "200" or field(r, "itemCreate2") != "5302":
            fail("S6 %s 侧收藏重放 = %s/%s（期望 200/5302）⇒ **重复收藏没被拒**"
                 % (side, field(r, "itemCreate1"), field(r, "itemCreate2")))
        else:
            say("      ✅ %s 侧收藏重放：200 → 5302（重复收藏判定命中）" % side)

        if field(r, "rename1") != "200" or field(r, "rename2") != "200":
            fail("S6 %s 侧改名重放 = %s/%s（期望 200/200）⇒ **同名改名不是幂等的**"
                 % (side, field(r, "rename1"), field(r, "rename2")))
        else:
            say("      ✅ %s 侧改名重放：200 → 200（同名 ⇒ 直接成功）" % side)

    # ── 绝对断言（另一半）：**副作用** —— 重复请求不得留下第二份 ──────────
    for side, db in (("B", args.b_db), ("C", args.c_db)):
        n = psql(args.pg_container, db,
                 "SELECT count(*) FROM favorite_folder WHERE user_id=1001 AND name='ReplayFolder'")
        if n != "1":
            fail("S6 %s 侧 ReplayFolder 行数 = %s（期望 1）⇒ **重复建夹写出了第二个同名夹**" % (side, n))
        else:
            say("      ✅ %s 侧 ReplayFolder 只有 1 行（重复建夹没写出第二个）" % side)

        n = psql(args.pg_container, db,
                 "SELECT count(*) FROM favorite_item WHERE folder_id=9001 AND target_id='90002'")
        if n != "1":
            fail("S6 %s 侧 item(90002) 行数 = %s（期望 1）⇒ **重复收藏写出了第二条**" % (side, n))
        else:
            say("      ✅ %s 侧 item(90002) 只有 1 行（重复收藏没写出第二条）" % side)

        # 🔴 **幂等的核心断言**：同名改名**不得**推进 update_time ⇒ ⇒ 证明「真的没写库」
        after = psql(args.pg_container, db, update_time_sql)
        if after != before[side]:
            fail("S6 %s 侧 folder 9002 的 update_time 被推进了（%s → %s）"
                 " ⇒ **「同名改名不写库」这条幂等语义破了**"
                 % (side, before[side], after))
        else:
            say("      ✅ %s 侧 folder 9002 的 update_time 未推进（%s）⇒ 同名改名真的没写库"
                % (side, after))

    # ── 两侧对称断言 ─────────────────────────────────────────────────
    keys = ("folderCreate1", "folderCreate2", "itemCreate1", "itemCreate2",
            "rename1", "rename2")
    for key in keys:
        if field(b, key) != field(c, key):
            fail("S6 两侧 %s 不等 ⇒ %s vs %s" % (key, field(b, key), field(c, key)))
    if field(b, "renameBody") != field(c, "renameBody"):
        fail("S6 两侧改名响应体不等 ⇒ %r vs %r"
             % (field(b, "renameBody"), field(c, "renameBody")))
    else:
        say("      ✅ 两侧六个业务码 + 改名响应体全部相同")


# ══════════════════════════ 主流程 ══════════════════════════

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--b-http", required=True)
    ap.add_argument("--c-http", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--probe-cp", required=True)
    ap.add_argument("--tmp", required=True)
    ap.add_argument("--pg-container", required=True)
    ap.add_argument("--b-db", default="first_db")
    ap.add_argument("--c-db", default="fav_c_db")
    ap.add_argument("--redis-container", required=True)
    ap.add_argument("--stub-mode-file", required=True)
    args = ap.parse_args()

    say("═══ favorite 场景轮（双侧活体 · 无第三方锚）═══")
    for fn in (scenario_create_ok, scenario_concurrent, scenario_timeout,
               scenario_partial_success, scenario_redis_down, scenario_replay):
        try:
            fn(args, args.tmp)
        except Exception as e:      # 环境问题 ⇒ 退出码 2（不是「有差异」）
            say("      💥 场景 %s 抛异常（环境问题）: %s" % (fn.__name__, e))
            return 2

    say("")
    if WARN:
        say("⚠️ 警告 %d 条（**已登记的已知差异** ⇒ 报出来但不打红；见台账）：" % len(WARN))
        for w in WARN:
            say("   · %s" % w)
    if NOTES:
        say("· 观测记录:")
        for n in NOTES:
            say("   · %s" % n)
    if FAILED:
        say("❌ 场景轮失败 %d 条" % FAILED)
        return 1
    say("✅ 场景轮全绿（6 类场景 · 双侧活体一致 · 绝对断言全过）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
