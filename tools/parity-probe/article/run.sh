#!/usr/bin/env bash
# =============================================================================
# run.sh —— article 域「B 侧 ↔ C 侧」**双侧活体对拍**探针（HTTP 面 · 逐字节）
#
# 对比双方：
#   **B 侧**（唯一 oracle · 只读）= /Users/pr/Documents/java&golang/RAG
#       · 形态 = article-api（HTTP 进程） + article-rpc（gRPC 进程）**两个进程**（Boot 4）
#       · 用**本地现成镜像** `sea/article-api:latest` / `sea/article-rpc:latest` 起
#         ⇒ **B 仓一个字节都不改**（连 `target/` 都不写 —— 镜像 31h 前构建于
#           B 仓 `service/article` 最后一次改动 2026-11-01 21:24 之后）
#   **C 侧**（被测）= 本仓工作树
#       · 形态 = **单进程**同起 Tomcat + gRPC（D1 合并 · D2 域内直连）
#       · 产物 = `mvn -o -pl service/article -am package` 的 fat jar
#
# 判定：同一请求 ⇒ **两侧响应逐字节比对** ⇒ 再与冻结基线 `expected.txt` 比对。
# 退出码：0 = 逐字节一致 · 1 = 有差异 · 2 = 环境问题（**与真差异严格分开**）。
#
# ⚠️ **gRPC 面不可直接对拍**（拓扑产物 · 不是行为漂移）：
#   B 侧 = 两个进程（api 通过 `discovery:///article-rpc` 走**跨进程 gRPC**）
#   C 侧 = 一个进程（api **直连**领域层，**没有网络跳** · D2）
#   ⇒ 两边的 gRPC 拓扑不同 ⇒ 对拍**只能落在 HTTP 面**（两边都对外暴露的同一契约）。
#
# 用法：
#   bash tools/parity-probe/article/run.sh
#   RUN_ID=A bash tools/parity-probe/article/run.sh       # 并发隔离
#   FREEZE=1 bash tools/parity-probe/article/run.sh       # 重建冻结基线
#   KEEP_WORK=1 bash tools/parity-probe/article/run.sh     # 保留临时目录
#
# 依赖：docker · java 21+ · mvn（离线 -o）· curl · xxd · python3 · lsof · nc。
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAG_V1_ROOT="$(cd "${HERE}/../../.." && pwd)"

# ── 「每轮独立」机制（工具面 · **不改产品代码**）────────────────────────────────
source "${HERE}/probe-isolation.sh"

# ⚠️ **基准端口要先登记** ⇒ 租约才知道「这一块会占哪些端口」⇒ 才能避开危险端口
#    （撞上容器发布口时 `lsof` 返回的是 Docker Desktop backend ⇒ 杀了 Docker 就没了）。
#    B 侧 api HTTP / B 侧 rpc gRPC / B 侧 rpc 观测口 / C 侧 HTTP / C 侧 gRPC
PROBE_BASE_APP_PORTS="18889 19001 19092 18893 19003"
# PG 阶梯：两侧族**交错**（B 侧偶数位 / C 侧奇数位）⇒ 同一块号下两族也不重叠
PROBE_BASE_PG_PORTS="55570 55572 55574 55576 55578 55580 55571 55573 55575 55577 55579 55581"
probe_iso_init parity || exit 2

# ── 宿主中间件（**共享**，来自 B 仓 compose 起的那套）───────────────────────────
NACOS_PORT="${NACOS_PORT:-49848}"      # Sea-nacos   49848→8848
KAFKA_PORT="${KAFKA_PORT:-49992}"      # Sea-kafka   49992→9093（EXTERNAL listener）
REDIS_PORT="${REDIS_PORT:-49380}"      # Sea-redis   49380→6379
JAEGER_PORT="${JAEGER_PORT:-49317}"    # Sea-jaeger  49317→4317
# 🔴 **Kafka 的 EXTERNAL listener 广播的是 `127.0.0.1:49992`** ⇒ **只对宿主有效**。
#    ⇒ B 侧（在容器里）必须用 compose DNS 名 `kafka:9092`（PLAINTEXT listener），
#      C 侧（在宿主上）用 `127.0.0.1:49992`。
#    ⚠️ 踩坑见 README「Kafka 的两个 listener」段。

# ── 两侧端口（偏移来自端口租约 ⇒ 并发跑两份时保证不撞）─────────────────────────
B_API_PORT="${B_API_PORT:-$(( 18889 + PROBE_PORT_OFFSET ))}"
B_RPC_PORT="${B_RPC_PORT:-$(( 19001 + PROBE_PORT_OFFSET ))}"
B_RPC_MGMT_PORT="${B_RPC_MGMT_PORT:-$(( 19092 + PROBE_PORT_OFFSET ))}"
C_HTTP_PORT="${C_HTTP_PORT:-$(( 18893 + PROBE_PORT_OFFSET ))}"
C_GRPC_PORT="${C_GRPC_PORT:-$(( 19003 + PROBE_PORT_OFFSET ))}"

