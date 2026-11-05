#!/usr/bin/env bash
# =============================================================================
# run.sh —— comment 域「B 侧 ↔ C 侧」**双侧活体对拍**探针（HTTP 面 · 逐字节）
#
# 对比双方：
#   **B 侧**（唯一 oracle · 只读）= /Users/pr/Documents/java&golang/RAG
#       · 形态 = comment-api（HTTP 进程 :8888） + comment-rpc（gRPC 进程 :9002）**两个进程**
#       · 用**本地现成镜像** `sea/comment-api:latest` / `sea/comment-rpc:latest` 起
#         ⇒ **B 仓一个字节都不改**（连 `target/` 都不写）
#   **C 侧**（被测）= 本仓工作树
#       · 形态 = **单进程**同起 Tomcat + gRPC（D1 合并 · D2 域内直连）
#       · 产物 = `mvn -o -pl service/comment -am package` 的 fat jar
#
# 判定：同一请求 ⇒ **两侧响应逐字节比对**（status + content-type + body）
#      ⇒ 再与冻结基线 `expected.txt` 比对。
# 退出码：0 = 逐字节一致 · 1 = 有差异 · 2 = 环境问题（**与真差异严格分开**）。
#
# ⚠️ **gRPC 面不可直接对拍**（拓扑产物 · 不是行为漂移）：
#   B 侧 = 两个进程（api 通过 `discovery:///comment-rpc` 走**跨进程 gRPC**）
#   C 侧 = 一个进程（api **直连**领域层，**没有网络跳** · D2）
#   ⇒ 两边的 gRPC 拓扑不同 ⇒ 对拍**只能落在 HTTP 面**（两边都对外暴露的同一契约）。
#
# ⚠️ **写路径（create）不做逐字节对拍**：
#   `create` 的响应里带**随机雪花 ID**，且会**同步推 Kafka** ⇒ 两侧字节必然不同
#   ⇒ 逐字节比它 = 必然红且红了也不代表有错（假红）。
#   ⇒ 探针只覆盖**确定性面**：鉴权面 / 校验面 / 路由面 / 读面（seeded）。
#   （写路径的真链路已由仓内 `CommentKafkaAuditRealTest` 等真触发测试覆盖。）
#
# 用法：
#   bash tools/parity-probe/comment/run.sh
#   RUN_ID=A bash tools/parity-probe/comment/run.sh       # 并发隔离
#   FREEZE=1 bash tools/parity-probe/comment/run.sh       # 重建冻结基线
#   KEEP_WORK=1 bash tools/parity-probe/comment/run.sh     # 保留临时目录
#
# 依赖：docker · java 21+ · mvn（离线 -o）· curl · xxd · shasum · lsof · nc · python3。
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAG_V1_ROOT="$(cd "${HERE}/../../.." && pwd)"

# ── 「每轮独立」机制（工具面 · **不改产品代码**）────────────────────────────────
source "${HERE}/probe-isolation.sh"

# ⚠️ **基准端口要先登记** ⇒ 租约才知道「这一块会占哪些端口」⇒ 才能避开危险端口
#    （撞上容器发布口时 `lsof` 返回的是 Docker Desktop backend ⇒ 杀了 Docker 就没了）。
#    B 侧 api HTTP / B 侧 rpc gRPC / C 侧 HTTP / C 侧 gRPC / **B 侧 Redis / C 侧 Redis**
#    🔴 **Redis 端口也必须登记** ⇒ 两侧的 Redis key 空间逐字相同 ⇒ 共用一个就是假绿。
PROBE_BASE_APP_PORTS="18888 19002 18892 19012 19390 19391"
# PG 阶梯：两侧族**交错**（B 侧偶数位 / C 侧奇数位）⇒ 同一块号下两族也不重叠
PROBE_BASE_PG_PORTS="55570 55572 55574 55576 55578 55580 55571 55573 55575 55577 55579 55581"
probe_iso_init parity || exit 2

# ── 宿主中间件（**共享**，来自 B 仓 compose 起的那套）───────────────────────────
NACOS_PORT="${NACOS_PORT:-49848}"      # Sea-nacos   49848→8848
KAFKA_PORT="${KAFKA_PORT:-49992}"      # Sea-kafka   49992→9093（EXTERNAL listener）
JAEGER_PORT="${JAEGER_PORT:-49317}"    # Sea-jaeger  49317→4317
# 🔴 **Kafka 的 EXTERNAL listener 广播的是 `127.0.0.1:49992`** ⇒ **只对宿主有效**。
#    ⇒ B 侧（在容器里）必须用 compose DNS 名 `kafka:9092`（PLAINTEXT listener），
#      C 侧（在宿主上）用 `127.0.0.1:49992`。
# ⚠️ 踩坑见 README「Kafka 的两个 listener」段。
# ⚠️ **Redis 不在这里** —— 本探针**自带两个 Redis 容器**（V5 隔离面，见 probe-isolation.sh）。

# ── 两侧端口（偏移来自端口租约 ⇒ 并发跑两份时保证不撞）─────────────────────────
B_API_PORT="${B_API_PORT:-$(( 18888 + PROBE_PORT_OFFSET ))}"
B_RPC_PORT="${B_RPC_PORT:-$(( 19002 + PROBE_PORT_OFFSET ))}"
C_HTTP_PORT="${C_HTTP_PORT:-$(( 18892 + PROBE_PORT_OFFSET ))}"
C_GRPC_PORT="${C_GRPC_PORT:-$(( 19012 + PROBE_PORT_OFFSET ))}"
B_REDIS_PORT="${B_REDIS_PORT:-$(( 19390 + PROBE_PORT_OFFSET ))}"
C_REDIS_PORT="${C_REDIS_PORT:-$(( 19391 + PROBE_PORT_OFFSET ))}"

# ── 两侧各自的 Postgres / Redis（**各一个容器** ⇒ 账本与缓存互不干扰）─────────
B_PG_CONTAINER="parity-comment-pg-b-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-}$$"
C_PG_CONTAINER="parity-comment-pg-c-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-}$$"
B_REDIS_CONTAINER="parity-comment-redis-b-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-}$$"
C_REDIS_CONTAINER="parity-comment-redis-c-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-$$}"

# ── B 侧镜像（本地现成 ⇒ 不构建 B 侧）────────────────────────────────────────
B_API_IMAGE="${B_API_IMAGE:-sea/comment-api:latest}"
B_RPC_IMAGE="${B_RPC_IMAGE:-sea/comment-rpc:latest}"

