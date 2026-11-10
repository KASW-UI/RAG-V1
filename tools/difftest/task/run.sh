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
# 退出码：0 = 双侧逐字节一致 · 1 = 有真差异 · 2 = 环境问题 / 依赖缺失 / 真源漂移 ·
#         3 = **oracle 无效读数**（oracle 自己没答上来 ⇒ 本轮不可判 ⇒ 需重跑）。
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

# 🔴 **必须先等 Nacos 就绪**（2026-11-10 补记）：
#    Sea-nacos 是 **Spring 应用** ⇒ 冷启动要 **~200 s**（实测 `Root WebApplicationContext`
#    initialization completed in 67150~115538 ms`）⇒ ⇒ Docker 刚重启 / 容器刚拉起时，
#    **API 还没起来** ⇒ ⇒ 此时建命名空间必然失败 ⇒ ⇒ 而原来的报错是
#    「Nacos 命名空间没建出来（Sea-nacos 起没起？）」⇒ **既没等、也没说清要等多久** ⇒
#    ⇒ ⇒ 改成**显式轮询就绪**（readiness=200）⇒ 给出「还在启动，等了多久」的进度。
#    ⚠️ **容器的 healthcheck 标 unhealthy 不代表 API 不可用** ⇒ **以 API 读数为准**
#       （实测：容器 healthcheck 常年 unhealthy，但 readiness API 正常返回 200）。
NACOS_WAIT="${NACOS_WAIT:-300}"
nacos_ready() {
    [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' \
        "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/health/readiness" 2>/dev/null)" = "200" ]
}
if ! nacos_ready; then
    say "   · 等 Nacos 就绪（最多 ${NACOS_WAIT}s · Sea-nacos 冷启动实测 ~200s）…"
    nacos_waited=0
    while [ "${nacos_waited}" -lt "${NACOS_WAIT}" ]; do
        sleep 5
        nacos_waited=$((nacos_waited + 5))
        if nacos_ready; then
            say "   ✓ Nacos 已就绪（等了 ${nacos_waited}s）"
            break
        fi
        if [ $((nacos_waited % 30)) -eq 0 ]; then
            say "   · 仍在等 Nacos…（${nacos_waited}s / ${NACOS_WAIT}s）"
        fi
    done
fi
if ! nacos_ready; then
    die "Nacos（127.0.0.1:${NACOS_PORT}）等 ${NACOS_WAIT}s 仍未就绪 ⇒ **环境问题**（不是本域缺陷）⇒ 需先让 Sea-nacos 起来"
fi

curl -s -m 10 -X POST "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/namespaces" \
    --data-urlencode "customNamespaceId=${NACOS_NAMESPACE}" \
    --data-urlencode "namespaceName=${NACOS_NAMESPACE}" \
    --data-urlencode "namespaceDesc=task parity probe" >/dev/null 2>&1
NS_LIST="$(curl -s -m 10 "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/namespaces" 2>/dev/null)"
case "${NS_LIST}" in
    *"\"namespace\":\"${NACOS_NAMESPACE}\""*) : ;;
    *) die "Nacos 命名空间 ${NACOS_NAMESPACE} 没建出来（Nacos 已就绪，但建命名空间失败）" ;;
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
# ── 记下「播种窗口」的起点 ⇒ TTL 判据要用它（见步骤 10 的说明）────────────────
# 🔴 **为什么记它**：判「TTL 口径」需要一个「这个 key 最多可能有多旧」的**上界**
#    ⇒ 而那个上界就是「本次探针从播种到检查过了多久」⇒ ⇒ **与机器负载无关地自动放大**
#    ⇒ ⇒ 判据**不会**因为机器忙而假红。
SEED_TS="$(date +%s)"

say "   ✓ 播种完成（HTTP 面 uid 1001/1005/1006 在 Redis · 1002 在 PG · user_like_count 一行；gRPC 面 uid 2001/2005/2006 在 Redis · 2002 在 PG）"