# ── 两侧各自的 Postgres（**各一个容器** ⇒ Flyway 账本互不干扰）────────────────
B_PG_CONTAINER="parity-article-pg-b-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-}$$"
C_PG_CONTAINER="parity-article-pg-c-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-$$}"
# ── 探针**自带**一个 MinIO（不依赖宿主那套 ⇒ 不污染别人；凭据是探针本地的）────
MINIO_CONTAINER="parity-article-minio-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-$$}"
MINIO_PORT="${MINIO_PORT:-$(( 49110 + PROBE_PORT_OFFSET ))}"
MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-parityprobe}"
MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-parityprobe-local-secret}"
MINIO_BUCKET="articles"

# ── B 侧镜像（本地现成 ⇒ 不构建 B 侧）────────────────────────────────────────
B_API_IMAGE="${B_API_IMAGE:-sea/article-api:latest}"
B_RPC_IMAGE="${B_RPC_IMAGE:-sea/article-rpc:latest}"

# ── C 侧产物 ─────────────────────────────────────────────────────────────────
C_JAR_REL="service/article/target/article-service-1.0-SNAPSHOT.jar"
# ── 验签密钥（**两侧必须一致** ⇒ 否则鉴权路径不可比）─────────────────────────
JWT_SECRET="${JWT_SECRET:-ae0536f9-6450-4606-8e13-5a19ed505da0}"

# ⚠️ **目录名带探针标记**（`rag-article-parity.`）⇒ 端口预检能认出「哪些进程是探针自己的」
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rag-article-parity.XXXXXX")"
C_PID=""
say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }

dump_logs() {
  local f
  for f in c-side.log; do
    [ -f "${WORK:-/nonexistent}/${f}" ] || continue
    printf '\n---- %s（末 30 行）----\n' "${f}" >&2
    tail -30 "${WORK}/${f}" | sed 's/^/   | /' >&2
  done
  # B 侧两个容器的日志只在 docker 里 ⇒ 这里直接捞
  for c in "${B_RPC_CONTAINER:-}" "${B_API_CONTAINER:-}"; do
    [ -n "${c}" ] || continue
    docker ps -a --format '{{.Names}}' | grep -qx "${c}" || continue
    printf '\n---- docker logs %s（末 30 行）----\n' "${c}" >&2
    docker logs "${c}" 2>&1 | tail -30 | sed 's/^/   | /' >&2
  done
}
die()  { printf '!! %s\n' "$*" >&2; dump_logs; exit 2; }

host_port_open() { nc -z -G 2 127.0.0.1 "$1" >/dev/null 2>&1; }

# ── 清理：**按端口反查 PID** ⇒ 即使 trap 没跑（被外部取消），下一轮也能按端口回收 ──
cleanup() {
  local p pid
  # ① 先按**端口**反查 PID（C 侧 JVM）—— 端口是我们自己的 ⇒ 命中即探针残留
  for p in "${C_HTTP_PORT}" "${C_GRPC_PORT}"; do
    for pid in $(lsof -nP -iTCP:"${p}" -sTCP:LISTEN -t 2>/dev/null); do
      kill -9 "${pid}" 2>/dev/null
    done
  done
  # ② 容器（B 侧两个 + 两个 PG + MinIO）
  for c in "${B_RPC_CONTAINER:-x}" "${B_API_CONTAINER:-x}" "${B_PG_CONTAINER:-x}" \
           "${C_PG_CONTAINER:-x}" "${MINIO_CONTAINER:-x}"; do
    [ "${c}" = "x" ] && continue
    docker rm -f "${c}" >/dev/null 2>&1
  done
  # ③ Nacos 残留：本轮**两个**命名空间里的实例必须注销
  probe_nacos_cleanup_ns "${PROBE_NS_B}" || true
  probe_nacos_cleanup_ns "${PROBE_NS_C}" || true
  # ④ 端口租约必须释放 ⇒ 否则下一轮会跳到下一块端口
  probe_iso_release
  if [ "${KEEP_WORK:-0}" = "1" ]; then
    say "   · KEEP_WORK=1 ⇒ 保留 ${WORK}"
  else
    say "   · 临时目录保留在 ${WORK}（本机 `rm -rf` 会被回收站守卫拦下 ⇒ 不删）"
  fi
}
trap cleanup EXIT INT TERM

# ══════════════════════════ 0. 前置检查（缺 ⇒ exit 2，绝不静默降级）════════════
step "0/9 前置检查"
for cmd in docker java mvn curl xxd shasum lsof nc python3; do
  command -v "${cmd}" >/dev/null 2>&1 || die "缺命令：${cmd}"
done
docker ps >/dev/null 2>&1 || die "docker 不可用"
for pair in "Sea-nacos:${NACOS_PORT}" "Sea-kafka:${KAFKA_PORT}" "Sea-redis:${REDIS_PORT}" "Sea-jaeger:${JAEGER_PORT}"; do
  c="${pair%%:*}"; p="${pair##*:}"
  docker ps --format '{{.Names}}' | grep -qx "${c}" || die "容器 ${c} 没在跑（宿主中间件缺失）"
  host_port_open "${p}" || die "宿主端口 ${p} 不通（${c} 的映射口）"
done
for img in "${B_API_IMAGE}" "${B_RPC_IMAGE}"; do
  docker image inspect "${img}" >/dev/null 2>&1 || die "B 侧镜像不存在：${img}（B 侧不构建 ⇒ 需要本地现成镜像）"