# ── C 侧产物 ─────────────────────────────────────────────────────────────────
C_JAR_REL="service/comment/target/comment-service-1.0-SNAPSHOT.jar"
# ── 验签密钥（**两侧必须一致** ⇒ 否则鉴权路径不可比）─────────────────────────
# ⚠️ **必须 ≥32 字节**：两侧都用 jjwt 的 `Keys.hmacShaKeyFor` ⇒ 短密钥会被 zero-fill 补齐
#    ⇒ 补齐后两侧的 key 字节一致，但**只有当原密钥本就 ≥32 字节时**才天然一致 ⇒ 取 36 字符 UUID。
JWT_SECRET="${JWT_SECRET:-ae0536f9-6450-4606-8e13-5a19ed505da0}"

# ── 本轮 Kafka 隔离（**探针侧覆盖** · 不属于被比较面）──────────────────────────
# ⚠️ **为什么覆盖 topic / group**：两侧的审计 topic 名逐字相同（`comment-audit-topic`）
#    且消费组也相同（`comment-audit-group`）⇒ 若与宿主上别的 comment 测试共用同一个 broker，
#    对方 publish 进来的消息会被**本轮某一侧**消费掉 ⇒ 那一侧的库多出评论 ⇒
#    ⇒ 读面用例**假红**。
# ⇒ 本轮给两侧各一个**专属 topic + group** ⇒ 任何外部消息都进不来 ⇒ 读面只受本轮播种影响。
# ⚠️ **topic 名本身不在被比较面内**（它由 `docs/Mapping_Table/comment.md` 静态比对负责）。
# ⚠️ 用 `SPRING_APPLICATION_JSON` 而不是 env：它的键名是**精确属性名**，不经过 relaxed binding
#    （`comment.kafka.audit-topic` 的 env 形式是 `COMMENT_KAFKA_AUDITTOPIC`，易写错）。
PROBE_KAFKA_TOPIC_B="${PROBE_KAFKA_TOPIC_B:-comment-audit-probe-b-${PROBE_RUN_TAG:-0}-$$}"
PROBE_KAFKA_TOPIC_C="${PROBE_KAFKA_TOPIC_C:-comment-audit-probe-c-${PROBE_RUN_TAG:-0}-$$}"
PROBE_KAFKA_GROUP_B="${PROBE_KAFKA_GROUP_B:-comment-audit-probe-b-${PROBE_RUN_TAG:-0}-$$}"
PROBE_KAFKA_GROUP_C="${PROBE_KAFKA_GROUP_C:-comment-audit-probe-c-${PROBE_RUN_TAG:-0}-$$}"
B_SPRING_JSON="{\"comment\":{\"kafka\":{\"audit-topic\":\"${PROBE_KAFKA_TOPIC_B}\"}},\"spring\":{\"kafka\":{\"consumer\":{\"group-id\":\"${PROBE_KAFKA_GROUP_B}\"}}}}"
C_SPRING_JSON="{\"comment\":{\"kafka\":{\"audit-topic\":\"${PROBE_KAFKA_TOPIC_C}\"}},\"spring\":{\"kafka\":{\"consumer\":{\"group-id\":\"${PROBE_KAFKA_GROUP_C}\"}}}}"

# ── 种子数据（两侧**逐字相同** ⇒ 读面可比）────────────────────────────────────
TARGET_TYPE="article"
TARGET_ID="9007199254740993"
SUBJECT_ID=9007199254741001
ROOT_CID=9007199254742001
REPLY_CID=9007199254742002

# ⚠️ **目录名带探针标记**（`rag-comment-parity.`）⇒ 端口预检能认出「哪些进程是探针自己的」
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rag-comment-parity.XXXXXX")"
C_PID=""
say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }

dump_logs() {
  local f
  for f in c-side.log; do
    [ -f "${WORK:-/nonexistent}/${f}" ] || continue
    printf '\n---- %s（末 40 行）----\n' "${f}" >&2
    tail -40 "${WORK}/${f}" | sed 's/^/   | /' >&2
  done
  # B 侧两个容器的日志只在 docker 里 ⇒ 这里直接捞
  for c in "${B_RPC_CONTAINER:-}" "${B_API_CONTAINER:-}"; do
    [ -n "${c}" ] || continue
    docker ps -a --format '{{.Names}}' | grep -qx "${c}" || continue
    printf '\n---- docker logs %s（末 40 行）----\n' "${c}" >&2
    docker logs "${c}" 2>&1 | tail -40 | sed 's/^/   | /' >&2
  done
}
die()  { printf '!! %s\n' "$*" >&2; dump_logs; exit 2; }

host_port_open() { nc -z -G 2 127.0.0.1 "$1" >/dev/null 2>&1; }