# ── 🔴 预热（**消除冷启动**）────────────────────────────────────────────────
# 🔴 **为什么必须预热**（2026-11-10 实测）：
#    B 侧 task-api → task-rpc 是**跨进程**调用，且出向调用带 **2s deadline**
#    （B 侧 `service/task/task-api/src/main/resources/application.yml:270`）。
#    ⇒ **JVM 刚起**时，第一次请求要走「类加载 + JIT + 连接建立」⇒
#    ⇒ 这一跳**首次调用**就能超过 2s ⇒ ⇒ B 侧自己吐 **504**。
#    ⇒ 而实测**每次都是第一个用例**（`ok-redis-hit`）挂 ⇒ ⇒ **不是偶发，是必然**。
#    ⇒ ⇒ ⇒ **「重跑一次就好」是错的**（每次重跑都是新 JVM ⇒ 每次第一个用例都挂）
#    ⇒ ⇒ ⇒ 探针会**永远** EXIT=3 ⇒ ⇒ **必须先预热**。
#
# 🔴 **预热为什么用 9999 这个 uid**：
#    · 它**不在**任何用例表里 ⇒ 不会被量 ⇒ 不会污染读数；
#    · 两侧对它都走「Redis 未命中 ⇒ PG 无行」这条路 ⇒
#      ⇒ 对**不存在的** key 设 EXPIRE 是 **no-op** ⇒ **不产生任何 key** ⇒ 状态面零副作用；
#    · ⇒ 预热**既不污染读数，也不污染 Redis / PG 状态**。
WARMUP_UID=9999
say "   · 预热（uid=${WARMUP_UID} · 两侧各一次 · 消除 JVM 冷启动）"
# 📌 **gRPC 探针要先建好**（预热要用同一个探针 ⇒ 不能等到步骤 9 才建）
python3 "${HERE}/grpc_probe.py" build \
    --fat-jar "${C_JAR}" --work "${WORK}/grpc" \
    || die "gRPC 探针构建失败"
warmup_http() {   # $1 = 端口  $2 = 标签
    python3 "${HERE}/http_probe.py" probe "$1" POST /taskcenter/v1/task/get \
        application/json "{\"id\":\"${WARMUP_UID}\"}" >/dev/null 2>&1 || true
}
warmup_http "${B_API_PORT}" "B api"
warmup_http "${C_HTTP_PORT}" "C http"
# gRPC 面同样要预热（B 侧 task-rpc 也是刚起的 JVM）
GRPC_WARMUP_CASES="${WORK}/grpc-warmup.txt"
printf 'warmup\t%s\n' "${WARMUP_UID}" > "${GRPC_WARMUP_CASES}"
warmup_grpc() {   # $1 = 端口
    python3 "${HERE}/grpc_probe.py" probe --port "$1" --cases "${GRPC_WARMUP_CASES}" \
        --work "${WORK}/grpc" >/dev/null 2>&1 || true
}
warmup_grpc "${B_RPC_PORT}"
warmup_grpc "${C_GRPC_PORT}"
# 🔴 **断言预热没有留下状态副作用**（否则「预热」自己就污染了状态面 ⇒ 假红）
for r in "${B_REDIS_CONTAINER}" "${C_REDIS_CONTAINER}"; do
    leftover="$(docker exec "${r}" redis-cli EXISTS "task:progress:${WARMUP_UID}" 2>/dev/null | tr -d '\r')"
    if [ "${leftover}" != "0" ]; then
        die "预热 uid=${WARMUP_UID} 在 ${r} 里留下了 key ⇒ 预热污染了状态面 ⇒ 判据失效"
    fi
done
say "   ✓ 预热完成（且断言未留下任何 Redis key ⇒ 状态面零污染）"

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
case "${M1_RC}" in
    0) : ;;
    1) say "   !! M1 有差异 ⇒ 明细："; cat "${WORK}/m1-report.txt" ;;
    3) say "   ⚠️ M1 有**不可判**用例（oracle 侧没给出业务应答）⇒ 不是「C 侧有差异」⇒ 需重跑："
       cat "${WORK}/m1-report.txt" ;;
    *) say "   !! M1 探针异常（EXIT=${M1_RC}）"; cat "${WORK}/m1-report.txt" 2>/dev/null || true ;;
esac

# ══════════════════════════ 9. gRPC 字节面（U-6）═════════════════════════════════
step "9/10 gRPC 字节面（7 条用例 · B↔C 逐字节）"