done
# 🔴 **B 侧容器必须与 compose 那套中间件在同一个 docker 网络**——
#    因为 Kafka 的 PLAINTEXT listener 广播的是 compose DNS 名 `kafka`。
B_SIDE_NETWORK="${B_SIDE_NETWORK:-$(docker inspect Sea-nacos --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' 2>/dev/null | head -1)}"
[ -n "${B_SIDE_NETWORK}" ] || die "找不到 compose 网络（Sea-nacos 不在任何网络里？）"
# 本轮两个 Nacos 命名空间（**必须两个** —— 两侧注册的服务名都是 `article-rpc`）
probe_nacos_ensure_namespace "${PROBE_NS_B}" || die "B 侧 Nacos 命名空间 ${PROBE_NS_B} 不可用"
probe_nacos_ensure_namespace "${PROBE_NS_C}" || die "C 侧 Nacos 命名空间 ${PROBE_NS_C} 不可用"
say "   ✓ 命令齐 / 中间件在跑 / B 侧镜像就绪"
say "   ✓ 隔离身份：B 侧命名空间=${PROBE_NS_B} · C 侧命名空间=${PROBE_NS_C} · 端口块=${PROBE_PORT_BLOCK}（偏移 ${PROBE_PORT_OFFSET}）· PG 偏移 ${PROBE_PG_OFFSET}"
say "   ✓ B 侧容器网络=${B_SIDE_NETWORK}"

# 端口预检：把**上一轮残留**的探针进程踢掉（否则 C 侧 JVM 起不来：Port already in use）
for p in "${B_API_PORT}" "${B_RPC_PORT}" "${B_RPC_MGMT_PORT}" "${C_HTTP_PORT}" "${C_GRPC_PORT}"; do
  probe_free_port "${p}"
done
for p in "${B_API_PORT}" "${B_RPC_PORT}" "${B_RPC_MGMT_PORT}" "${C_HTTP_PORT}" "${C_GRPC_PORT}"; do
  for _ in $(seq 1 15); do host_port_open "${p}" || break; sleep 1; done
  host_port_open "${p}" && die "端口 ${p} 仍被占用（不是探针进程 ⇒ 不杀）⇒ 请显式指定 PORT_OFFSET"
done