# ── 清理：**按端口反查 PID** ⇒ 即使 trap 没跑（被外部取消），下一轮也能按端口回收 ──
cleanup() {
  local p pid c
  # ① 先按**端口**反查 PID（C 侧 JVM）—— 端口是我们自己的 ⇒ 命中即探针残留
  for p in "${C_HTTP_PORT}" "${C_GRPC_PORT}"; do
    for pid in $(lsof -nP -iTCP:"${p}" -sTCP:LISTEN -t 2>/dev/null); do
      kill -9 "${pid}" 2>/dev/null
    done
  done
  # ② 容器（B 侧两个 + 两个 PG + 两个 Redis）
  for c in "${B_RPC_CONTAINER:-x}" "${B_API_CONTAINER:-x}" "${B_PG_CONTAINER:-x}" \
           "${C_PG_CONTAINER:-x}" "${B_REDIS_CONTAINER:-x}" "${C_REDIS_CONTAINER:-x}"; do
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
step "0/10 前置检查"
# 🔴 **变量自检**（工具面护栏）：
#    探针脚本一旦有**一处赋值被写坏**（例如 `${VAR:+…}` 少写一个 `}`），
#    bash 会把后面整段当成一个字符串吞掉 ⇒ 症状是「某个变量 unbound」，
#    而**真正的原因在几百行之前** ⇒ 排查成本极高（本次实测踩过一轮）。
# ⇒ 这里对**每个会被后面用到的关键变量**做一次非空断言 ⇒ 坏在哪儿一目了然。
for v in PROBE_KAFKA_TOPIC_B PROBE_KAFKA_TOPIC_C PROBE_KAFKA_GROUP_B PROBE_KAFKA_GROUP_C \
         B_SPRING_JSON C_SPRING_JSON TARGET_TYPE TARGET_ID SUBJECT_ID ROOT_CID REPLY_CID \
         B_API_PORT B_RPC_PORT C_HTTP_PORT C_GRPC_PORT B_REDIS_PORT C_REDIS_PORT \
         B_PG_CONTAINER C_PG_CONTAINER B_REDIS_CONTAINER C_REDIS_CONTAINER \
         B_API_IMAGE B_RPC_IMAGE C_JAR_REL JWT_SECRET WORK; do
  if [ -z "${!v:-}" ]; then
    printf '!! 变量自检失败：%s 为空 ⇒ 脚本某处赋值被写坏（看上面第一处 unbound 报错）\n' "${v}" >&2
    exit 2
  fi
done
say "   ✓ 变量自检通过（22 个关键变量均非空）"
for cmd in docker java mvn curl xxd shasum lsof nc python3; do
  command -v "${cmd}" >/dev/null 2>&1 || die "缺命令：${cmd}"
done
docker ps >/dev/null 2>&1 || die "docker 不可用"
for pair in "Sea-nacos:${NACOS_PORT}" "Sea-kafka:${KAFKA_PORT}" "Sea-jaeger:${JAEGER_PORT}"; do
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
# 本轮两个 Nacos 命名空间（**必须两个** —— 两侧注册的服务名都是 `comment-rpc`）
probe_nacos_ensure_namespace "${PROBE_NS_B}" || die "B 侧 Nacos 命名空间 ${PROBE_NS_B} 不可用"
probe_nacos_ensure_namespace "${PROBE_NS_C}" || die "C 侧 Nacos 命名空间 ${PROBE_NS_C} 不可用"
say "   ✓ 命令齐 / 中间件在跑 / B 侧镜像就绪"
say "   ✓ 隔离身份：B 侧命名空间=${PROBE_NS_B} · C 侧命名空间=${PROBE_NS_C} · 端口块=${PROBE_PORT_BLOCK}（偏移 ${PROBE_PORT_OFFSET}）· PG 偏移 ${PROBE_PG_OFFSET}"
say "   ✓ B 侧容器网络=${B_SIDE_NETWORK}"
say "   ✓ 本轮 Kafka 隔离：B topic=${PROBE_KAFKA_TOPIC_B} · C topic=${PROBE_KAFKA_TOPIC_C}"

# 端口预检：把**上一轮残留**的探针进程踢掉（否则 C 侧 JVM 起不来：Port already in use）
# ⚠️ **先扫掉上一轮硬中断留下的探针容器**（名字统一前缀 `parity-comment-`）
#    ⇒ 否则它的 Redis/PG 容器仍占着端口 ⇒ 本轮 `docker run` 绑不上 ⇒ 整轮起不来。
for c in $(docker ps -a --format '{{.Names}}' | grep '^parity-comment-' || true); do
  say "   · 清理上一轮残留的探针容器：${c}"
  docker rm -f "${c}" >/dev/null 2>&1
done
for p in "${B_API_PORT}" "${B_RPC_PORT}" "${C_HTTP_PORT}" "${C_GRPC_PORT}" \
         "${B_REDIS_PORT}" "${C_REDIS_PORT}"; do
  probe_free_port "${p}"
done
for p in "${B_API_PORT}" "${B_RPC_PORT}" "${C_HTTP_PORT}" "${C_GRPC_PORT}" \
         "${B_REDIS_PORT}" "${C_REDIS_PORT}"; do
  for _ in $(seq 1 15); do host_port_open "${p}" || break; sleep 1; done
  host_port_open "${p}" && die "端口 ${p} 仍被占用（不是探针进程 ⇒ 不杀）⇒ 请显式指定 PORT_OFFSET"
done

# ══════════════════════════ 1. 两侧各自的 Postgres + Redis ════════════════════
step "1/10 两侧各自的 Postgres（账本表名两侧相同 ⇒ 必须隔离）＋ 各自的 Redis（key 空间两侧相同 ⇒ 必须隔离）"
start_pg() {   # $1 = 容器名  $2 = 宿主端口 ⇒ 成功返回 0（宿主端口已验证可连）
  local c="$1" p="$2"
  docker rm -f "${c}" >/dev/null 2>&1
  docker run -d --name "${c}" -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=sea \
    -e POSTGRES_DB=first_db -p "${p}:5432" --pull=never postgres:latest >/dev/null 2>&1 || return 1
  # ⚠️ **就绪判据必须是「走 psql 真正会走的那条路」**（容器内 socket）——
  #    `pg_isready` 与 `psql` 的连接方式不一定一致 ⇒ 这里直接跑一条真查询。
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
start_redis() {   # $1 = 容器名  $2 = 宿主端口
  local c="$1" p="$2"
  docker rm -f "${c}" >/dev/null 2>&1
  docker run -d --name "${c}" -p "${p}:6379" --pull=never redis:7-alpine >/dev/null 2>&1 || return 1
  for _ in $(seq 1 30); do
    host_port_open "${p}" && return 0
    sleep 1
  done
  return 1
}
B_PG_CANDIDATES=($(probe_pg_ladder 55570 2 6))
C_PG_CANDIDATES=($(probe_pg_ladder 55571 2 6))
B_PG_PORT="$(pick_pg_port "${B_PG_CONTAINER}" ${B_PG_CANDIDATES[@]+${B_PG_CANDIDATES[@]}})" \
  || die "B 侧 PG 起不来（候选：${B_PG_CANDIDATES[*]}）"
C_PG_PORT="$(pick_pg_port "${C_PG_CONTAINER}" ${C_PG_CANDIDATES[@]+${C_PG_CANDIDATES[@]}})" \
  || die "C 侧 PG 起不来（候选：${C_PG_CANDIDATES[*]}）"
start_redis "${B_REDIS_CONTAINER}" "${B_REDIS_PORT}" || die "B 侧 Redis 起不来（:${B_REDIS_PORT}）"
start_redis "${C_REDIS_CONTAINER}" "${C_REDIS_PORT}" || die "C 侧 Redis 起不来（:${C_REDIS_PORT}）"
say "   ✓ B 侧 PG :${B_PG_PORT} · C 侧 PG :${C_PG_PORT} · B 侧 Redis :${B_REDIS_PORT} · C 侧 Redis :${C_REDIS_PORT}"

# ══════════════════════════ 2. 构建 C 侧（持锁 + 拷进本轮 WORK）════════════════
step "2/10 构建 C 侧（mvn 串行锁 + 拷进本轮 WORK ⇒ 与别的轮次互不干扰）"
build_c_side() {
  local helper="${RAG_MVN_LOCK_HELPER:-/tmp/rag-mvn-lock.py}"
  # ⚠️ **`-Dmaven.test.skip=true` 而不是 `-DskipTests`**：
  #   `-DskipTests` 只跳过**执行**、仍会**编译**测试代码 ⇒ 测试源码编译不过时整轮失败。
  #   而本探针只需要**运行期产物**（fat jar）⇒ 连测试编译都不必做。
  # ⚠️ **`PROBE_SKIP_BUILD=1`** ⇒ 复用工作树里已有的 jar（**不碰 target/**）。
  if [ "${PROBE_SKIP_BUILD:-0}" = "1" ]; then
    say "   · PROBE_SKIP_BUILD=1 ⇒ 复用已有 jar（${C_JAR_REL}）"
    return 0
  fi
  if [ -f "${helper}" ]; then
    # 仓内约定的**全局** mvn 锁（多个 worker 并行写同一个 target/ 时用它）
    ( cd "${RAG_V1_ROOT}" && python3 "${helper}" mvn -o -q -pl service/comment -am package -Dmaven.test.skip=true )
  else
    probe_mvn_lock_acquire || return 1
    ( cd "${RAG_V1_ROOT}" && mvn -o -q -pl service/comment -am package -Dmaven.test.skip=true )
    local rc=$?
    probe_mvn_lock_release
    return $rc
  fi
}
build_c_side || die "C 侧构建失败"
C_JAR="${RAG_V1_ROOT}/${C_JAR_REL}"
[ -f "${C_JAR}" ] || die "C 侧 jar 没产出：${C_JAR}"
cp "${C_JAR}" "${WORK}/comment-service.jar" || die "拷 C 侧 jar 失败"
C_JAR="${WORK}/comment-service.jar"
say "   ✓ C 侧构建完成（jar 已拷进本轮 WORK）"

# ══════════════════════════ 3. 起 B 侧（rpc → 等注册 → api）════════════════════
step "3/10 起 B 侧 comment-rpc（注册 Nacos ${PROBE_NS_B}）+ comment-api（:${B_API_PORT}）"
# ⚠️ **B 侧在容器里 ⇒ 宿主中间件用 compose DNS 名**；PG / Redis 用宿主映射口（host.docker.internal）。
B_RPC_CONTAINER="parity-comment-b-rpc-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-$$}"
B_API_CONTAINER="parity-comment-b-api-${PROBE_RUN_TAG:+${PROBE_RUN_TAG}-$$}"
docker rm -f "${B_RPC_CONTAINER}" "${B_API_CONTAINER}" >/dev/null 2>&1
docker run -d --name "${B_RPC_CONTAINER}" --network "${B_SIDE_NETWORK}" \
  -e JAVA_OPTS="-XX:+UseContainerSupport -Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8" \
  -e POSTGRES_HOST=host.docker.internal -e POSTGRES_PORT="${B_PG_PORT}" \
  -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=sea -e POSTGRES_DB=first_db \
  -e NACOS_ADDR=nacos -e NACOS_PORT=8848 -e NACOS_NAMESPACE="${PROBE_NS_B}" \
  -e KAFKA_BROKERS=kafka:9092 \
  -e REDIS_HOST=host.docker.internal -e REDIS_PORT="${B_REDIS_PORT}" \
  -e OTEL_ADDR=jaeger -e OTLP_PORT=4317 \
  -e SPRING_APPLICATION_JSON="${B_SPRING_JSON}" \
  -e SNOWFLAKE_NODE_ID=4 \
  -e COMMENT_RPC_PORT="${B_RPC_PORT}" \
  -p "${B_RPC_PORT}:${B_RPC_PORT}" \
  --memory=1g --restart=no --pull=never \
  "${B_RPC_IMAGE}" >/dev/null || die "B 侧 comment-rpc 容器起不来"
# ⚠️ **就绪判据必须精确到「本轮的端口」**（数「注册端口 == 本轮 gRPC 端口」的实例个数）
#    ⇒ 别的轮次 / 上一轮的残留实例**不会被数进来**。
for _ in $(seq 1 120); do
  [ "$(probe_nacos_instance_count "${B_RPC_PORT}" "${PROBE_NS_B}")" != "0" ] && break
  sleep 1
done
[ "$(probe_nacos_instance_count "${B_RPC_PORT}" "${PROBE_NS_B}")" != "0" ] \
  || die "B 侧 comment-rpc 120s 内没以端口 ${B_RPC_PORT} 注册进 Nacos（命名空间 ${PROBE_NS_B}）"
say "   ✓ B 侧 comment-rpc 已注册（命名空间 ${PROBE_NS_B} · 端口 ${B_RPC_PORT}）"

docker run -d --name "${B_API_CONTAINER}" --network "${B_SIDE_NETWORK}" \
  -e JAVA_OPTS="-XX:+UseContainerSupport -Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8" \
  -e POSTGRES_HOST=host.docker.internal -e POSTGRES_PORT="${B_PG_PORT}" \
  -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=sea -e POSTGRES_DB=first_db \
  -e NACOS_ADDR=nacos -e NACOS_PORT=8848 -e NACOS_NAMESPACE="${PROBE_NS_B}" \
  -e KAFKA_BROKERS=kafka:9092 \
  -e REDIS_HOST=host.docker.internal -e REDIS_PORT="${B_REDIS_PORT}" \
  -e OTEL_ADDR=jaeger -e OTLP_PORT=4317 \
  -e SPRING_APPLICATION_JSON="${B_SPRING_JSON}" \
  -e USER_JWT_SECRET="${JWT_SECRET}" \
  -e COMMENT_API_PORT="${B_API_PORT}" \
  -p "${B_API_PORT}:${B_API_PORT}" \
  --memory=1g --restart=no --pull=never \
  "${B_API_IMAGE}" >/dev/null || die "B 侧 comment-api 容器起不来"
for _ in $(seq 1 90); do host_port_open "${B_API_PORT}" && break; sleep 1; done
host_port_open "${B_API_PORT}" || die "B 侧 comment-api 没监听 :${B_API_PORT}"
say "   ✓ B 侧 comment-api 已监听 :${B_API_PORT}"

# ══════════════════════════ 4. 起 C 侧（单进程：Tomcat + gRPC）═════════════════
step "4/10 起 C 侧 comment-service（单进程 · Tomcat :${C_HTTP_PORT} + gRPC :${C_GRPC_PORT}）"
# ⚠️ **必须在「端口已空闲」之后起** ⇒ 否则 `Port already in use`。
( cd "${WORK}" && exec env \
  POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${C_PG_PORT}" \
  POSTGRES_USER=admin POSTGRES_PASSWORD=sea POSTGRES_DB=first_db \
  KAFKA_BROKERS="127.0.0.1:${KAFKA_PORT}" \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE="${PROBE_NS_C}" \
  USER_JWT_SECRET="${JWT_SECRET}" \
  REDIS_HOST=127.0.0.1 REDIS_PORT="${C_REDIS_PORT}" \
  OTEL_EXPORTER_OTLP_ENDPOINT="127.0.0.1:${JAEGER_PORT}" \
  SPRING_APPLICATION_JSON="${C_SPRING_JSON}" \
  COMMENT_HTTP_PORT="${C_HTTP_PORT}" COMMENT_GRPC_PORT="${C_GRPC_PORT}" \
  java -Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8 -jar "${C_JAR}" \
  >"${WORK}/c-side.log" 2>&1 ) &
C_PID=$!
disown "${C_PID}" 2>/dev/null || true
for _ in $(seq 1 150); do host_port_open "${C_HTTP_PORT}" && break; sleep 1; done
host_port_open "${C_HTTP_PORT}" || die "C 侧没监听 :${C_HTTP_PORT}（见 ${WORK}/c-side.log）"
for _ in $(seq 1 60); do
  [ "$(probe_nacos_instance_count "${C_GRPC_PORT}" "${PROBE_NS_C}")" != "0" ] && break
  sleep 1
done
[ "$(probe_nacos_instance_count "${C_GRPC_PORT}" "${PROBE_NS_C}")" != "0" ] \
  || say "   ⚠️ C 侧没注册进 Nacos（命名空间 ${PROBE_NS_C}）⇒ 继续（C 侧 HTTP 面是域内直连，不依赖注册）"
say "   ✓ C 侧已监听 :${C_HTTP_PORT}（gRPC :${C_GRPC_PORT} · pid ${C_PID}）"

# ══════════════════════════ 5. 两侧同条件播种 ═════════════════════════════════
step "5/10 两侧同条件播种（同一批行 ⇒ 读面可比）"
SEED_SQL="INSERT INTO subject (id,target_type,target_id,total_count,root_count,state,attribute,owner_id,created_at,updated_at)
VALUES (${SUBJECT_ID},'${TARGET_TYPE}','${TARGET_ID}',2,1,0,0,4242,'2026-01-02 03:04:05+00','2026-01-02 03:04:05+00');
INSERT INTO comment_index (id,target_type,target_id,user_id,root_id,parent_id,like_count,dislike_count,reply_count,state,attribute,created_at,updated_at)
VALUES (${ROOT_CID},'${TARGET_TYPE}','${TARGET_ID}',1001,0,0,7,2,1,0,0,'2026-01-02 03:04:05+00','2026-01-02 03:04:05+00'),
       (${REPLY_CID},'${TARGET_TYPE}','${TARGET_ID}',1002,${ROOT_CID},${ROOT_CID},3,0,0,0,0,'2026-01-02 04:04:05+00','2026-01-02 04:04:05+00');
INSERT INTO comment_content (comment_id,content,meta,created_at,updated_at)
VALUES (${ROOT_CID},'根评论正文','{\"k\":\"v\"}','2026-01-02 03:04:05+00','2026-01-02 03:04:05+00'),
       (${REPLY_CID},'子评论正文',NULL,'2026-01-02 04:04:05+00','2026-01-02 04:04:05+00');"
for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  # ⚠️ **播种要重试**：PG 刚起来时 psql 的 socket 可能还没建好
  #    ⇒ 且**必须把 psql 的 stderr 打出来** ⇒ 否则失败时看不到原因。
  seeded=0
  for attempt in 1 2 3 4 5; do
    # ⚠️ **重试前先 TRUNCATE** ⇒ 上一次可能**部分插入** ⇒ 否则重试撞 duplicate key
    docker exec -i "${c}" psql -U admin -d first_db -c \
      "TRUNCATE subject, comment_index, comment_content CASCADE;" >/dev/null 2>&1
    if printf '%s\n' "${SEED_SQL}" | docker exec -i "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 2>"${WORK}/seed.err" >/dev/null; then
      seeded=1
      break
    fi
    say "   · ${c} 第 ${attempt} 次播种失败 ⇒ 原文：$(tr '\n' ' ' < "${WORK}/seed.err")"
    sleep 2
  done
  [ "${seeded}" = "1" ] || die "灌种子失败：${c}（5 次重试后仍失败）"
done
say "   ✓ 两侧同一份种子（1 条 subject + 2 条 index + 2 条 content）"

# ── 变异挂钩（**只给工具面用** · 供 mutate-parity.sh 施加变异）──────────────────
# ⚠️ 这是**工具面**的钩子：跑在「播种之后、请求之前」⇒ 两侧数据已同条件就位，
#    再人为把**一侧**改掉 ⇒ 探针**必须**报出差异（EXIT=1）。
# ⚠️ 可用变量：PROBE_B_PG_CONTAINER / PROBE_C_PG_CONTAINER / ROOT_CID / REPLY_CID / TARGET_ID。
if [ -n "${PROBE_MUTATE_CMD:-}" ]; then
  export PROBE_B_PG_CONTAINER="${B_PG_CONTAINER}"
  export PROBE_C_PG_CONTAINER="${C_PG_CONTAINER}"
  export ROOT_CID="${ROOT_CID}"
  export REPLY_CID="${REPLY_CID}"
  export TARGET_ID="${TARGET_ID}"
  say "   🧬 施加变异：${PROBE_MUTATE_CMD}"
  eval "${PROBE_MUTATE_CMD}" >/dev/null 2>&1 || die "变异命令失败"
  say "   🧬 变异已施加 ⇒ 期望探针报差异（EXIT=1）"
fi

# ══════════════════════════ 6. 就绪闸门 ══════════════════════════════════════
step "6/10 就绪闸门（两侧首个「200 + 5001」响应 ⇒ 证明 Tomcat+路由+鉴权+控制器全通）"
# ⚠️ 判据选「**必填字段校验失败**」而不是「随便一个请求」：
#    · 它在**解析层**就返回（5001）⇒ **不碰数据库** ⇒ 不会污染读面的缓存；
#    · 它同时证明「Tomcat 起来了 + 路由匹配上了 + 鉴权过了 + advice 装上了」；
#    · 它是**确定性**的 ⇒ 不会出现「还没热好」的抖动。
mint_jwt() {   # $1 = claim 模式  $2 = 密钥（仅 badsig 用）⇒ 打印 HS256 token
  python3 - "${1}" "${JWT_SECRET}" <<'PY'
import hmac, hashlib, base64, json, sys, time
mode, secret = sys.argv[1], sys.argv[2].encode()
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
now = int(time.time())
if mode == "good":      payload = {"userId": 1001, "exp": now + 3600}
elif mode == "goodstr": payload = {"userId": "1001", "exp": now + 3600}
elif mode == "noclaim": payload = {"exp": now + 3600}
elif mode == "nonnumeric": payload = {"userId": "abc", "exp": now + 3600}
elif mode == "expired": payload = {"userId": 1001, "exp": now - 3600}
elif mode == "badsig":  payload = {"userId": 1001, "exp": now + 3600}
else: raise SystemExit("unknown mode " + mode)
si = b64(json.dumps({"alg":"HS256","typ":"JWT"}, separators=(',',':')).encode())
sp = b64(json.dumps(payload, separators=(',',':')).encode())
signing = ("%s.%s" % (si, sp)).encode()
key = secret if mode != "badsig" else (b"x" * len(secret))
print("%s.%s.%s" % (si, sp, b64(hmac.new(key, signing, hashlib.sha256).digest())))
PY
}
TOKEN_GOOD="$(mint_jwt good)"
TOKEN_GOODSTR="$(mint_jwt goodstr)"
TOKEN_NOCLAIM="$(mint_jwt noclaim)"
TOKEN_NONNUMERIC="$(mint_jwt nonnumeric)"
TOKEN_EXPIRED="$(mint_jwt expired)"
TOKEN_BADSIG="$(mint_jwt badsig)"

wait_ready() {   # $1 = 端口  $2 = 标签
  local p="$1" tag="$2" body
  for _ in $(seq 1 60); do
    body="$(curl -s -m 10 -X POST -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${TOKEN_GOOD}" \
      -d '{}' "http://127.0.0.1:${p}/comment/v1/comment/list" 2>/dev/null)"
    case "${body}" in
      *'"code":5001'*) say "   ✓ ${tag} 就绪（首个响应 = 200 + 5001）"; return 0 ;;
      '') sleep 2 ;;
      *) sleep 2 ;;
    esac
  done
  say "   ❌ ${tag} 轮询 60 次仍拿不到 200 + 5001（最后一次 body：${body}）"
  return 1
}
wait_ready "${B_API_PORT}" "B 侧" || die "B 侧没就绪"
wait_ready "${C_HTTP_PORT}" "C 侧" || die "C 侧没就绪"

