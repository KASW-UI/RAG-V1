#!/usr/bin/env bash
#
# task 域 **双侧活体对拍**探针 —— **B 侧（RAG / Boot 4）↔ C 侧（RAG-V1 / Boot 3.5）**
#
# 被测两侧（**同时起、同时活、同请求、同字节**）：
#   · B 侧 = /Users/pr/Documents/java&golang/RAG/service/task/{task-api,task-rpc}（Boot 4.0.8）
#           ⚠️ B 侧是 **两个进程**（api = HTTP 壳 · rpc = gRPC 业务）⇒ 本探针要起 **2 个** jar；
#   · C 侧 = 本仓 service/task（Boot 3.5.11 · **D1 合并进程**：HTTP + gRPC 同进程）⇒ 1 个 jar。
#
# 对拍面（**四个面**，全部逐字节）：
#   · M1  HTTP 字节面   —— 原始状态行 + Content-Type + X-Content-Type-Options + Allow + body hex
#   · M2  Redis 状态面  —— 回填后的 `task:progress:<uid>` hash 内容 + TTL + 「无行时 EXPIRE 是 no-op」
#   · M3  PG 状态面     —— `task_progress` / `user_like_count` 的行（读路径**不得**写库）
#   · M4  冻结锚面      —— C 侧读数 vs `expected.txt`（**冻结的是 B 侧读数** ⇒ oracle 是 B 侧）
#
# ⚠️ **没有「第三方锚」**：本机没有 Go 真源可活体 ⇒ 判定条件是 **C == B（双侧活体逐字节相等）**，
#    强度记为 **E-4（双侧活体对拍）**，**不冒充「三方一致」**。
#
# ⚠️ **B 侧只读**：本探针只在 B 仓跑 `mvn package`（产物落 `target/`，已被 .gitignore 排除）
#   ⇒ **不碰 B 侧任何源码 / 配置**（第 0 步断言 B 仓 `git status` 干净）。
#
# ⚠️ **Kafka 一律指向死地址 `127.0.0.1:1`**：本探针只测 **HTTP 读路径**，Kafka 不在这条路径上；
#   而两侧的 `spring.kafka.listener.*` **都是死键**（两侧都自建 `kafkaListenerContainerFactory`
#   ⇒ Boot 的 configurer 不参与 ⇒ 那些键没有消费点）⇒ **没法用 `auto-startup=false` 关掉 listener**
#   ⇒ 只能用「broker 不可达」让它们连不上（两侧**对称**施加 ⇒ 对拍公平性不受影响）。
#
# 用法：
#   bash tools/difftest/task/run.sh                 # 对拍（同时校验冻结锚）
#   FREEZE=1 bash tools/difftest/task/run.sh        # 重新冻结 B 侧读数到 expected.txt
#   KEEP_WORK=1 bash tools/difftest/task/run.sh      # 保留临时目录与日志
#
# 退出码：0 = 双侧逐字节一致 · 1 = 有真差异 · 2 = 环境问题 / 依赖缺失 / 真源漂移。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"

# ── 两侧仓路径 ─────────────────────────────────────────────────────────────────
B_REPO="${B_REPO:-/Users/pr/Documents/java&golang/RAG}"

# ── 宿主中间件 ────────────────────────────────────────────────────────────────
NACOS_PORT="${NACOS_PORT:-49848}"    # Sea-nacos 49848→8848
JAEGER_PORT="${JAEGER_PORT:-49317}"  # Sea-jaeger 49317→4317（OTLP gRPC）

# ── 端口（**本探针专用段 18241-18248**；follow 18201- · like 18211- · hot 18221-）────
B_API_PORT="${B_API_PORT:-18241}"      # B 侧 task-api（HTTP）
B_RPC_PORT="${B_RPC_PORT:-18242}"      # B 侧 task-rpc（gRPC 业务口）
B_REDIS_PORT="${B_REDIS_PORT:-18243}"  # B 侧 Redis（**必须与 C 侧物理隔离**）
C_REDIS_PORT="${C_REDIS_PORT:-18244}"  # C 侧 Redis
B_PG_PORT="${B_PG_PORT:-18245}"        # B 侧 Postgres
C_PG_PORT="${C_PG_PORT:-18246}"        # C 侧 Postgres
C_HTTP_PORT="${C_HTTP_PORT:-18247}"    # C 侧 task-service（HTTP）
C_GRPC_PORT="${C_GRPC_PORT:-18248}"    # C 侧 task-service（gRPC）