# ══════════════════════════ 1. 探针自带 MinIO + 两侧各自的 Postgres ═══════════
step "1/9 探针自带 MinIO（:${MINIO_PORT}）＋ 两侧各自的 Postgres"
start_minio() {
  docker rm -f "${MINIO_CONTAINER}" >/dev/null 2>&1
  docker run -d --name "${MINIO_CONTAINER}" \
    -e MINIO_ROOT_USER="${MINIO_ACCESS_KEY}" -e MINIO_ROOT_PASSWORD="${MINIO_SECRET_KEY}" \
    -p "${MINIO_PORT}:9000" minio/minio:latest minio server /data >/dev/null 2>&1 || return 1
  for _ in $(seq 1 40); do
    curl -s -m 3 "http://127.0.0.1:${MINIO_PORT}/minio/health/live" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
start_minio || die "探针 MinIO 起不来（:${MINIO_PORT}）"

start_pg() {   # $1 = 容器名  $2 = 宿主端口 ⇒ 成功返回 0（宿主端口已验证可连）
  local c="$1" p="$2"
  docker rm -f "${c}" >/dev/null 2>&1
  docker run -d --name "${c}" -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=sea \
    -e POSTGRES_DB=first_db -p "${p}:5432" postgres:latest >/dev/null 2>&1 || return 1
  # ⚠️ **就绪判据必须是「走 psql 真正会走的那条路」**（容器内 socket）——
  #    `pg_isready` 与 `psql` 的连接方式不一定一致（实测：`pg_isready` 过了，
  #    紧接着的 `psql` 仍报 `socket "/var/run/postgresql/.s.PGSQL.5432" failed: No such file`）
  #    ⇒ 所以这里直接跑一条真查询。
  for _ in $(seq 1 40); do
    docker exec "${c}" psql -U admin -d first_db -c 'SELECT 1' >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec "${c}" psql -U admin -d first_db -c 'SELECT 1' >/dev/null 2>&1 || return 1
  for _ in $(seq 1 15); do
    host_port_open "${p}" && return 0
    sleep 1
  done
  return 1
}
pick_pg_port() {   # $1 = 容器名  $2.. = 候选端口 ⇒ 成功时把端口打到 stdout
  local c="$1"; shift
  local p
  for p in "$@"; do
    if start_pg "${c}" "${p}"; then printf '%s' "${p}"; return 0; fi
    # 🔴 **这条必须写 stderr** ⇒ stdout 被 `$(pick_pg_port …)` 捕获 ⇒ 混进端口变量
    say "   · :${p} 起不来 ⇒ 换下一个" >&2
    docker rm -f "${c}" >/dev/null 2>&1
  done
  return 1
}
B_PG_CANDIDATES=($(probe_pg_ladder 55570 2 6))
C_PG_CANDIDATES=($(probe_pg_ladder 55571 2 6))
B_PG_PORT="$(pick_pg_port "${B_PG_CONTAINER}" ${B_PG_CANDIDATES[@]+${B_PG_CANDIDATES[@]}})" \
  || die "B 侧 PG 起不来（候选：${B_PG_CANDIDATES[*]}）"
C_PG_PORT="$(pick_pg_port "${C_PG_CONTAINER}" ${C_PG_CANDIDATES[@]+${C_PG_CANDIDATES[@]}})" \
  || die "C 侧 PG 起不来（候选：${C_PG_CANDIDATES[*]}）"
say "   ✓ MinIO :${MINIO_PORT} · B 侧 PG :${B_PG_PORT} · C 侧 PG :${C_PG_PORT}（宿主端口已验证可连）"

# ══════════════════════════ 2. 构建 C 侧（持锁 + 拷进本轮 WORK）════════════════
step "2/9 构建 C 侧（mvn 串行锁 + 拷进本轮 WORK ⇒ 与别的轮次互不干扰）"
build_c_side() {
  local helper="${RAG_MVN_LOCK_HELPER:-/tmp/rag-mvn-lock.py}"
  # ⚠️ **`-Dmaven.test.skip=true` 而不是 `-DskipTests`**：
  #   `-DskipTests` 只跳过**执行**、仍会**编译**测试代码 ⇒ 工作树里测试源码编译不过时整轮失败。
  #   而本探针只需要**运行期产物**（fat jar）⇒ 连测试编译都不必做。
  #   （实测 2026-11-03：article 工作树的测试源码当时编译不过 ⇒ 与探针无关。）
  # ⚠️ **`PROBE_SKIP_BUILD=1`** ⇒ 复用工作树里已有的 jar（**不碰 target/**）。
  #   用途：仓内其它 worker 正持有**全局 mvn 锁**时（实测：另一个 worker 在跑 surefire）
  #         探针不必排在它后面等锁 ⇒ 变异脚本可以快速连跑多轮。
  #   ⚠️ 用它之前必须确认 jar 存在（下面会断言）⇒ 不会拿着空产物去跑。
  if [ "${PROBE_SKIP_BUILD:-0}" = "1" ]; then
    say "   · PROBE_SKIP_BUILD=1 ⇒ 复用已有 jar（${C_JAR_REL}）"
    return 0
  fi
  if [ -f "${helper}" ]; then
    # 仓内约定的**全局** mvn 锁（多个 worker 并行写同一个 target/ 时用它）
    ( cd "${RAG_V1_ROOT}" && python3 "${helper}" mvn -o -q -pl service/article -am package -Dmaven.test.skip=true )
  else
    probe_mvn_lock_acquire || return 1
    ( cd "${RAG_V1_ROOT}" && mvn -o -q -pl service/article -am package -Dmaven.test.skip=true )
    local rc=$?
    probe_mvn_lock_release
    return $rc
  fi
}
build_c_side || die "C 侧构建失败"
C_JAR="${RAG_V1_ROOT}/${C_JAR_REL}"
[ -f "${C_JAR}" ] || die "C 侧 jar 没产出：${C_JAR}"
cp "${C_JAR}" "${WORK}/article-service.jar" || die "拷 C 侧 jar 失败"
C_JAR="${WORK}/article-service.jar"
say "   ✓ C 侧构建完成（jar 已拷进本轮 WORK）"

# ══════════════════════════ 3. 起 B 侧（rpc → 等注册 → api）════════════════════
step "3/9 起 B 侧 article-rpc（注册 Nacos ${PROBE_NS_B}）+ article-api（:${B_API_PORT}）"
# ⚠️ **B 侧在容器里 ⇒ 宿主中间件用 compose DNS 名**；PG 用宿主映射口（host.docker.internal）。
B_RPC_CONTAINER="parity-article-b-rpc-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-$$}"
B_API_CONTAINER="parity-article-b-api-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-$$}"
docker rm -f "${B_RPC_CONTAINER}" "${B_API_CONTAINER}" >/dev/null 2>&1
docker run -d --name "${B_RPC_CONTAINER}" --network "${B_SIDE_NETWORK}" \
  -e JAVA_OPTS="-XX:+UseContainerSupport -Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8" \
  -e POSTGRES_HOST=host.docker.internal -e POSTGRES_PORT="${B_PG_PORT}" \
  -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=sea -e POSTGRES_DB=first_db \
  -e NACOS_ADDR=nacos -e NACOS_PORT=8848 -e NACOS_NAMESPACE="${PROBE_NS_B}" \
  -e KAFKA_BROKERS=kafka:9092 \
  -e MINIO_ENDPOINT="host.docker.internal:${MINIO_PORT}" \
  -e MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY}" -e MINIO_SECRET_KEY="${MINIO_SECRET_KEY}" \
  -e REDIS_HOST=redis -e REDIS_PORT=6379 \
  -e OTEL_ADDR=jaeger -e OTLP_PORT=4317 \
  -e SNOWFLAKE_NODE_ID=3 \
  -e ARTICLE_RPC_PORT="${B_RPC_PORT}" -e ARTICLE_RPC_MANAGEMENT_PORT="${B_RPC_MGMT_PORT}" \
  -p "${B_RPC_PORT}:${B_RPC_PORT}" -p "${B_RPC_MGMT_PORT}:${B_RPC_MGMT_PORT}" \
  --memory=1g --restart=no \
  "${B_RPC_IMAGE}" >/dev/null || die "B 侧 article-rpc 容器起不来"