# ══════════════════════════ 7. 请求矩阵 · 逐字节比对 ═════════════════════════
step "7/10 请求矩阵（B 侧 ↔ C 侧 ↔ 冻结基线 ⇒ 逐字节）"
EXPECTED="${HERE}/expected.txt"
KNOWN_DIFF="${HERE}/known-diff.txt"

# 用例表：名字|方法|路径|body|token 模式|content-type
# ⚠️ **body 里不能出现 `|`** ⇒ 解析用 `IFS='|'`。
LIST_BODY_FULL="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"sort_type\":1,\"root_id\":0,\"page\":1,\"page_size\":20}"
LIST_BODY_HOT="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"sort_type\":0,\"root_id\":0,\"page\":1,\"page_size\":20}"
LIST_BODY_PAGE2="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"sort_type\":1,\"root_id\":0,\"page\":2,\"page_size\":1}"
LIST_BODY_REPLIES="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"sort_type\":1,\"root_id\":${ROOT_CID},\"page\":1,\"page_size\":20}"
LIST_BODY_MISSING="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"does-not-exist\",\"sort_type\":1,\"root_id\":0,\"page\":1,\"page_size\":20}"
LIST_BODY_BLANK_TYPE="{\"target_type\":\"   \",\"target_id\":\"${TARGET_ID}\",\"sort_type\":1,\"root_id\":0,\"page\":1,\"page_size\":20}"
LIST_BODY_BLANK_ID="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"\",\"sort_type\":1,\"root_id\":0,\"page\":1,\"page_size\":20}"
LIST_BODY_NOPAGESIZE="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"sort_type\":1,\"root_id\":0,\"page\":1}"
# ⚠️ **四处业务层兜底**（B 侧 `GetCommentService` 差异 4 · Go `getcommentlogic.go:47-55`）：
#    page≤0 ⇒ 1 · page_size≤0 ⇒ 20 · page_size>50 ⇒ 50 · root_id<0 ⇒ 0 · sort_type∉{0,1} ⇒ 0
#    ⇒ 这四条**在解析层之后**发生 ⇒ 是 L2 业务语义 ⇒ 探针必须逐条比。
LIST_BODY_PAGE_ZERO="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"sort_type\":1,\"root_id\":0,\"page\":0,\"page_size\":0}"
LIST_BODY_PAGESIZE_OVER="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"sort_type\":1,\"root_id\":0,\"page\":1,\"page_size\":999}"
LIST_BODY_SORT_INVALID="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"sort_type\":7,\"root_id\":0,\"page\":1,\"page_size\":20}"
LIST_BODY_ROOT_NEG="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"sort_type\":1,\"root_id\":-5,\"page\":1,\"page_size\":20}"
CREATE_BODY_FULL="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"root_id\":0,\"parent_id\":0,\"content\":\"探针对拍正文\",\"meta\":\"{}\"}"
CREATE_BODY_NOMETA="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"root_id\":0,\"parent_id\":0,\"content\":\"探针对拍正文\"}"
DELETE_BODY_FULL="{\"comment_id\":${ROOT_CID},\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\"}"
DELETE_BODY_PART="{\"comment_id\":${ROOT_CID}}"
LIKE_BODY_FULL="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"comment_id\":${ROOT_CID},\"action_type\":1}"
LIKE_BODY_PART="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"comment_id\":${ROOT_CID}}"
MANAGE_BODY_FULL="{\"comment_id\":${ROOT_CID},\"action_type\":1}"
MANAGE_BODY_PART="{\"comment_id\":${ROOT_CID}}"
REPORT_BODY_FULL="{\"comment_id\":${ROOT_CID},\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"reason\":\"探针对拍理由\"}"
REPORT_BODY_PART="{\"comment_id\":${ROOT_CID}}"
STATE_BODY_FULL="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"state\":0}"
STATE_BODY_PART="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\"}"