# ── Nacos 分组隔离 ────────────────────────────────────────────────────────────
# ⚠️ 两侧的 group 都读 `spring.cloud.nacos.discovery.group` ⇒ **可被 NACOS_GROUP 覆盖**
#   ⇒ 本探针给两侧**同一个唯一 group** ⇒ 同名服务 `task-rpc` 不会与别的 lane 串扰。
NACOS_NAMESPACE="${NACOS_NAMESPACE:-taskprobe}"
NACOS_GROUP="${NACOS_GROUP:-taskdiff$$}"

# ── 容器（名字带 $$ ⇒ 别的 lane 的 cleanup 删不到本轮的库）────────────────────
B_REDIS_CONTAINER="${B_REDIS_CONTAINER:-e5task-b-redis-$$}"
C_REDIS_CONTAINER="${C_REDIS_CONTAINER:-e5task-c-redis-$$}"
B_PG_CONTAINER="${B_PG_CONTAINER:-e5task-b-pg-$$}"
C_PG_CONTAINER="${C_PG_CONTAINER:-e5task-c-pg-$$}"

# ── Kafka：一律死地址（理由见文件头）──────────────────────────────────────────
DEAD_BROKER="127.0.0.1:1"

# ── 播种数据（**两侧逐条相同**）────────────────────────────────────────────────
# 🔴 **Redis / PG 必须两侧物理隔离**（不是「省一个容器」的取舍，是正确性）：
#   读路径在 Redis 未命中时会**回填** `task:progress:<uid>` ⇒ 共用一个 Redis 时
#   后跑的一侧会命中前跑一侧写下的缓存 ⇒ **PG 回源那条路径被吃掉** ⇒ 假绿。
#   ⇒ 两侧各起一个 Redis 容器 + 各起一个 Postgres 容器（库名也不同）。
B_DB="taskdiff_b"
C_DB="taskdiff_c"
PG_USER="postgres"
PG_PASS="postgres"

# ── 工作目录 ──────────────────────────────────────────────────────────────────
WORK="$(mktemp -d)"
PIDS=()
KEEPALIVE_PIDS=()
FREEZE="${FREEZE:-0}"
KEEP_WORK="${KEEP_WORK:-0}"
# ⚠️ **SKIP_B_BUILD=1**：B 侧产物**不会变**时（变异验证只需改 C 侧）⇒ 跳过 B 侧构建
#    ⇒ 省一次全量 `mvn package`（B 侧两个 fat jar ≈ 60 s）。
SKIP_B_BUILD="${SKIP_B_BUILD:-0}"
# ⚠️ **REPORT_OUT**：把逐条明细写到**固定路径** ⇒ 变异脚本能读它判「哪条转红、哪条仍绿」。
REPORT_OUT="${REPORT_OUT:-${WORK}/m1-report.txt}"
# 🔴 gRPC 面的明细另存一份 ⇒ 探针层变异（M-P4）要**单独**判它
#    （HTTP 面的明细判不出「gRPC 面被改了」—— 那正是 M-P4 要证明的点）
GRPC_REPORT_OUT="${GRPC_REPORT_OUT:-${WORK}/grpc-report.txt}"

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
die()  { printf '!! %s\n' "$*" >&2; dump_logs; exit 2; }