# ⚠️ **就绪判据必须精确到「本轮的端口」**（数「注册端口 == 本轮 gRPC 端口」的实例个数）
#    ⇒ 别的轮次 / 上一轮的残留实例**不会被数进来**（B 侧探针踩过这个坑：判据自匹配）。
for _ in $(seq 1 90); do
  [ "$(probe_nacos_instance_count "${B_RPC_PORT}" "${PROBE_NS_B}")" != "0" ] && break
  sleep 1
done
[ "$(probe_nacos_instance_count "${B_RPC_PORT}" "${PROBE_NS_B}")" != "0" ] \
  || die "B 侧 article-rpc 90s 内没以端口 ${B_RPC_PORT} 注册进 Nacos（命名空间 ${PROBE_NS_B}）"
say "   ✓ B 侧 article-rpc 已注册（命名空间 ${PROBE_NS_B} · 端口 ${B_RPC_PORT}）"

docker run -d --name "${B_API_CONTAINER}" --network "${B_SIDE_NETWORK}" \
  -e JAVA_OPTS="-XX:+UseContainerSupport -Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8" \
  -e POSTGRES_HOST=host.docker.internal -e POSTGRES_PORT="${B_PG_PORT}" \
  -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=sea -e POSTGRES_DB=first_db \
  -e NACOS_ADDR=nacos -e NACOS_PORT=8848 -e NACOS_NAMESPACE="${PROBE_NS_B}" \
  -e KAFKA_BROKERS=kafka:9092 \
  -e MINIO_ENDPOINT="host.docker.internal:${MINIO_PORT}" \
  -e MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY}" -e MINIO_SECRET_KEY="${MINIO_SECRET_KEY}" \
  -e REDIS_HOST=redis -e REDIS_PORT=6379 \
  -e OTEL_ADDR=jaeger -e OTLP_PORT=4317 \
  -e USER_JWT_SECRET="${JWT_SECRET}" \
  -e ARTICLE_API_PORT="${B_API_PORT}" \
  -p "${B_API_PORT}:${B_API_PORT}" \
  --memory=1g --restart=no \
  "${B_API_IMAGE}" >/dev/null || die "B 侧 article-api 容器起不来"
for _ in $(seq 1 90); do host_port_open "${B_API_PORT}" && break; sleep 1; done
host_port_open "${B_API_PORT}" || die "B 侧 article-api 没监听 :${B_API_PORT}"
say "   ✓ B 侧 article-api 已监听 :${B_API_PORT}"

# ══════════════════════════ 4. 起 C 侧（单进程：Tomcat + gRPC）═════════════════
step "4/9 起 C 侧 article-service（单进程 · Tomcat :${C_HTTP_PORT} + gRPC :${C_GRPC_PORT}）"
# ⚠️ **必须在「端口已空闲」之后起** ⇒ 否则 `Port already in use`（实测踩过：
#    上一轮 JVM 还在优雅关闭 ⇒ 新进程启动即 fatal）。
( cd "${WORK}" && exec env \
  POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${C_PG_PORT}" \
  POSTGRES_USER=admin POSTGRES_PASSWORD=sea POSTGRES_DB=first_db \
  KAFKA_BROKERS="127.0.0.1:${KAFKA_PORT}" \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE="${PROBE_NS_C}" \
  USER_JWT_SECRET="${JWT_SECRET}" \
  MINIO_ENDPOINT="127.0.0.1:${MINIO_PORT}" \
  MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY}" MINIO_SECRET_KEY="${MINIO_SECRET_KEY}" \
  MINIO_PUBLIC_BASE_URL="http://127.0.0.1:${MINIO_PORT}" \
  OTEL_ADDR=127.0.0.1 OTLP_PORT="${JAEGER_PORT}" \
  ARTICLE_HTTP_PORT="${C_HTTP_PORT}" ARTICLE_GRPC_PORT="${C_GRPC_PORT}" \
  java -Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8 -jar "${C_JAR}" \
  >"${WORK}/c-side.log" 2>&1 ) &
C_PID=$!
# ⚠️ `disown` ⇒ 清理时 kill 掉它**不会**在父 shell 打出 `Killed: 9` 的作业通知。
disown "${C_PID}" 2>/dev/null || true
for _ in $(seq 1 120); do host_port_open "${C_HTTP_PORT}" && break; sleep 1; done
host_port_open "${C_HTTP_PORT}" || die "C 侧没监听 :${C_HTTP_PORT}（见 ${WORK}/c-side.log）"
for _ in $(seq 1 60); do
  [ "$(probe_nacos_instance_count "${C_GRPC_PORT}" "${PROBE_NS_C}")" != "0" ] && break
  sleep 1
done
[ "$(probe_nacos_instance_count "${C_GRPC_PORT}" "${PROBE_NS_C}")" != "0" ] \
  || say "   ⚠️ C 侧没注册进 Nacos（命名空间 ${PROBE_NS_C}）⇒ 继续（C 侧 HTTP 面是域内直连，不依赖注册）"