# 🔴 **探针的 classpath 从 C 侧 fat jar 的 BOOT-INF/lib/ 解出来**
#    ⇒ 版本与「真正在跑的那套依赖」**完全一致**（不引入 ~/.m2 的第二份版本来源）
#    ⇒ 且 proto jar 也从里面取 ⇒ 探针用的就是**当次构建**的那份 pb
# 📌 **这一步已在步骤 7 的预热之前建好**（预热要用同一个探针 ⇒ 不能等到这里才建）
#    ⇒ 这里只做**幂等重建**（万一预热那次没建成 ⇒ 这里补上）。
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
case "${GRPC_RC}" in
    0) : ;;
    1) say "   !! gRPC 面有差异 ⇒ 明细："; cat "${GRPC_REPORT_OUT}" ;;
    3) say "   ⚠️ gRPC 面有**不可判**用例（oracle 侧没给出业务应答）⇒ 不是「C 侧有差异」⇒ 需重跑："
       cat "${GRPC_REPORT_OUT}" ;;
    *) say "   !! gRPC 探针异常（EXIT=${GRPC_RC}）"; cat "${GRPC_REPORT_OUT}" 2>/dev/null || true ;;
esac

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

# ── 🔴 **本轮「不可判」的 uid 集合**（2026-11-10 补 · 与探针的 INVALID 档配套）──
#    oracle 侧某条用例**没给出业务应答**时 ⇒ oracle 那次读**有没有把缓存回填进
#    Redis**是**不确定**的（它在跨进程 hop 超时那一刻可能已经写了，也可能没写）
#    ⇒ ⇒ **那个 uid 的 Redis 状态不可判** ⇒ 既不能判「一致」，也不能判「不一致」。
#    ⇒ ⇒ 这里把「本轮判为 INVALID 的用例」映射成 uid ⇒ M2 对这些 key **跳过并标注**。
#
#    ⚠️ **为什么不能整面跳过**：整面跳过会把**其它** uid 的真差异一起吞掉
#       ⇒ ⇒ 只跳过**受影响的那些 uid** ⇒ 其余照判。
INVALID_UIDS="$(python3 - "${WORK}/m1-report.txt" "${WORK}/grpc-report.txt" \
    "${HERE}/cases.txt" "${HERE}/grpc_cases.txt" <<'PY'
import re, sys, json
report_http, report_grpc, cases_http, cases_grpc = sys.argv[1:5]


def names_of(path):
    """从探针明细里挑出判为 INVALID 的用例名。"""
    out = set()
    cur = None
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.rstrip("\n")
                m = re.match(r"^### (\S+)\s*$", line)
                if m:
                    cur = m.group(1)
                    continue
                if line.startswith("  => INVALID") and cur:
                    out.add(cur)
    except OSError:
        pass
    return out


def rows_of(path):
    """读用例表 ⇒ {用例名: 原始字段列表}。"""
    out = {}
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.rstrip("\n")
                if not line.strip() or line.lstrip().startswith("#"):
                    continue
                fields = line.split("\t")
                out[fields[0].strip()] = fields
    except OSError:
        pass
    return out


uids = set()
http_cases = rows_of(cases_http)
for name in names_of(report_http):
    fields = http_cases.get(name)
    if not fields or len(fields) != 5:
        continue
    try:
        body = json.loads(fields[4])
    except Exception:
        continue
    if isinstance(body, dict) and "id" in body:
        uids.add(str(body["id"]).strip())

grpc_cases = rows_of(cases_grpc)
for name in names_of(report_grpc):
    fields = grpc_cases.get(name)
    if fields and len(fields) == 2:
        uids.add(fields[1].strip())

print(" ".join(sorted(uids)))
PY
)"
if [ -n "${INVALID_UIDS}" ]; then
    say "   ⚠️ 本轮**不可判**的 uid：${INVALID_UIDS}（oracle 侧没给出业务应答 ⇒ 其 Redis 状态不可判）"
else
    say "   ✓ 本轮无不可判 uid（两侧都给出了业务应答）"
fi