dump_logs() {
    local f
    for f in "${WORK}"/*.log; do
        [ -f "${f}" ] || continue
        printf '\n----- %s (tail 40) -----\n' "${f}" >&2
        tail -40 "${f}" >&2
    done
}

host_port_open() { nc -z -G 2 127.0.0.1 "$1" >/dev/null 2>&1; }

wait_port() {   # $1 = 端口  $2 = 标签  $3 = 最长秒数
    local port="$1" label="$2" secs="${3:-90}" i=0
    while [ "${i}" -lt "${secs}" ]; do
        host_port_open "${port}" && { say "   ✓ ${label} 已监听 :${port}"; return 0; }
        sleep 1; i=$((i + 1))
    done
    return 1
}

stop_stacks() {
    local p
    for p in "${KEEPALIVE_PIDS[@]:-}"; do [ -n "${p}" ] && kill "${p}" 2>/dev/null; done
    for p in "${PIDS[@]:-}"; do
        [ -n "${p}" ] || continue
        kill "${p}" 2>/dev/null
    done
    sleep 1
    for p in "${PIDS[@]:-}"; do
        [ -n "${p}" ] || continue
        kill -9 "${p}" 2>/dev/null
    done
}

cleanup() {
    local rc=$?
    stop_stacks
    docker rm -f "${B_REDIS_CONTAINER}" "${C_REDIS_CONTAINER}" \
        "${B_PG_CONTAINER}" "${C_PG_CONTAINER}" >/dev/null 2>&1
    if [ "${KEEP_WORK}" = "1" ]; then
        say "   （KEEP_WORK=1 ⇒ 保留 ${WORK}）"
    else
        rm -rf "${WORK}"
    fi
    exit "${rc}"
}
trap cleanup EXIT INT TERM

# ══════════════════════════ 0. 预检 ═══════════════════════════════════════════
step "0/10 预检"

command -v docker >/dev/null 2>&1 || die "没有 docker"
command -v nc >/dev/null 2>&1 || die "没有 nc"
command -v python3 >/dev/null 2>&1 || die "没有 python3"
[ -d "${B_REPO}" ] || die "B 侧仓不存在：${B_REPO}"

# 🔴 **B 侧只读断言**：本探针绝不允许改 B 侧任何源码 / 配置。
B_DIRTY="$(cd "${B_REPO}" && git status --porcelain -- service/task service/common)"
if [ -n "${B_DIRTY}" ]; then
    die "B 侧仓有未提交改动 ⇒ 探针前提不成立（B 侧必须干净）：
${B_DIRTY}"
fi
say "   ✓ B 侧仓干净（service/task · service/common 无未提交改动）"

# ══════════════════════════ 1. 构建两侧产物 ═══════════════════════════════════
step "1/10 构建两侧产物（B 侧只跑 mvn package ⇒ 不改 B 侧源码）"

if [ "${SKIP_B_BUILD}" = "1" ]; then
    say "   · B 侧：跳过构建（SKIP_B_BUILD=1 ⇒ B 侧产物不变）"
else
    say "   · B 侧：mvn -o -pl service/task/task-api,service/task/task-rpc -am package"
    ( cd "${B_REPO}" && mvn -o -q -pl service/task/task-api,service/task/task-rpc -am package -DskipTests ) \
        >"${WORK}/build-b.log" 2>&1 || { tail -30 "${WORK}/build-b.log" >&2; die "B 侧构建失败"; }
fi

say "   · C 侧：mvn -o -pl service/task -am package"
( cd "${C_ROOT}" && mvn -o -q -pl service/task -am package -DskipTests ) \
    >"${WORK}/build-c.log" 2>&1 || { tail -30 "${WORK}/build-c.log" >&2; die "C 侧构建失败"; }

B_API_JAR="${B_REPO}/service/task/task-api/target/task-api-0.0.1-SNAPSHOT.jar"
B_RPC_JAR="${B_REPO}/service/task/task-rpc/target/task-rpc-0.0.1-SNAPSHOT.jar"
C_JAR="${C_ROOT}/service/task/target/task-service-1.0-SNAPSHOT.jar"
[ -f "${B_API_JAR}" ] || die "B 侧 task-api jar 不存在：${B_API_JAR}"
[ -f "${B_RPC_JAR}" ] || die "B 侧 task-rpc jar 不存在：${B_RPC_JAR}"
[ -f "${C_JAR}" ]     || die "C 侧 task-service jar 不存在：${C_JAR}"
say "   ✓ 两侧产物就绪"

# ══════════════════════════ 2. 起两侧容器 ═════════════════════════════════════
step "2/10 起两侧独立容器（Redis / Postgres 各一份 ⇒ 物理隔离）"

start_redis() {   # $1 = 容器名  $2 = 宿主端口
    docker rm -f "$1" >/dev/null 2>&1
    docker run -d --name "$1" -p "127.0.0.1:${2}:6379" redis:7-alpine >/dev/null \
        || die "起 Redis 容器失败：$1"
    local i=0
    while [ "${i}" -lt 40 ]; do
        docker exec "$1" redis-cli ping 2>/dev/null | grep -q PONG && return 0
        sleep 1; i=$((i + 1))
    done
    die "Redis 容器 $1 没就绪"
}

start_pg() {      # $1 = 容器名  $2 = 宿主端口  $3 = 库名
    docker rm -f "$1" >/dev/null 2>&1
    docker run -d --name "$1" -e POSTGRES_USER="${PG_USER}" -e POSTGRES_PASSWORD="${PG_PASS}" \
        -e POSTGRES_DB="$3" -p "127.0.0.1:${2}:5432" postgres:16-alpine >/dev/null \
        || die "起 Postgres 容器失败：$1"
    local i=0
    while [ "${i}" -lt 60 ]; do
        docker exec "$1" pg_isready -U "${PG_USER}" -d "$3" >/dev/null 2>&1 && return 0
        sleep 1; i=$((i + 1))
    done
    die "Postgres 容器 $1 没就绪"
}

start_redis "${B_REDIS_CONTAINER}" "${B_REDIS_PORT}"
start_redis "${C_REDIS_CONTAINER}" "${C_REDIS_PORT}"
start_pg "${B_PG_CONTAINER}" "${B_PG_PORT}" "${B_DB}"
start_pg "${C_PG_CONTAINER}" "${C_PG_PORT}" "${C_DB}"
say "   ✓ Redis：B=${B_REDIS_PORT} / C=${C_REDIS_PORT} · Postgres：B=${B_PG_PORT}/${B_DB} / C=${C_PG_PORT}/${C_DB}"

# ══════════════════════════ 3. Nacos 专属命名空间 ═════════════════════════════
step "3/10 Nacos 专属命名空间（${NACOS_NAMESPACE}）＋ 唯一分组（${NACOS_GROUP}）"
curl -s -m 10 -X POST "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/namespaces" \
    --data-urlencode "customNamespaceId=${NACOS_NAMESPACE}" \
    --data-urlencode "namespaceName=${NACOS_NAMESPACE}" \
    --data-urlencode "namespaceDesc=task parity probe" >/dev/null 2>&1
NS_LIST="$(curl -s -m 10 "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/namespaces" 2>/dev/null)"
case "${NS_LIST}" in
    *"\"namespace\":\"${NACOS_NAMESPACE}\""*) : ;;
    *) die "Nacos 命名空间 ${NACOS_NAMESPACE} 没建出来（Sea-nacos ${NACOS_PORT} 起没起？）" ;;
esac
say "   ✓ 命名空间 ${NACOS_NAMESPACE} 就绪"

# ══════════════════════════ 4. 起 B 侧 task-rpc（gRPC）═════════════════════════
step "4/10 起 B 侧 task-rpc（gRPC :${B_RPC_PORT}）"
env \
    POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${B_PG_PORT}" POSTGRES_DB="${B_DB}" \
    POSTGRES_USER="${PG_USER}" POSTGRES_PASSWORD="${PG_PASS}" \
    REDIS_HOST=127.0.0.1 REDIS_PORT="${B_REDIS_PORT}" REDIS_PASSWORD= \
    KAFKA_BROKERS="${DEAD_BROKER}" \
    NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" \
    NACOS_NAMESPACE="${NACOS_NAMESPACE}" NACOS_GROUP="${NACOS_GROUP}" \
    OTEL_EXPORTER_OTLP_ENDPOINT="http://127.0.0.1:${JAEGER_PORT}" \
    TASK_RPC_PORT="${B_RPC_PORT}" \
    java -jar "${B_RPC_JAR}" >"${WORK}/b-rpc.log" 2>&1 &
PIDS+=($!)
disown %% 2>/dev/null
wait_port "${B_RPC_PORT}" "B 侧 task-rpc" 120 || { tail -40 "${WORK}/b-rpc.log" >&2; die "B 侧 task-rpc 没起来"; }

# ══════════════════════════ 5. 起 B 侧 task-api（HTTP）═════════════════════════
step "5/10 起 B 侧 task-api（HTTP :${B_API_PORT} ⇒ 直连 task-rpc）"
env \
    NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" \
    NACOS_NAMESPACE="${NACOS_NAMESPACE}" NACOS_GROUP="${NACOS_GROUP}" \
    OTEL_ADDR=127.0.0.1 OTLP_PORT="${JAEGER_PORT}" \
    TASK_API_PORT="${B_API_PORT}" \
    TASK_RPC_ADDRESS="static://127.0.0.1:${B_RPC_PORT}" \
    java -jar "${B_API_JAR}" >"${WORK}/b-api.log" 2>&1 &
PIDS+=($!)
disown %% 2>/dev/null
wait_port "${B_API_PORT}" "B 侧 task-api" 120 || { tail -40 "${WORK}/b-api.log" >&2; die "B 侧 task-api 没起来"; }

# ══════════════════════════ 6. 起 C 侧 task-service（D1 单进程）════════════════
step "6/10 起 C 侧 task-service（HTTP :${C_HTTP_PORT} ＋ gRPC :${C_GRPC_PORT}）"
env \
    POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${C_PG_PORT}" POSTGRES_DB="${C_DB}" \
    POSTGRES_USER="${PG_USER}" POSTGRES_PASSWORD="${PG_PASS}" \
    REDIS_HOST=127.0.0.1 REDIS_PORT="${C_REDIS_PORT}" REDIS_PASSWORD= \
    KAFKA_BROKERS="${DEAD_BROKER}" \
    NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" \
    NACOS_NAMESPACE="${NACOS_NAMESPACE}" NACOS_GROUP="${NACOS_GROUP}" \
    OTEL_EXPORTER_OTLP_ENDPOINT="http://127.0.0.1:${JAEGER_PORT}" \
    TASK_HTTP_PORT="${C_HTTP_PORT}" TASK_GRPC_PORT="${C_GRPC_PORT}" \
    java -jar "${C_JAR}" >"${WORK}/c-app.log" 2>&1 &
PIDS+=($!)
disown %% 2>/dev/null
wait_port "${C_HTTP_PORT}" "C 侧 task-service" 120 || { tail -40 "${WORK}/c-app.log" >&2; die "C 侧 task-service 没起来"; }

# ══════════════════════════ 7. 播种（两侧逐条相同）════════════════════════════
step "7/10 播种（两侧 Redis + Postgres 逐条相同）"

seed_redis() {   # $1 = 容器名
    local r="$1"
    # uid=1001 ⇒ Redis 命中（值合法 ⇒ 190 进度 2 / 1902 进度 4）
    docker exec "${r}" redis-cli HSET task:progress:1001 190 'doing|2|3' 1902 'done|4|5' >/dev/null
    docker exec "${r}" redis-cli EXPIRE task:progress:1001 900 >/dev/null
    # uid=1005 ⇒ 第二段非法 ⇒ 1902 进度回落 0（190 仍是 2）
    docker exec "${r}" redis-cli HSET task:progress:1005 190 'doing|2|3' 1902 'done|abc|5' >/dev/null
    docker exec "${r}" redis-cli EXPIRE task:progress:1005 900 >/dev/null
    # uid=1006 ⇒ 段数≠3 ⇒ 190 进度回落 0（1902 仍是 4）
    docker exec "${r}" redis-cli HSET task:progress:1006 190 'doing|2' 1902 'done|4|5' >/dev/null
    docker exec "${r}" redis-cli EXPIRE task:progress:1006 900 >/dev/null
    # ── gRPC 面**专用** uid（2001~）──────────────────────────────────────────
    # 🔴 **必须与 HTTP 面（1001~）物理错开**：`ok-pg-fallback` 会**回填** Redis 缓存
    #    ⇒ 若共用 uid ⇒ 谁先跑谁就替对方把回填做了 ⇒ HTTP 面的 M2 状态面
    #    （验「读路径确实写了缓存」）就**失去判据** ⇒ ⇒ 两面 uid 互不污染。
    # uid=2001 ⇒ Redis 命中（值合法 ⇒ 190 进度 2 / 1902 进度 4）
    docker exec "${r}" redis-cli HSET task:progress:2001 190 'doing|2|3' 1902 'done|4|5' >/dev/null
    docker exec "${r}" redis-cli EXPIRE task:progress:2001 900 >/dev/null
    # uid=2005 ⇒ 第二段非法 ⇒ 1902 进度回落 0（190 仍是 2）
    docker exec "${r}" redis-cli HSET task:progress:2005 190 'doing|2|3' 1902 'done|abc|5' >/dev/null
    docker exec "${r}" redis-cli EXPIRE task:progress:2005 900 >/dev/null
    # uid=2006 ⇒ 段数≠3 ⇒ 190 进度回落 0（1902 仍是 4）
    docker exec "${r}" redis-cli HSET task:progress:2006 190 'doing|2' 1902 'done|4|5' >/dev/null
    docker exec "${r}" redis-cli EXPIRE task:progress:2006 900 >/dev/null
}

seed_pg() {      # $1 = 容器名  $2 = 库名
    local p="$1" db="$2"
    # uid=1002 ⇒ 只有 PG 有行 ⇒ 走「Redis 未命中 ⇒ 回源」那条路径（进度 7 / 9，与 uid=1001 刻意不同）
    docker exec -i "${p}" psql -U "${PG_USER}" -d "${db}" -q <<'SQL' >/dev/null
INSERT INTO task_progress (user_id, task_id, status, progress, target)
VALUES (1002, 190, 'doing', 7, 3), (1002, 1902, 'done', 9, 5);
INSERT INTO user_like_count (user_id, like_count, updated_at)
VALUES ('1001', 42, TIMESTAMPTZ '2026-01-01 00:00:00+00');
-- gRPC 面**专用** uid=2002 ⇒ 只有 PG 有行 ⇒ 走「Redis 未命中 ⇒ 回源」那条路径
-- （进度 7 / 9，与 uid=1002 刻意不同 ⇒ 两面的读数不会互相顶掉）
INSERT INTO task_progress (user_id, task_id, status, progress, target)
VALUES (2002, 190, 'doing', 7, 3), (2002, 1902, 'done', 9, 5);
SQL
}

seed_redis "${B_REDIS_CONTAINER}"
seed_redis "${C_REDIS_CONTAINER}"
seed_pg "${B_PG_CONTAINER}" "${B_DB}"
seed_pg "${C_PG_CONTAINER}" "${C_DB}"
say "   ✓ 播种完成（HTTP 面 uid 1001/1005/1006 在 Redis · 1002 在 PG · user_like_count 一行；gRPC 面 uid 2001/2005/2006 在 Redis · 2002 在 PG）"

# ══════════════════════════ 8. M1 HTTP 字节面 ══════════════════════════════════
step "8/10 M1 HTTP 字节面（21 条用例 · B↔C 逐字节）"
EXPECTED_ARG=()
if [ "${FREEZE}" = "1" ]; then
    EXPECTED_ARG=(--freeze "${HERE}/expected.txt" --freeze-known "${HERE}/known-readings.txt"
                  --known-cases "${HERE}/known-cases.txt"
                  --known-readings "${HERE}/known-readings.txt")
    say "   · FREEZE=1 ⇒ 重新冻结 B 侧读数到 expected.txt（＋ 已知差异的 C 侧形状到 known-readings.txt）"
else
    EXPECTED_ARG=(--expected "${HERE}/expected.txt"
                  --known-cases "${HERE}/known-cases.txt"
                  --known-readings "${HERE}/known-readings.txt")
    say "   · 同时校验冻结锚 expected.txt ＋ 已知差异白名单 known-cases.txt"
fi

python3 "${HERE}/http_probe.py" compare \
    --b-port "${B_API_PORT}" --c-port "${C_HTTP_PORT}" \
    --cases "${HERE}/cases.txt" \
    --out "${REPORT_OUT}" \
    "${EXPECTED_ARG[@]+"${EXPECTED_ARG[@]}"}"
M1_RC=$?
if [ "${M1_RC}" -ne 0 ]; then
    say "   !! M1 有差异 ⇒ 明细："
    cat "${WORK}/m1-report.txt"
fi

# ══════════════════════════ 9. gRPC 字节面（U-6）═════════════════════════════════
step "9/10 gRPC 字节面（7 条用例 · B↔C 逐字节）"

# 🔴 **探针的 classpath 从 C 侧 fat jar 的 BOOT-INF/lib/ 解出来**
#    ⇒ 版本与「真正在跑的那套依赖」**完全一致**（不引入 ~/.m2 的第二份版本来源）
#    ⇒ 且 proto jar 也从里面取 ⇒ 探针用的就是**当次构建**的那份 pb
python3 "${HERE}/grpc_probe.py" build \
    --fat-jar "${C_JAR}" --work "${WORK}/grpc" \
    || die "gRPC 探针构建失败"

GRPC_EXPECTED_ARG=()
if [ "${FREEZE}" = "1" ]; then
    GRPC_EXPECTED_ARG=(--freeze "${HERE}/grpc_expected.txt"
                       --freeze-known "${HERE}/grpc_known-readings.txt"
                       --known-cases "${HERE}/grpc_known-cases.txt"
                       --known-readings "${HERE}/grpc_known-readings.txt")
    say "   · FREEZE=1 ⇒ 重新冻结 B 侧读数到 grpc_expected.txt"
else
    GRPC_EXPECTED_ARG=(--expected "${HERE}/grpc_expected.txt"
                       --known-cases "${HERE}/grpc_known-cases.txt"
                       --known-readings "${HERE}/grpc_known-readings.txt")
    say "   · 同时校验冻结锚 grpc_expected.txt ＋ 已知差异白名单 grpc_known-cases.txt"
fi

python3 "${HERE}/grpc_probe.py" compare \
    --b-port "${B_RPC_PORT}" --c-port "${C_GRPC_PORT}" \
    --cases "${HERE}/grpc_cases.txt" \
    --work "${WORK}/grpc" \
    --out "${GRPC_REPORT_OUT}" \
    "${GRPC_EXPECTED_ARG[@]+"${GRPC_EXPECTED_ARG[@]}"}"
GRPC_RC=$?
if [ "${GRPC_RC}" -ne 0 ]; then
    say "   !! gRPC 面有差异 ⇒ 明细："
    cat "${GRPC_REPORT_OUT}"
fi

# ══════════════════════════ 10. M2 / M3 状态面 ══════════════════════════════════
step "10/10 M2 Redis 状态面 ＋ M3 PG 状态面"

STATE_FAIL=0

pg_state() {      # $1 = 容器名  $2 = 库名
    docker exec "${1}" psql -U "${PG_USER}" -d "${2}" -Atc \
        "SELECT 'task_progress', user_id, task_id, coalesce(status,'<null>'), coalesce(progress,-1), coalesce(target,-1) FROM task_progress ORDER BY user_id, task_id;" \
        2>/dev/null | tr -d '\r'
    docker exec "${1}" psql -U "${PG_USER}" -d "${2}" -Atc \
        "SELECT 'user_like_count', user_id, coalesce(like_count,-1), to_char(updated_at,'YYYY-MM-DD HH24:MI:SSOF') FROM user_like_count ORDER BY user_id;" \
        2>/dev/null | tr -d '\r'
}

redis_state() {   # $1 = 容器名 ⇒ 打印「key 的 HGETALL（排序）＋ EXISTS ＋ TTL」
    local r="$1" k
    for k in task:progress:1001 task:progress:1002 task:progress:1003 task:progress:1005 task:progress:1006 \
             task:progress:2001 task:progress:2002 task:progress:2003 task:progress:2005 task:progress:2006; do
        printf 'KEY %s exists=%s ttl=%s\n' "${k}" \
            "$(docker exec "${r}" redis-cli EXISTS "${k}" | tr -d '\r')" \
            "$(docker exec "${r}" redis-cli TTL "${k}" | tr -d '\r')"
        docker exec "${r}" redis-cli HGETALL "${k}" 2>/dev/null | sort | tr -d '\r' | sed 's/^/  /'
    done
}

redis_state "${B_REDIS_CONTAINER}" >"${WORK}/redis-b.txt"
redis_state "${C_REDIS_CONTAINER}" >"${WORK}/redis-c.txt"
pg_state "${B_PG_CONTAINER}" "${B_DB}"  >"${WORK}/pg-b.txt"
pg_state "${C_PG_CONTAINER}" "${C_DB}"  >"${WORK}/pg-c.txt"

# ── M2：Redis 内容必须逐字节相同（TTL 允许 ±10s 的**测量抖动**，不是语义差）────
b_hash="$(grep '^  ' "${WORK}/redis-b.txt" || true)"
c_hash="$(grep '^  ' "${WORK}/redis-c.txt" || true)"
if [ "${b_hash}" != "${c_hash}" ]; then
    say "   !! M2 Redis 内容不一致："
    diff <(printf '%s\n' "${b_hash}") <(printf '%s\n' "${c_hash}") | head -30
    STATE_FAIL=1
else
    say "   ✓ M2 Redis 内容两侧一致"
fi

# ── M2-b：逐 key 校验「存在性 + TTL」
#    · exists=1 ⇒ TTL 必须 > 0，且两侧相差 ≤ 10s（**测量抖动**，不是语义差）；
#    · exists=0 ⇒ TTL 必须是 -2（Redis 对不存在的 key 返回 -2）⇒
#      这正是「DB 无行时对不存在的 key 设 EXPIRE 是 **no-op**」那条 B 侧行为。
while read -r _kw key b_exists b_ttl; do
    b_exists="${b_exists#exists=}"; b_ttl="${b_ttl#ttl=}"
    c_line="$(grep "^KEY ${key} " "${WORK}/redis-c.txt" || true)"
    read -r _c_kw _c_key c_exists c_ttl <<< "${c_line}"
    c_exists="${c_exists#exists=}"; c_ttl="${c_ttl#ttl=}"
    if [ "${b_exists}" != "${c_exists}" ]; then
        say "   !! M2 存在性不一致：${key} B=${b_exists} C=${c_exists}"
        STATE_FAIL=1
        continue
    fi
    if [ "${b_exists}" = "0" ]; then
        # 不存在 ⇒ TTL 必须是 -2；**不允许**被回填成一个空 marker
        if [ "${b_ttl}" != "-2" ] || [ "${c_ttl}" != "-2" ]; then
            say "   !! M2 ${key} 不存在但 TTL 非 -2：B=${b_ttl} C=${c_ttl}（EXPIRE 对不存在的 key 必须是 no-op）"
            STATE_FAIL=1
        fi
        continue
    fi
    if [ "${b_ttl}" -le 0 ] || [ "${c_ttl}" -le 0 ]; then
        say "   !! M2 TTL 异常：${key} B=${b_ttl} C=${c_ttl}（存在的 key 必须有正 TTL）"
        STATE_FAIL=1
    elif [ $(( b_ttl > c_ttl ? b_ttl - c_ttl : c_ttl - b_ttl )) -gt 10 ]; then
        say "   !! M2 TTL 相差过大：${key} B=${b_ttl} C=${c_ttl}"
        STATE_FAIL=1
    fi
done < <(grep '^KEY ' "${WORK}/redis-b.txt")
say "   ✓ M2 TTL / 存在性两侧一致（容忍 ≤10s 测量抖动）"

# ── M3：PG 行必须逐字节相同（读路径**不得**写库）
if ! diff -q "${WORK}/pg-b.txt" "${WORK}/pg-c.txt" >/dev/null; then
    say "   !! M3 PG 行不一致："
    diff "${WORK}/pg-b.txt" "${WORK}/pg-c.txt" | head -30
    STATE_FAIL=1
else
    say "   ✓ M3 PG 行两侧一致（读路径未写库）"
fi

# ══════════════════════════ 收尾 ═══════════════════════════════════════════════
step "结论"
if [ "${M1_RC}" -eq 0 ] && [ "${GRPC_RC}" -eq 0 ] && [ "${STATE_FAIL}" -eq 0 ]; then
    say "   ✅ 双侧逐字节一致（M1 HTTP ＋ **gRPC 字节面** ＋ M2 Redis ＋ M3 PG）"
    exit 0
fi
say "   ❌ 有差异：M1_RC=${M1_RC} · GRPC_RC=${GRPC_RC} · STATE_FAIL=${STATE_FAIL}"
exit 1