say "   ✓ C 侧已监听 :${C_HTTP_PORT}（gRPC :${C_GRPC_PORT} · pid ${C_PID}）"

# ══════════════════════════ 5. 两侧同条件播种 ═════════════════════════════════
step "5/9 两侧同条件播种（同样的行 + 同样的 MinIO 对象）"
AID_LEGACY=9007199254740993
AID_REV=9007199254740994
REV=rev-succ-0001
# 🔴 **`author_id` 用 `'0'`（不是 `'1001'`）—— 这是本探针**唯一一处**刻意选值，理由三条**：
#   ① **确定性**：B 侧 `ArticleAuthorResolver.resolveAuthorName` 只有在
#      「parse 成功 **且** uid > 0」时才会发 user-rpc 的 RPC。
#      `author_id='1001'` ⇒ B 侧**每次**列表/详情都去连 user-rpc ⇒ 在探针的隔离
#      命名空间里 `user-rpc` **不可达** ⇒ 每次都失败 ⇒ 触发共享层的
#      `GoStyleGrpcClientBreakerInterceptor` 的**熔断** ⇒ 熔断打开后它抛的是
#      `GoStyleCircuitOpenException`（**普通 RuntimeException，不是 StatusRuntimeException**）
#      ⇒ B 侧 `resolveAuthorName` 的 catch **接不住** ⇒ 冒泡到 api 层 ⇒
#      **列表端点返回 1015**，而 C 侧恒返回 200 ⇒ ⇒ **假红**（实测踩过一轮）。
#      `author_id='0'` ⇒ 两侧都走「uid ≤ 0 ⇒ 直接回落」这一支 ⇒ **不发 RPC** ⇒ 无熔断 ⇒ 确定性。
#   ② **可比**：`author_name` 的 **RPC 成功支**在 C 侧**根本不存在**（缺口 G-2 · 签字偏离 ·
#      `docs/Mapping_Table/article/article.md` §1.5 G-2 行）⇒ 拿它去比**必然**不等 ⇒
#      **不是**可比面 ⇒ 探针比的是两侧**同形**的「回落支」。
#   ③ **JWT 用同一个 `userId='0'`**：两侧的 `userId` claim 取值都是**原样字符串**
#      （B 侧 `ArticleApiSupport.resolveUserIdClaim` / C 侧同名方法 · **都不做数字校验**）
#      ⇒ `userId='0'` 两侧都接受 ⇒ `/v1/me/article/{id}` 的 ownership 闸门
#      （`article.author_id == 当前用户`）两侧同样通过 ⇒ 都能读到完整字段。
# ⚠️ article 行与 revision 行**故意填成不同值** ⇒ 响应里出现 revision 的值
#    ⇒ 可证明确实走了 revision 分支（4 个独立信号：ext_info / update_time / content / title…）
SEED_SQL="INSERT INTO article (id,title,brief,content,cover_image_url,manual_type_tag,secondary_tags,author_id,status,view_count,like_count,comment_count,share_count,ext_info,created_at,updated_at)
VALUES ('${AID_LEGACY}','标题','摘要','legacy-body','https://example.invalid/c.png','tech','[]','0',2,1,2,3,4,'{}','2026-01-02 03:04:05+00','2026-01-02 03:04:06+00');
INSERT INTO article (id,title,brief,content,cover_image_url,manual_type_tag,secondary_tags,author_id,status,view_count,like_count,comment_count,share_count,ext_info,created_at,updated_at)
VALUES ('${AID_REV}','旧行标题（旧 article 行）','旧行摘要','rev-body','https://example.invalid/old.png','golang','[]','0',2,1,2,3,4,'{}','2026-01-02 03:04:05+00','2026-01-02 03:04:05+00');
INSERT INTO article_revision (article_id,revision,revision_id,author_id,source_object,content_sha256,title,brief,cover_image_url,manual_type_tag,secondary_tags,markdown,published_at,sync_event_id)
VALUES ('${AID_REV}',1,'${REV}','0','rev-body',repeat('a',64),'成功路径标题','成功路径摘要','https://example.invalid/rev.png','tech','[]','# 成功路径正文','2026-01-02 09:04:05+00','evt-succ-0001');
INSERT INTO article_publication (article_id,current_revision,pointer_version,state,last_event_id,updated_at)
VALUES ('${AID_REV}','${REV}',1,'published','evt-succ-0001','2026-01-02 09:04:05+00');"
for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  # ⚠️ **播种要重试**：PG 刚起来时 psql 的 socket 可能还没建好（实测踩过一轮）
  #    ⇒ 且**必须把 psql 的 stderr 打出来** ⇒ 否则失败时看不到原因（只看到「灌种子失败」）。
  seeded=0
  for attempt in 1 2 3 4 5; do
    # ⚠️ **重试前先 TRUNCATE** ⇒ 上一次可能**部分插入** ⇒ 否则重试撞 duplicate key
    docker exec -i "${c}" psql -U admin -d first_db -c \
      "TRUNCATE article_publication, article_revision, article CASCADE;" >/dev/null 2>&1
    if printf '%s\n' "${SEED_SQL}" | docker exec -i "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 2>"${WORK}/seed.err" >/dev/null; then
      seeded=1
      break
    fi
    say "   · ${c} 第 ${attempt} 次播种失败 ⇒ 原文：$(tr '\n' ' ' < "${WORK}/seed.err")"
    sleep 2
  done
  [ "${seeded}" = "1" ] || die "灌种子失败：${c}（5 次重试后仍失败）"