# ── M2：Redis **内容**必须逐字节相同（逐 key 比，不是整段比）
#    🔴 **逐 key 比的理由**：整段比一旦有一处不同就整段红 ⇒ 无法定位是哪条用例引起的
#       ⇒ 且无法对「受影响的 uid」单独跳过 ⇒ ⇒ 改成**逐 key**判（输出可定位）。
redis_hash_of() {   # $1 = redis-b.txt/c.txt  $2 = key ⇒ 打印该 key 的 HGETALL 块（排序）
    # ⚠️ 用 index() 而不是正则匹配（key 里有 `:`，正则里要转义 ⇒ 容易写错 ⇒ 静默取不到块）
    awk -v k="KEY ${2} " '
        index($0, k) == 1 { inblock = 1; next }
        /^KEY / { inblock = 0 }
        inblock && /^  / { print }
    ' "$1"
}
for key in task:progress:1001 task:progress:1002 task:progress:1003 task:progress:1005 task:progress:1006 \
             task:progress:2001 task:progress:2002 task:progress:2003 task:progress:2005 task:progress:2006; do
    uid="${key##*:}"
    b_h="$(redis_hash_of "${WORK}/redis-b.txt" "${key}")"
    c_h="$(redis_hash_of "${WORK}/redis-c.txt" "${key}")"
    if [ "${b_h}" != "${c_h}" ]; then
        case " ${INVALID_UIDS} " in
            *" ${uid} "*)
                say "   ⚠️ M2 ${key} 内容不一致，但该 uid 本轮**不可判**（oracle 侧无业务应答）⇒ 跳过"
                ;;
            *)
                say "   !! M2 Redis 内容不一致：${key}"
                diff <(printf '%s\n' "${b_h}") <(printf '%s\n' "${c_h}") | head -10
                STATE_FAIL=1
                ;;
        esac
    fi
done
if [ "${STATE_FAIL}" -eq 0 ]; then
    say "   ✓ M2 Redis 内容两侧一致"
fi

# ── M2-b：逐 key 校验「存在性 + TTL」
#    · exists=1 ⇒ TTL 必须 > 0，且两侧相差 ≤ 10s（**测量抖动**，不是语义差）；
#    · exists=0 ⇒ TTL 必须是 -2（Redis 对不存在的 key 返回 -2）⇒
#      这正是「DB 无行时对不存在的 key 设 EXPIRE 是 **no-op**」那条 B 侧行为。
#
# ── TTL 判据的窗口（**实测**，不是拍脑袋）────────────────────────────────────
#    窗口 = 「播种完成 → 现在」过了多久 ⇒ ⇒ **随机器负载自动放大** ⇒ 不会假红。
TTL_WINDOW=$(( $(date +%s) - SEED_TS + 10 ))
TTL_FLOOR=$(( 900 - TTL_WINDOW ))
[ "${TTL_FLOOR}" -lt 0 ] && TTL_FLOOR=0
while read -r _kw key b_exists b_ttl; do
    b_exists="${b_exists#exists=}"; b_ttl="${b_ttl#ttl=}"
    c_line="$(grep "^KEY ${key} " "${WORK}/redis-c.txt" || true)"
    read -r _c_kw _c_key c_exists c_ttl <<< "${c_line}"
    c_exists="${c_exists#exists=}"; c_ttl="${c_ttl#ttl=}"
    uid="${key##*:}"
    # 🔴 **不可判的 uid ⇒ 存在性/TTL 也不可判**（oracle 侧那次读可能没回填、也可能填了）
    #    ⇒ ⇒ 跳过并标注，**不判失败**（冤枉 C 侧）。
    case " ${INVALID_UIDS} " in
        *" ${uid} "*)
            say "   ⚠️ M2 ${key} 存在性/TTL 不可判（该 uid 本轮 oracle 侧无业务应答）⇒ 跳过"
            continue
            ;;
    esac
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
        continue
    fi
    # ── 🔴 **负载免疫的「TTL 口径」判据**（2026-11-10 补记 · 改掉原判据）：
    #    每个**存在**的 key，两侧各自的 TTL 都必须落在 **[900 - 窗口时长, 900]**：
    #      · 上界 **900** = 两侧的 TTL 口径都是 **15m**（B 侧 `TaskRedisKeys.PROGRESS_CACHE_TTL`）
    #        ⇒ 「口径」判据 ⇒ **与机器负载无关**；
    #      · 下界 **900 - 窗口时长** = 这个 key **最早**可能是「播种/回填」那一刻写的
    #        ⇒ 「写入时刻在本次探针窗口内」⇒ ⇒ 窗口随负载自动放大 ⇒ **不会假红**。
    #    ⚠️ **原判据「两侧 TTL 绝对差 > 10s ⇒ 失败」是「时序」判据，不是「口径」判据**
    #       ⇒ 两侧的写入天然错开**一个请求往返**（探针先发 B、等它返回、再发 C）
    #       ⇒ 机器一忙，这个往返就能到 15~20s ⇒ ⇒ **把时序误判成行为差异** ⇒ 假红。
    #       （实测：本机空闲时差 <2s；Docker 刚起 + Nacos 启动时差 15~20s ⇒ 8~10 条 key 误报）
    #    ⇒ ⇒ 而本判据**每侧各验各的** ⇒ ⇒ 顺带**蕴含**「两侧口径一致」（都在同一个带里）。
    if [ "${b_ttl}" -gt 900 ] || [ "${c_ttl}" -gt 900 ]; then
        say "   !! M2 TTL 口径不是 900s：${key} B=${b_ttl} C=${c_ttl}（两侧 PROGRESS_CACHE_TTL = 15m = 900s）"
        STATE_FAIL=1
        continue
    fi
    if [ "${b_ttl}" -lt "${TTL_FLOOR}" ]; then
        say "   !! M2 TTL 比窗口下限还小：${key} B=${b_ttl} C=${c_ttl}（下限 ${TTL_FLOOR} = 900 - 本次探针窗口 ${TTL_WINDOW}s）"
        STATE_FAIL=1
        continue
    fi
    if [ "${c_ttl}" -lt "${TTL_FLOOR}" ]; then
        say "   !! M2 TTL 比窗口下限还小：${key} B=${b_ttl} C=${c_ttl}（下限 ${TTL_FLOOR} = 900 - 本次探针窗口 ${TTL_WINDOW}s）"
        STATE_FAIL=1
        continue
    fi