CASES="auth_missing_list|POST|/comment/v1/comment/list|${LIST_BODY_FULL}|none|application/json
auth_missing_create|POST|/comment/v1/comment/create|${CREATE_BODY_FULL}|none|application/json
auth_badsig_create|POST|/comment/v1/comment/create|${CREATE_BODY_FULL}|badsig|application/json
auth_expired_create|POST|/comment/v1/comment/create|${CREATE_BODY_FULL}|expired|application/json
auth_noclaim_delete|POST|/comment/v1/comment/delete|${DELETE_BODY_FULL}|noclaim|application/json
auth_nonnumeric_manage|POST|/comment/v1/comment/manage|${MANAGE_BODY_FULL}|nonnumeric|application/json
auth_noclaim_list|POST|/comment/v1/comment/list|${LIST_BODY_FULL}|noclaim|application/json
auth_goodstr_manage|POST|/comment/v1/comment/manage|${MANAGE_BODY_FULL}|goodstr|application/json
validate_missing_meta_create|POST|/comment/v1/comment/create|${CREATE_BODY_NOMETA}|good|application/json
validate_missing_field_delete|POST|/comment/v1/comment/delete|${DELETE_BODY_PART}|good|application/json
validate_missing_field_like|POST|/comment/v1/comment/like|${LIKE_BODY_PART}|good|application/json
validate_missing_field_list|POST|/comment/v1/comment/list|${LIST_BODY_NOPAGESIZE}|good|application/json
validate_missing_field_manage|POST|/comment/v1/comment/manage|${MANAGE_BODY_PART}|good|application/json
validate_missing_field_report|POST|/comment/v1/comment/report|${REPORT_BODY_PART}|good|application/json
validate_missing_field_subject_state|POST|/comment/v1/subject/state|${STATE_BODY_PART}|good|application/json
validate_blank_target_type_list|POST|/comment/v1/comment/list|${LIST_BODY_BLANK_TYPE}|good|application/json
validate_blank_target_id_list|POST|/comment/v1/comment/list|${LIST_BODY_BLANK_ID}|good|application/json
validate_empty_body_create|POST|/comment/v1/comment/create||good|application/json
validate_malformed_json_create|POST|/comment/v1/comment/create|{\"target_type\":|good|application/json
route_notfound|POST|/comment/v1/nonexistent|{}|good|application/json
route_notfound_noauth|POST|/comment/v1/nonexistent|{}|none|application/json
route_method_not_allowed|GET|/comment/v1/comment/list||good|application/json
route_wrong_prefix|POST|/comment/list|{}|good|application/json
content_type_text_plain|POST|/comment/v1/comment/list|${LIST_BODY_FULL}|good|text/plain
list_seeded_time|POST|/comment/v1/comment/list|${LIST_BODY_FULL}|good|application/json
list_seeded_hot|POST|/comment/v1/comment/list|${LIST_BODY_HOT}|good|application/json
list_seeded_page2|POST|/comment/v1/comment/list|${LIST_BODY_PAGE2}|good|application/json
list_seeded_replies|POST|/comment/v1/comment/list|${LIST_BODY_REPLIES}|good|application/json
list_seeded_missing_target|POST|/comment/v1/comment/list|${LIST_BODY_MISSING}|good|application/json
list_fallback_page_zero|POST|/comment/v1/comment/list|${LIST_BODY_PAGE_ZERO}|good|application/json
list_fallback_pagesize_over|POST|/comment/v1/comment/list|${LIST_BODY_PAGESIZE_OVER}|good|application/json
list_fallback_sort_invalid|POST|/comment/v1/comment/list|${LIST_BODY_SORT_INVALID}|good|application/json
list_fallback_root_negative|POST|/comment/v1/comment/list|${LIST_BODY_ROOT_NEG}|good|application/json"

token_for() {   # $1 = 模式 ⇒ 打印 token（none ⇒ 空）
  case "$1" in
    none) printf '' ;;
    good) printf '%s' "${TOKEN_GOOD}" ;;
    goodstr) printf '%s' "${TOKEN_GOODSTR}" ;;
    noclaim) printf '%s' "${TOKEN_NOCLAIM}" ;;
    nonnumeric) printf '%s' "${TOKEN_NONNUMERIC}" ;;
    expired) printf '%s' "${TOKEN_EXPIRED}" ;;
    badsig) printf '%s' "${TOKEN_BADSIG}" ;;
    *) die "未知 token 模式：$1" ;;
  esac
}