done
say "   ✓ 两侧同一份种子（${AID_LEGACY} legacy 行 + ${AID_REV} revision 行）"

# MinIO 对象：两侧读的是**同一个**对象存储 ⇒ 内容必须一致
docker exec "${MINIO_CONTAINER}" sh -c "
  mc alias set p http://127.0.0.1:9000 '${MINIO_ACCESS_KEY}' '${MINIO_SECRET_KEY}' >/dev/null 2>&1
  mc mb --ignore-existing p/${MINIO_BUCKET} >/dev/null 2>&1
  printf 'legacy 正文\n' > /tmp/o1
  printf 'revision 正文\n' > /tmp/o2
  mc cp /tmp/o1 p/${MINIO_BUCKET}/legacy-body >/dev/null 2>&1
  mc cp /tmp/o2 p/${MINIO_BUCKET}/rev-body >/dev/null 2>&1
" || die "MinIO 灌对象失败"
say "   ✓ MinIO 对象已就位（legacy-body / rev-body）"

# ── 变异挂钩（**只给工具面用** · 供 mutate-parity.sh 施加变异）──────────────────
# ⚠️ 这是**工具面**的钩子：跑在「播种之后、请求之前」⇒ 两侧数据已同条件就位，
#    再人为把**一侧**改掉 ⇒ 探针**必须**报出差异（EXIT=1）。
# ⚠️ 可用变量：PROBE_B_PG_CONTAINER / PROBE_C_PG_CONTAINER / PROBE_MINIO_CONTAINER /
#    AID_LEGACY / AID_REV。
if [ -n "${PROBE_MUTATE_CMD:-}" ]; then
  export PROBE_B_PG_CONTAINER="${B_PG_CONTAINER}"
  export PROBE_C_PG_CONTAINER="${C_PG_CONTAINER}"
  export PROBE_MINIO_CONTAINER="${MINIO_CONTAINER}"
  export AID_LEGACY="${AID_LEGACY}"
  export AID_REV="${AID_REV}"
  say "   🧬 施加变异：${PROBE_MUTATE_CMD}"
  eval "${PROBE_MUTATE_CMD}" >/dev/null 2>&1 || die "变异命令失败"
  say "   🧬 变异已施加 ⇒ 期望探针报差异（EXIT=1）"
fi

# ══════════════════════════ 6. 就绪闸门 ══════════════════════════════════════
step "6/9 就绪闸门（两侧首个非 1015 响应）"
# ⚠️ 「端口已监听」≠「下游就绪」⇒ 首请求可能 1015 ⇒ 那不是漂移，是没准备好
wait_ready() {   # $1 = 端口  $2 = 标签
  local p="$1" tag="$2" body
  for _ in $(seq 1 40); do
    body="$(curl -s -m 5 "http://127.0.0.1:${p}/v1/articles" 2>/dev/null)"
    case "${body}" in
      ''|*'"code":1015'*) sleep 2 ;;
      *) say "   ✓ ${tag} 就绪"; return 0 ;;
    esac
  done
  say "   ❌ ${tag} 轮询 40 次仍拿不到正常响应"
  return 1
}
wait_ready "${B_API_PORT}" "B 侧" || die "B 侧没就绪"
wait_ready "${C_HTTP_PORT}" "C 侧" || die "C 侧没就绪"

# ══════════════════════════ 7. 请求矩阵 · 逐字节比对 ══════════════════════════
step "7/9 请求矩阵（B 侧 ↔ C 侧 ↔ 冻结基线 ⇒ 逐字节）"
EXPECTED="${HERE}/expected.txt"

mint_jwt() {   # $1 = userId ⇒ 打印 HS256 token（两侧用同一把密钥 ⇒ 鉴权路径可比）
  python3 - "$1" "${JWT_SECRET}" <<'PY'
import hmac, hashlib, base64, json, sys, time
uid, secret = sys.argv[1], sys.argv[2].encode()
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
si = b64(json.dumps({"alg":"HS256","typ":"JWT"}, separators=(',',':')).encode())
sp = b64(json.dumps({"userId": uid, "exp": int(time.time())+3600}, separators=(',',':')).encode())
print("%s.%s.%s" % (si, sp, b64(hmac.new(secret, ("%s.%s" % (si, sp)).encode(), hashlib.sha256).digest())))
PY
}
# ⚠️ `userId='0'`：与种子里的 `author_id='0'` **同一个身份** ⇒ ownership 闸门两侧都通过。
#    （两侧的 claim 取值都是**原样字符串**、都不做数字校验 ⇒ `'0'` 两侧都接受。）
TOKEN="$(mint_jwt 0)"