done < <(grep '^KEY ' "${WORK}/redis-b.txt")
say "   ✓ M2 TTL / 存在性两侧一致（口径判据：两侧 TTL ∈ [${TTL_FLOOR}, 900] · 窗口 ${TTL_WINDOW}s）"

# ── M3：PG 行必须逐字节相同（读路径**不得**写库）
#    ⚠️ PG 侧**不受**不可判 uid 影响：读路径本来就**不写库** ⇒
#    ⇒ oracle 那次读没答上来 ⇒ PG 里只会是「播种的行」⇒ 两侧仍应逐字节相同
#    ⇒ ⇒ M3 保持严格判（不跳过）。
if ! diff -q "${WORK}/pg-b.txt" "${WORK}/pg-c.txt" >/dev/null; then
    say "   !! M3 PG 行不一致："
    diff "${WORK}/pg-b.txt" "${WORK}/pg-c.txt" | head -30
    STATE_FAIL=1
else
    say "   ✓ M3 PG 行两侧一致（读路径未写库）"
fi

# ══════════════════════════ 收尾 ═══════════════════════════════════════════════
step "结论"
# 🔴 **三档结论**（2026-11-10 补）：探针现在会区分「真差异」与「oracle 无效读数」。
#    · EXIT=1 = 有真差异 ⇒ **C 侧确实与 oracle 不一致** ⇒ 要修；
#    · EXIT=3 = 有不可判用例 ⇒ **oracle 自己没答上来** ⇒ 不是 C 侧的锅 ⇒ 需重跑；
#    · ⇒ **两者必须分开报** —— 混为一谈就是「冤枉 C 侧」。
if [ "${M1_RC}" -eq 0 ] && [ "${GRPC_RC}" -eq 0 ] && [ "${STATE_FAIL}" -eq 0 ]; then
    say "   ✅ 双侧逐字节一致（M1 HTTP ＋ **gRPC 字节面** ＋ M2 Redis ＋ M3 PG）"
    exit 0
fi
if [ "${M1_RC}" -eq 3 ] || [ "${GRPC_RC}" -eq 3 ]; then
    say "   ⚠️ **oracle 侧有无效读数**（M1_RC=${M1_RC} · GRPC_RC=${GRPC_RC}）"
    say "      ⇒ 这些用例本轮**不可判**（不是「C 侧有差异」）⇒ 结论不可信 ⇒ **需重跑**"
    if [ "${M1_RC}" -eq 1 ] || [ "${GRPC_RC}" -eq 1 ] || [ "${STATE_FAIL}" -eq 1 ]; then
        say "   ❌ 同时存在真差异（M1_RC=${M1_RC} · GRPC_RC=${GRPC_RC} · STATE_FAIL=${STATE_FAIL}）⇒ 以真差异为准"
        exit 1
    fi
    exit 3
fi
say "   ❌ 有差异：M1_RC=${M1_RC} · GRPC_RC=${GRPC_RC} · STATE_FAIL=${STATE_FAIL}"
exit 1