fetch() {   # $1 = 端口 $2 = 方法 $3 = 路径 $4 = body $5 = token $6 = content-type ⇒ body 落 stdout，头落 $7
  local port="$1" method="$2" path="$3" body="$4" tok="$5" ct="$6" hdr="$7"
  local args=(-s -m 30 -D "${hdr}" -X "${method}" -H "Content-Type: ${ct}")
  [ -n "${tok}" ] && args+=(-H "Authorization: Bearer ${tok}")
  if [ -n "${body}" ]; then
    curl "${args[@]}" --data-binary "${body}" "http://127.0.0.1:${port}${path}"
  else
    curl "${args[@]}" "http://127.0.0.1:${port}${path}"
  fi
}

# 🔴 **状态码/Content-Type 必须按 RFC 9110 跳过 1xx**：
#    共享层的 `ClientDisconnectProbe` 会**直接往 socket 写** `HTTP/1.1 103 Early Hints\r\n\r\n`
#    作断连探针（**不经过 Tomcat**）⇒ curl 的 `-D` 文件里会**多出一段 1xx 响应头**。
#    ⇒ 若用 `head -1` 取状态码，会把 103 当成最终状态 ⇒ **假红**（实测踩过一轮）。
#    ⇒ 判据：**只认 status ≥ 200 的那一段**；1xx 既不覆盖状态码、也不清空 content-type。
parse_final_response() {   # $1 = 头文件 ⇒ 打印 "<status>|<content-type>"
  tr -d '\r' < "${1}" | awk '
    BEGIN { st=""; ct="" }
    /^HTTP\// { code = $2 + 0; if (code >= 200) { st = code; ct = "" } ; next }
    tolower($1) == "content-type:" {
      v = substr($0, index($0, ":") + 1); gsub(/^[ \t]+/, "", v); ct = v
    }
    END { print st "|" ct }
  '
}