# 用例表：名字|路径|是否带 Authorization
CASES="
list_articles|/v1/articles|0
list_articles_page2|/v1/articles?page=2&page_size=1|0
list_articles_page0|/v1/articles?page=0|0
list_articles_unknown_param|/v1/articles?foo=bar|0
get_article_legacy|/v1/article/${AID_LEGACY}|0
get_article_revision|/v1/article/${AID_REV}|0
get_article_missing|/v1/article/does-not-exist|0
get_article_nonnumeric|/v1/article/abc|0
me_article_noauth|/v1/me/article/${AID_REV}|0
me_article_auth|/v1/me/article/${AID_REV}|1
me_article_auth_missing|/v1/me/article/does-not-exist|1
route_notfound|/v1/nonexistent|0
"

fetch() {   # $1 = 端口  $2 = 路径  $3 = 带 token(0/1) ⇒ body 落到 stdout，状态行与 content-type 落到 $4
  local port="$1" path="$2" auth="$3" hdr="$4"
  if [ "${auth}" = "1" ]; then
    curl -s -m 30 -D "${hdr}" -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${port}${path}"
  else
    curl -s -m 30 -D "${hdr}" "http://127.0.0.1:${port}${path}"
  fi
}

FAILED=0
FREEZE_FILE=""
[ "${FREEZE:-0}" = "1" ] && FREEZE_FILE="${WORK}/expected.txt" && : > "${FREEZE_FILE}"

run_case() {   # $1 = 名字  $2 = 路径  $3 = 是否带 token
  local name="$1" path="$2" auth="$3"
  local b c bh ch bhex chex bstatus cstatus bct cct eh
  b="$(fetch "${B_API_PORT}" "${path}" "${auth}" "${WORK}/b.hdr")"
  c="$(fetch "${C_HTTP_PORT}" "${path}" "${auth}" "${WORK}/c.hdr")"
  bhex="$(printf '%s' "${b}" | xxd -p | tr -d '\n')"
  chex="$(printf '%s' "${c}" | xxd -p | tr -d '\n')"
  bstatus="$(grep -iE '^HTTP/' "${WORK}/b.hdr" | tr -d '\r' | head -1 | awk '{print $2}')"
  cstatus="$(grep -iE '^HTTP/' "${WORK}/c.hdr" | tr -d '\r' | head -1 | awk '{print $2}')"
  bct="$(grep -iE '^content-type:' "${WORK}/b.hdr" | tr -d '\r' | head -1 | sed 's/^[Cc]ontent-[Tt]ype: *//')"
  cct="$(grep -iE '^content-type:' "${WORK}/c.hdr" | tr -d '\r' | head -1 | sed 's/^[Cc]ontent-[Tt]ype: *//')"

  # ① 两侧逐字节
  if [ "${bhex}" != "${chex}" ] || [ "${bstatus}" != "${cstatus}" ] || [ "${bct}" != "${cct}" ]; then
    FAILED=1
    say "   ❌ ${name}"
    say "      B status=${bstatus} ct=${bct}"
    say "      C status=${cstatus} ct=${cct}"
    say "      B body: ${b}"
    say "      C body: ${c}"
    say "      B hex: ${bhex}"
    say "      C hex: ${chex}"
    return
  fi
  say "   ✅ ${name}  B == C（逐字节 · status ${bstatus} · ${bct}）"

  # ② 与冻结基线比
  if [ -n "${FREEZE_FILE}" ]; then
    printf 'CASE|%s|%s|%s|%s\n' "${name}" "${bstatus}" "${bct}" "${bhex}" >> "${FREEZE_FILE}"
    return
  fi
  if [ -f "${EXPECTED}" ]; then
    eh="$(awk -F'|' -v n="CASE|${name}" '$1"|"$2==n {print $3"|"$4"|"$5}' "${EXPECTED}")"
    if [ -z "${eh}" ]; then
      FAILED=1
      say "   ❌ ${name}：expected.txt 里没有这一条"
      return
    fi
    if [ "${bstatus}|${bct}|${bhex}" != "${eh}" ]; then
      FAILED=1
      say "   ❌ ${name}：与冻结基线不一致"
      say "      基线: ${eh}"
      say "      实测: ${bstatus}|${bct}|${bhex}"
    fi
  fi
}

while IFS='|' read -r name path auth; do
  [ -n "${name:-}" ] || continue
  run_case "${name}" "${path}" "${auth}"
done <<< "${CASES}"

if [ -n "${FREEZE_FILE}" ]; then
  # 🔴 **只有本轮真的全绿才允许覆盖基线** ⇒ 否则一次坏跑就把基线写坏了
  if [ "${FAILED}" = "0" ]; then
    cp "${FREEZE_FILE}" "${EXPECTED}"
    say "   📌 已冻结基线 ⇒ ${EXPECTED}（FREEZE=1 · 依据见 README「基线冻结依据」）"
  else
    say "   ⚠️ 本轮有差异 ⇒ **不覆盖** ${EXPECTED}（基线保持不变）"
  fi
fi

# ══════════════════════════ 8. 判决 ═══════════════════════════════════════════
echo
if [ "${FAILED}" = "0" ]; then
  say "✅ 双侧逐字节一致（B 侧 Java 栈 == C 侧 Java 栈）"
  exit 0
fi
say "❌ 双侧存在字节差异（见上面逐条）"
dump_logs
exit 1