FAILED=0
KNOWN_COUNT=0
FREEZE_FILE=""
KNOWN_FREEZE_FILE=""
[ "${FREEZE:-0}" = "1" ] && { FREEZE_FILE="${WORK}/expected.txt"; KNOWN_FREEZE_FILE="${WORK}/known-diff.txt"; : > "${FREEZE_FILE}"; : > "${KNOWN_FREEZE_FILE}"; }

run_case() {   # $1 = 名字 $2 = 方法 $3 = 路径 $4 = body $5 = token 模式 $6 = content-type
  local name="$1" method="$2" path="$3" body="$4" tmode="$5" ct="$6"
  local tok b c bhex chex bstatus cstatus bct cct eh
  tok="$(token_for "${tmode}")"
  b="$(fetch "${B_API_PORT}" "${method}" "${path}" "${body}" "${tok}" "${ct}" "${WORK}/b.hdr")"
  c="$(fetch "${C_HTTP_PORT}" "${method}" "${path}" "${body}" "${tok}" "${ct}" "${WORK}/c.hdr")"
  bhex="$(printf '%s' "${b}" | xxd -p | tr -d '\n')"
  chex="$(printf '%s' "${c}" | xxd -p | tr -d '\n')"
  IFS='|' read -r bstatus bct < <(parse_final_response "${WORK}/b.hdr")
  IFS='|' read -r cstatus cct < <(parse_final_response "${WORK}/c.hdr")

  # ① 两侧逐字节
  if [ "${bhex}" != "${chex}" ] || [ "${bstatus}" != "${cstatus}" ] || [ "${bct}" != "${cct}" ]; then
    # ①-a **已知差异**：两侧字节确实不同，但**两侧各自的字节都被冻结**在 known-diff.txt
    #      ⇒ 若差异**形状**变了（任一侧的字节变了）⇒ 仍然判红 ⇒
    #      ⇒ 白名单是「更严的断言」，不是「免责清单」。
    local kd=""
    if [ -n "${KNOWN_FREEZE_FILE}" ]; then
      # ⚠️ **FREEZE 模式**：两侧字节不同 ⇒ 记为「已知差异」并**冻结双方各自的字节**。
      #    ⇒ 冻结之后，差异**形状**若变（任一侧字节变了）⇒ 正常跑法会判红。
      #    ⇒ ⚠️ 这是「人工确认后才可提交」的动作 ⇒ 下面会**逐条打印** ⇒ 必须逐条核对根因。
      printf 'DIFF|%s|%s|%s|%s|%s|%s|%s\n' "${name}" "${bstatus}" "${bct}" "${bhex}" "${cstatus}" "${cct}" "${chex}" >> "${KNOWN_FREEZE_FILE}"
      say "   ⚠️  ${name}  两侧字节不同 ⇒ **本轮冻结为已知差异**（须人工核对根因后再提交）"
      say "      B status=${bstatus} ct=${bct}"
      say "      C status=${cstatus} ct=${cct}"
      KNOWN_COUNT=$(( KNOWN_COUNT + 1 ))
      return
    fi
    if [ -f "${KNOWN_DIFF}" ]; then
      kd="$(awk -F'|' -v n="DIFF|${name}" '$1"|"$2==n {print $3"|"$4"|"$5"|"$6"|"$7"|"$8}' "${KNOWN_DIFF}")"
    fi
    if [ -n "${kd}" ] && [ "${kd}" = "${bstatus}|${bct}|${bhex}|${cstatus}|${cct}|${chex}" ]; then
      KNOWN_COUNT=$(( KNOWN_COUNT + 1 ))
      say "   ⚠️  ${name}  两侧**已知差异**（B/C 各自字节已冻结 ⇒ 形状变了仍会判红）"
      say "      B status=${bstatus} ct=${bct}"
      say "      C status=${cstatus} ct=${cct}"
      say "      差异根因见 README「已知差异」段 ＋ docs/DRIFT/comment 台账"
      return
    fi
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
  say "   ✅ ${name}  B == C（逐字节 · status ${bstatus} · ${bct:-<无 content-type>}）"

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

while IFS='|' read -r name method path body tmode ct; do
  [ -n "${name:-}" ] || continue
  run_case "${name}" "${method}" "${path}" "${body}" "${tmode}" "${ct}"
done <<< "${CASES}"

if [ -n "${FREEZE_FILE}" ]; then
  # 🔴 **只有本轮真的全绿才允许覆盖基线** ⇒ 否则一次坏跑就把基线写坏了
  if [ "${FAILED}" = "0" ]; then
    cp "${FREEZE_FILE}" "${EXPECTED}"
    say "   📌 已冻结基线 ⇒ ${EXPECTED}（FREEZE=1 · 依据见 README「基线冻结依据」）"
    if [ -n "${KNOWN_FREEZE_FILE}" ]; then
      cp "${KNOWN_FREEZE_FILE}" "${KNOWN_DIFF}"
      say "   📌 已冻结已知差异 ⇒ ${KNOWN_DIFF}（$(wc -l < "${KNOWN_DIFF}" | tr -d ' ') 条 · 逐条须在 README 登记根因）"
    fi
  else
    say "   ⚠️ 本轮有差异 ⇒ **不覆盖** ${EXPECTED}（基线保持不变）"
  fi
fi

# ══════════════════════════ 8. 播种未被污染核验 ═══════════════════════════════
step "8/10 播种未被污染核验（两侧的行数与内容必须仍等于种子）"
seed_fingerprint() {   # $1 = 容器名 ⇒ 打印指纹
  # ⚠️ **列名是 `comment_id`（不是 `id`）** —— `comment_content` 的主键列名与 `comment_index` 不同
  #    （V3__create_comment_content.sql: `comment_id BIGINT PRIMARY KEY`）⇒ 写成 `id` 会整条 SQL 报错
  #    ⇒ 而 `2>/dev/null` 把错误吞了 ⇒ 指纹变成空串 ⇒ 误报「库被改动」。
  docker exec "${1}" psql -U admin -d first_db -At -c \
    "SELECT (SELECT count(*) FROM subject) || '|' || (SELECT count(*) FROM comment_index) || '|' || (SELECT count(*) FROM comment_content) || '|' || (SELECT coalesce(string_agg(comment_id::text || ':' || content, ',' ORDER BY comment_id), '') FROM comment_content);" 2>/dev/null | tr -d '\r'
}
SEED_FP="1|2|2|${ROOT_CID}:根评论正文,${REPLY_CID}:子评论正文"
for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  fp="$(seed_fingerprint "${c}")"
  if [ "${fp}" != "${SEED_FP}" ]; then
    FAILED=1
    say "   ❌ ${c} 的库在请求矩阵期间**被改动**了 ⇒ 探针读数不可信"
    say "      期望指纹: ${SEED_FP}"
    say "      实际指纹: ${fp}"
    say "      （最可能原因：本轮 Kafka 隔离失效 ⇒ 审计消费者写进了库）"
  else
    say "   ✅ ${c} 播种未被污染（指纹一致）"
  fi
done

# ══════════════════════════ 9. 判决 ═══════════════════════════════════════════
step "9/10 判决"
echo
if [ "${FAILED}" = "0" ] && [ "${KNOWN_COUNT}" = "0" ]; then
  say "✅ 双侧逐字节一致（B 侧 Java 栈 == C 侧 Java 栈）"
  exit 0
fi
if [ "${FAILED}" = "0" ]; then
  say "✅ 双侧一致：**逐字节相等**的用例全过 ＋ **${KNOWN_COUNT} 例已知差异**（两侧字节不同，但**各自字节与冻结值一致** ⇒ 差异**形状未变**）"
  say "   ⇒ 已知差异的根因见 README「已知差异」段 ＋ docs/DRIFT/comment 台账 **D-14**"
  exit 0
fi
say "❌ 双侧存在字节差异（见上面逐条）"
dump_logs
exit 1
