#!/usr/bin/env bash
# ============================================================================
# favorite 域 · **双侧活体对拍**探针（B 侧 RAG ↔ C 侧 RAG-V1 · 第三方锚 = Go 真源冻结字节）
# ----------------------------------------------------------------------------
# 补的缺口：docs/DRIFT/favorite/verification-favorite-service-2026-11-04.md §8.2 第 1 条
#           「🔴 本域当前无 E-4 证据」⇒ 本探针把这条变成「✅ 已验」。
#
# 三方是哪三方：
#
#   ┌── 第三方锚 ──────────────┐   ┌── B 侧活体 ──────────┐   ┌── C 侧活体 ──────────┐
#   │ expected.txt             │   │ favorite-api :2xxxx   │   │ favorite-service     │
#   │ = Go 真源的冻结渲染字节   │   │ + favorite-rpc :2xxxx │   │ （D1 合并进程）:2xxxx │
#   │ （B 侧仓 2026-10-30 录） │   │ Boot 4.0.8            │   │ Boot 3.5.11          │
#   └──────────────────────────┘   └───────────────────────┘   └──────────────────────┘
#
# 判定：三方逐 case 逐字段全等（档位语义见 expected.txt 的注释头）。
#
# 为什么**不跑 Go 真源**：Go 侧走**etcd** 服务发现；本机没有 Sea-etcd 容器
#   ⇒ ⇒ 改用 B 侧仓**已冻结**的 expected.txt（Go 字节）作第三方锚
#   ⇒ ⇒ **三方等价的强度不降**：判的是「C 侧的每个字节 == 真源的每个字节」。
#
# 为什么**两侧共用一个 Redis**：黑名单 key（user:jwt_blacklist:<token>）在**同一命名空间**
#   ⇒ ⇒ 「同一个被拉黑的 token」两侧必然查到同一条 ⇒ 顺带验了**黑名单 key 的拼法**。
#   ⇒ ⇒ 两侧的 DB **必须分开**（各自的 Flyway 账本 + 各自的种子）。
#
# 用法：
#   bash tools/difftest/favorite/run.sh
#   B_REPO=/path/to/RAG bash tools/difftest/favorite/run.sh
#   KEEP_TMP=1 bash tools/difftest/favorite/run.sh      # 保留临时目录（看两侧日志）
#
# 退出码：0 = 三方一致；1 = 有差异；2 = 环境问题。
# ============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# ⚠️ HERE = <root>/tools/difftest/favorite ⇒ 上溯 **3** 级才是仓根
#    （4 级会跑到 worktree 的**父目录** ⇒ mvn 会报「Could not find the selected project in the reactor」）。
C_ROOT="$(cd "${HERE}/../../.." && pwd)"
B_REPO="${B_REPO:-/Users/pr/Documents/java&golang/RAG}"

say() { printf '\033[1;36m[difftest/favorite]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m  ✅ %s\033[0m\n' "$*"; }
bad() { printf '\033[1;31m  ❌ %s\033[0m\n' "$*"; }
die() { bad "$*"; exit 2; }

command -v docker >/dev/null 2>&1 || die "没装 docker（探针需要真 PG + 真 Redis）"
command -v mvn    >/dev/null 2>&1 || die "没装 mvn"
command -v java   >/dev/null 2>&1 || die "没装 java"
command -v python3 >/dev/null 2>&1 || die "没装 python3"
command -v javac  >/dev/null 2>&1 || die "没装 javac（探针是 JDK-only 独立程序）"
[ -d "${B_REPO}/service/favorite" ] || die "找不到 B 侧仓：${B_REPO}（用 B_REPO=... 指定）"
say "C 侧仓：${C_ROOT}"
say "B 侧仓：${B_REPO}"

# ── 0.5 冻结锚 / 种子的**溯源核对**（副本会漂 ⇒ 每次运行前核对原件）───────
# ⚠️ 为什么必须核对：本仓持有的是 B 侧冻结锚的**副本**
#    ⇒ ⇒ B 侧原件若变了（Go 真源改了并重新冻结）⇒ 副本就**脱节**了
#    ⇒ ⇒ 此时拿陈旧副本判「三方一致」= **假绿**（判据的输入不是被检查的对象）
#    ⇒ ⇒ 这里核对**原件**的 sha256 ⇒ 不一致就退出 ⇒ 逼人重新拷贝。
FROZEN_SHA="422ac8052ce17c4cdf1e78c238273b3094c46b5e92a0b889676de2985f637f97"
SEED_SHA="ecdb8b13ba97745c2b4863897447bb946df480c486388206e8f9431f93083925"
B_EXPECTED="${B_REPO}/tools/api-full-parity-probe/favorite/expected.txt"
B_SEED="${B_REPO}/tools/grpc-contract-probe/favorite/seed.sql"
[ -f "${B_EXPECTED}" ] || die "找不到 B 侧冻结锚原件：${B_EXPECTED}"
[ -f "${B_SEED}" ] || die "找不到 B 侧种子原件：${B_SEED}"
ACTUAL_FROZEN_SHA="$(shasum -a 256 "${B_EXPECTED}" | awk '{print $1}')"
ACTUAL_SEED_SHA="$(shasum -a 256 "${B_SEED}" | awk '{print $1}')"
[ "${ACTUAL_FROZEN_SHA}" = "${FROZEN_SHA}" ] \
  || die "B 侧冻结锚原件已变（sha256=${ACTUAL_FROZEN_SHA}）⇒ 本仓副本已脱节 ⇒ 请重新拷贝 expected.txt"
[ "${ACTUAL_SEED_SHA}" = "${SEED_SHA}" ] \
  || die "B 侧种子原件已变（sha256=${ACTUAL_SEED_SHA}）⇒ 本仓副本已脱节 ⇒ 请重新拷贝 seed.sql"
ok "冻结锚 / 种子溯源核对通过（B 侧原件 sha256 一致）"

# ── 0.6 gRPC 面的**第三方锚**（Go 真源冻结的 gRPC 级渲染值）────────────────
# ⚠️ 这份锚与上面那份 HTTP 面的锚**不是同一份文件**：
#    · HTTP 面 = tools/api-full-parity-probe/favorite/expected.txt（32 case · JSON 面）
#    · gRPC 面 = tools/grpc-contract-probe/favorite/expected.txt（36 case · gRPC 可观测契约面）
# ⇒ ⇒ **两个面各有自己的 Go 冻结锚** ⇒ ⇒ 三方一致是**两面各证一次**，不是一次。
GRPC_FROZEN_SHA="cf6c25bebe7a7d6cbd2a6ef757745eacfb4efc99272377bd2c1933e69e78518a"
B_GRPC_EXPECTED="${B_REPO}/tools/grpc-contract-probe/favorite/expected.txt"
C_GRPC_EXPECTED="${HERE}/grpc-wire/expected.txt"
[ -f "${B_GRPC_EXPECTED}" ] || die "找不到 B 侧 gRPC 冻结锚原件：${B_GRPC_EXPECTED}"
[ -f "${C_GRPC_EXPECTED}" ] || die "找不到本仓 gRPC 冻结锚副本：${C_GRPC_EXPECTED}"
ACTUAL_GRPC_FROZEN_SHA="$(shasum -a 256 "${B_GRPC_EXPECTED}" | awk '{print $1}')"
[ "${ACTUAL_GRPC_FROZEN_SHA}" = "${GRPC_FROZEN_SHA}" ] \
  || die "B 侧 gRPC 冻结锚原件已变（sha256=${ACTUAL_GRPC_FROZEN_SHA}）⇒ 本仓副本已脱节 ⇒ 请重新拷贝"
# 🔴 **第二道核对：副本的数据行必须与原件逐行相同**
#    ⇒ 只核「B 侧原件没变」**抓不到**「有人改了本仓副本里的冻结值」⇒ 那是假绿。
python3 "${HERE}/grpc-wire/check-anchor-copy.py" "${B_GRPC_EXPECTED}" "${C_GRPC_EXPECTED}" \
  || die "本仓 gRPC 冻结锚副本与 B 侧原件的冻结列不一致 ⇒ 请重新拷贝（别改副本里的冻结值）"
# 🔴🔴 **档位表必须存在**（「Java ↔ Java」那一段的判据）：
#    缺了 ⇒ 「C == B」那一段**根本没跑** ⇒ 却会返回 0 ⇒ ⇒ **假绿**
#    ⇒ ⇒ 提前 die ⇒ 不给「漏跑」留任何机会。
[ -f "${HERE}/grpc-wire/kinds-bc.txt" ] || die "缺 gRPC 面的「Java ↔ Java」档位表：${HERE}/grpc-wire/kinds-bc.txt"
ok "gRPC 面冻结锚溯源核对通过（B 侧原件 sha256 一致 + 本仓副本逐行相同）"

# ── 0. 端口分配（专用段 21600-21699 + 双绑定自检 ⇒ 绝不与别的会话撞）────────
# ⚠️ 为什么**不能**用 OS 随机端口：随机端口在**临时端口段 49152–65535**，
#    而那正是 **Docker 随机发布容器端口**的段 ⇒ 别的会话的容器随时可能占住
#    ⇒ macOS/BSD 上 127.0.0.1:P 与 0.0.0.0:P 能共存 ⇒ 客户端会打到**别人的服务**上
#    ⇒ 表现为「连上了但不是自己的服务」⇒ **整个探针结果不可信**。
# ⚠️ 本段与本仓其它段不重叠：C 侧测试树 22910-22939 / 真 Tomcat 21400-21499 /
#    B 侧自带探针 21400-21599 ⇒ 本探针独占 21600-21699。
free_port() {
  python3 - "$@" <<'PY'
import socket, sys
FIRST, LAST = 21600, 21699
used = {int(x) for x in sys.argv[1:] if x.strip()}
for p in range(FIRST, LAST + 1):
    if p in used:
        continue
    socks = []
    try:
        for addr in (("0.0.0.0", p), ("127.0.0.1", p)):
            s = socket.socket()
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(addr)
            s.listen(1)
            socks.append(s)
        print(p)
        break
    except OSError:
        for s in socks:
            s.close()
else:
    sys.exit("端口段 %d-%d 已占满（并行会话太多？）" % (FIRST, LAST))
PY
}
USED_PORTS=()
B_RPC_PORT="$(free_port ${USED_PORTS[@]+"${USED_PORTS[@]}"})"; USED_PORTS+=("${B_RPC_PORT}")
B_API_PORT="$(free_port ${USED_PORTS[@]+"${USED_PORTS[@]}"})"; USED_PORTS+=("${B_API_PORT}")
C_HTTP_PORT="$(free_port ${USED_PORTS[@]+"${USED_PORTS[@]}"})"; USED_PORTS+=("${C_HTTP_PORT}")
C_GRPC_PORT="$(free_port ${USED_PORTS[@]+"${USED_PORTS[@]}"})"; USED_PORTS+=("${C_GRPC_PORT}")
PG_PORT="$(free_port ${USED_PORTS[@]+"${USED_PORTS[@]}"})"; USED_PORTS+=("${PG_PORT}")
REDIS_PORT="$(free_port ${USED_PORTS[@]+"${USED_PORTS[@]}"})"; USED_PORTS+=("${REDIS_PORT}")
say "端口：B rpc=${B_RPC_PORT} · B api=${B_API_PORT} · C http=${C_HTTP_PORT} · C grpc=${C_GRPC_PORT} · PG=${PG_PORT} · Redis=${REDIS_PORT}"

wait_port() {  # $1=port $2=name $3=tries
  local port="$1" name="$2" tries="${3:-120}"
  for _ in $(seq 1 "$tries"); do
    if python3 -c "import socket,sys;s=socket.socket();s.settimeout(0.5);sys.exit(0 if s.connect_ex(('127.0.0.1',${port}))==0 else 1)"; then
      return 0
    fi
    sleep 1
  done
  echo "  ✗ ${name} 端口 ${port} 超时未就绪" >&2
  return 1
}

TMP="$(mktemp -d)"
PG_CONTAINER="fav-e4-pg-$$"
REDIS_CONTAINER="fav-e4-redis-$$"
PIDS=()
cleanup() {
  for p in "${PIDS[@]:-}"; do kill "$p" >/dev/null 2>&1; done
  for p in "${PIDS[@]:-}"; do wait "$p" >/dev/null 2>&1; done
  docker rm -f "${PG_CONTAINER}" "${REDIS_CONTAINER}" >/dev/null 2>&1 || true
  if [ "${KEEP_TMP:-0}" = "1" ]; then
    say "KEEP_TMP=1 ⇒ 临时目录保留在 ${TMP}"
  else
    rm -rf "${TMP}"
  fi
}
trap cleanup EXIT

# ── 1. 构建两侧的可部署产物（fat jar = 真进程启动的东西）──────────────────
say "构建 B 侧 favorite-api / favorite-rpc（-am 必须带 ⇒ 不用过期上游产物）"
( cd "${B_REPO}" && mvn -o -q -pl service/favorite/favorite-api,service/favorite/favorite-rpc -am \
    package -Dmaven.test.skip=true ) > "${TMP}/mvn-b.log" 2>&1 \
  || { bad "B 侧 jar 构建失败"; tail -40 "${TMP}/mvn-b.log"; exit 2; }
B_RPC_JAR="${B_REPO}/service/favorite/favorite-rpc/target/favorite-rpc-0.0.1-SNAPSHOT.jar"
B_API_JAR="${B_REPO}/service/favorite/favorite-api/target/favorite-api-0.0.1-SNAPSHOT.jar"
[ -f "${B_RPC_JAR}" ] || die "B 侧 rpc jar 不存在：${B_RPC_JAR}"
[ -f "${B_API_JAR}" ] || die "B 侧 api jar 不存在：${B_API_JAR}"

say "构建 C 侧 favorite-service（fat jar = 可部署产物本身）"
( cd "${C_ROOT}" && mvn -o -q -pl service/favorite -am package -Dmaven.test.skip=true ) > "${TMP}/mvn-c.log" 2>&1 \
  || { bad "C 侧 jar 构建失败"; tail -40 "${TMP}/mvn-c.log"; exit 2; }
C_JAR="${C_ROOT}/service/favorite/target/favorite-service-1.0-SNAPSHOT.jar"
[ -f "${C_JAR}" ] || die "C 侧 jar 不存在：${C_JAR}"
ok "两侧可部署产物就绪"

# ── 2. 起真 PG（两个库：first_db = B 侧 / fav_c_db = C 侧）+ 真 Redis ──────
say "起 postgres:16-alpine 容器：${PG_CONTAINER}"
# ⚠️ PG 就绪等待三件套：① 必须探 **TCP**（-h 127.0.0.1）⇒ 官方镜像初始化阶段的
#    临时 server 只监听 unix socket ⇒ 走 socket 探活会**假就绪**；
#    ② 上限 180s（机器高负载时 initdb 本身就要几十秒）；③ 没就绪就**重建容器重来一次**。
wait_pg_tcp() {
  local tries="$1"
  for _ in $(seq 1 "${tries}"); do
    docker exec "${PG_CONTAINER}" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1 && return 0
    [ "$(docker inspect -f '{{.State.Running}}' "${PG_CONTAINER}" 2>/dev/null || echo false)" = "true" ] || return 1
    sleep 1
  done
  return 1
}
PG_READY=0
for attempt in 1 2; do
  docker rm -f "${PG_CONTAINER}" >/dev/null 2>&1 || true
  if ! docker run -d --rm --name "${PG_CONTAINER}" \
      -e POSTGRES_PASSWORD=probe -e POSTGRES_USER=postgres \
      -p "${PG_PORT}:5432" postgres:16-alpine >/dev/null; then
    say "   PG 容器第 ${attempt} 次启动失败（宿主口 ${PG_PORT} 可能被占）⇒ 重来"
    PG_PORT="$(free_port ${USED_PORTS[@]+"${USED_PORTS[@]}"})"; USED_PORTS+=("${PG_PORT}")
    continue
  fi
  if wait_pg_tcp 180; then PG_READY=1; break; fi
  say "   PG 第 ${attempt} 次 180s 内没就绪 ⇒ 重建容器重来"
done
[ "${PG_READY}" = "1" ] || die "PG 没就绪（已重建容器重试 1 次）"
for db in first_db fav_c_db; do
  db_ok=0
  for _ in $(seq 1 15); do
    if docker exec "${PG_CONTAINER}" psql -U postgres -h 127.0.0.1 -c "CREATE DATABASE ${db};" >/dev/null 2>&1; then
      db_ok=1; break
    fi
    sleep 2
  done
  [ "${db_ok}" = "1" ] || die "建库 ${db} 失败"
done
ok "PG 就绪（127.0.0.1:${PG_PORT}）· 两个库：first_db（B 侧）/ fav_c_db（C 侧）"

say "起 redis:7-alpine 容器（**两侧共用** ⇒ 黑名单 key 在同一命名空间）：${REDIS_CONTAINER}"
# ⚠️ **刻意不加 --rm**：场景轮（S5）要 `docker stop` 再 `docker start` 制造「Redis 不可用」
#    ⇒ 带 --rm 的话 stop 之后容器**被删掉** ⇒ 再也起不来 ⇒ ⇒ 清理仍由 cleanup 的 `docker rm -f` 负责。
docker run -d --name "${REDIS_CONTAINER}" -p "${REDIS_PORT}:6379" redis:7-alpine >/dev/null || die "Redis 容器启动失败"
for _ in $(seq 1 60); do
  docker exec "${REDIS_CONTAINER}" redis-cli ping 2>/dev/null | grep -q PONG && break
  [ "$(docker inspect -f '{{.State.Running}}' "${REDIS_CONTAINER}" 2>/dev/null || echo false)" = "true" ] || break
  sleep 1
done
docker exec "${REDIS_CONTAINER}" redis-cli ping 2>/dev/null | grep -q PONG || die "Redis 没就绪"
ok "Redis 就绪（127.0.0.1:${REDIS_PORT}）"

# ── 3. JWT 密钥（**两侧同一把** ⇒ 同一枚 token 两边都能验过）───────────────
# ⚠️ 必须 ≥32 字节 ⇒ 两侧 jjwt 的 padToHmacKeySize 都是 no-op ⇒ 落到同一份 key 上。
JWT_SECRET="ae0536f9-6450-4606-8e13-5a19ed505da0"

# ── 4/5. 两侧启停（**抽成函数** ⇒ 场景轮要换下游地址重启）──────────────────
# 🔴 **为什么必须抽成函数**：
#    32 case 的矩阵是**故意**让下游缺席的（`static://127.0.0.1:1` ⇒ 走「下游不可用」那条契约分支）
#    ⇒ ⇒ 而场景轮（§12）要求下游**在场** ⇒ ⇒ 两个阶段**不能共用一次启动**
#    ⇒ ⇒ 场景轮前要**停掉两侧、按新地址重启**。
# 🔴 **两个下游地址参数分开**：C 侧第一阶段**刻意不覆盖**（保持 yml 里的 `discovery:///user-rpc`
#    ⇒ 与冻结锚那 32 case 的基线**一字不差**）⇒ ⇒ 覆盖会改掉「下游缺席」的错误路径 ⇒ ⇒ 冻结值对不上。
#    ⇒ ⇒ 场景轮阶段才把**两侧**都指到下游桩（**同址** ⇒ 走**同形**路径 ⇒ 比出来的才是行为差异）。
SIDE_PIDS=()

start_sides() {   # $1 = B 侧下游地址（空 = 不覆盖）· $2 = C 侧下游地址（空 = 不覆盖）
  local b_downstream="$1"
  local c_downstream="$2"

  say "启动 B 侧 favorite-rpc（gRPC :${B_RPC_PORT}）"
  # ⚠️ rpc 侧**不能**关 discovery ⇒ NacosServiceManager 会拿不到 ⇒ 上下文起不来
  #    ⇒ 改用「指向死地址」⇒ 自动配置仍在 ⇒ 注册失败只打一条 ERROR 日志，服务照常起。
  # ⚠️ user-rpc / article-rpc 指向**死地址** ⇒ 走「下游不可用」这条契约分支（与 Go 侧同款）。
  local b_rpc_downstream_args=()
  [ -n "${b_downstream}" ] && b_rpc_downstream_args=(
      "--grpc.client.user-rpc.address=${b_downstream}"
      "--grpc.client.article-rpc.address=${b_downstream}")
  env POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${PG_PORT}" POSTGRES_USER=postgres \
      POSTGRES_PASSWORD=probe POSTGRES_DB=first_db \
    java -jar "${B_RPC_JAR}" \
      --grpc.server.port="${B_RPC_PORT}" \
      --spring.cloud.nacos.discovery.server-addr=127.0.0.1:1 \
      --management.otlp.tracing.export.enabled=false \
      ${b_rpc_downstream_args[@]+"${b_rpc_downstream_args[@]}"} \
      > "${TMP}/b-rpc.log" 2>&1 &
  SIDE_PIDS+=($!); PIDS+=($!)
  wait_port "${B_RPC_PORT}" "B favorite-rpc" 180 || { tail -40 "${TMP}/b-rpc.log"; exit 2; }
  ok "B 侧 favorite-rpc 已就绪 · 真 Flyway 已在 first_db 建表"

  say "启动 B 侧 favorite-api（HTTP :${B_API_PORT}）"
  # ⚠️ api 侧**可以**关 discovery ⇒ 但自动注册在 Nacos 不可达时会 failFast ⇒ 整个 api 起不来
  #    ⇒ 直接关掉（gRPC 走 static:// 直连 rpc，不需要发现）。
  # 🔴 **故意不传 --spring.main.web-application-type** ⇒ 产物里那行 yaml 若被删掉，探针直接红。
  env REDIS_ADDR=127.0.0.1 REDIS_PORT="${REDIS_PORT}" REDIS_PASSWORD= \
    java -jar "${B_API_JAR}" \
      --server.port="${B_API_PORT}" \
      --spring.cloud.nacos.discovery.enabled=false \
      --management.otlp.tracing.export.enabled=false \
      --grpc.client.favorite-rpc.address="static://127.0.0.1:${B_RPC_PORT}" \
      --grpc.client.favorite-rpc.negotiation-type=plaintext \
      --favorite-api.user-auth.access-secret="${JWT_SECRET}" \
      > "${TMP}/b-api.log" 2>&1 &
  SIDE_PIDS+=($!); PIDS+=($!)
  wait_port "${B_API_PORT}" "B favorite-api" 180 || { tail -40 "${TMP}/b-api.log"; exit 2; }
  ok "B 侧 favorite-api 已就绪"

  say "启动 C 侧 favorite-service（HTTP :${C_HTTP_PORT} / gRPC :${C_GRPC_PORT}）"
  # ⚠️ C 侧的 D2（域内直连）⇒ **没有**自我 gRPC 客户端 ⇒ 不需要 discovery。
  # ⚠️ Nacos 指向死地址 ⇒ 手写的 FavoriteNacosRegistration 注册失败被 catch（NacosException）⇒ 不阻塞。
  local c_downstream_args=()
  [ -n "${c_downstream}" ] && c_downstream_args=(
      "--grpc.client.user-rpc.address=${c_downstream}"
      "--grpc.client.article-rpc.address=${c_downstream}")
  env POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${PG_PORT}" POSTGRES_USER=postgres \
      POSTGRES_PASSWORD=probe POSTGRES_DB=fav_c_db \
      REDIS_HOST=127.0.0.1 REDIS_PORT="${REDIS_PORT}" REDIS_PASSWORD= \
      FAVORITE_JWT_SECRET="${JWT_SECRET}" FAVORITE_HTTP_PORT="${C_HTTP_PORT}" \
      FAVORITE_GRPC_PORT="${C_GRPC_PORT}" \
    java -jar "${C_JAR}" \
      --spring.cloud.nacos.discovery.server-addr=127.0.0.1:1 \
      --spring.cloud.nacos.discovery.register-enabled=false \
      --management.otlp.tracing.export.enabled=false \
      ${c_downstream_args[@]+"${c_downstream_args[@]}"} \
      > "${TMP}/c.log" 2>&1 &
  SIDE_PIDS+=($!); PIDS+=($!)
  wait_port "${C_HTTP_PORT}" "C favorite-service" 180 || { tail -40 "${TMP}/c.log"; exit 2; }
  wait_port "${C_GRPC_PORT}" "C favorite-service gRPC" 60 || { tail -40 "${TMP}/c.log"; exit 2; }
  ok "C 侧 favorite-service 已就绪 · 真 Flyway 已在 fav_c_db 建表"
}

stop_sides() {
  for p in "${SIDE_PIDS[@]:-}"; do kill "$p" >/dev/null 2>&1; done
  for p in "${SIDE_PIDS[@]:-}"; do wait "$p" >/dev/null 2>&1; done
  SIDE_PIDS=()
}

# 🔴 第一阶段：下游**缺席**（矩阵要验的是「下游不可用」那条契约分支）
#    ⇒ B 侧指死地址；C 侧**不覆盖**（保持 yml 里的 discovery:/// ⇒ 与冻结锚基线一字不差）
start_sides "static://127.0.0.1:1" ""

# ── 6. 灌种子（两侧同一份 ⇒ 读到同一批行 ⇒ 响应字节可比）─────────────────
say "灌种子到两侧的库"
for db in first_db fav_c_db; do
  docker exec -i "${PG_CONTAINER}" psql -U postgres -h 127.0.0.1 -d "${db}" -v ON_ERROR_STOP=1 \
    -f - < "${HERE}/seed.sql" > "${TMP}/seed-${db}.log" 2>&1 \
    || { bad "seed ${db} 失败"; cat "${TMP}/seed-${db}.log"; exit 2; }
done
ok "两侧种子就绪"

# ── 7. 签 token（同一把 secret ⇒ 两侧都验得过）────────────────────────────
say "签 token（两侧共用同一把 secret）"
VALID_USER1="$(python3 "${HERE}/mint-jwt.py" "${JWT_SECRET}" 1001)"
VALID_USER2="$(python3 "${HERE}/mint-jwt.py" "${JWT_SECRET}" 2002)"
EXPIRED="$(python3 "${HERE}/mint-jwt.py" "${JWT_SECRET}" 1001 86400 --expired)"
BADSIG="$(python3 "${HERE}/mint-jwt.py" "00000000-0000-0000-0000-000000000000" 1001)"
# 🔴🔴 **黑名单对照组必须与 VALID_USER1 是两枚不同的 token**：
#   同一秒内用**同样的 secret + 同样的 userId** 签出来的 token 是**逐字节相同**的
#   ⇒ ⇒ 若不把 iat 错开，把 BLACKLISTED 写进黑名单 = 把 VALID_USER1 也拉黑
#   ⇒ ⇒ 「所有带 token 的用例都 401」⇒ **症状长得像产品回归，实则是探针自伤**
#   ⇒ ⇒ 这里用 --iat-offset -120 把两枚错开。
BLACKLISTED="$(python3 "${HERE}/mint-jwt.py" "${JWT_SECRET}" 1001 86400 --iat-offset -120)"
[ -n "${VALID_USER1}" ] && [ -n "${BLACKLISTED}" ] || die "token 没签出来"
[ "${VALID_USER1}" != "${BLACKLISTED}" ] || die "BLACKLISTED 与 VALID_USER1 是同一枚 token ⇒ 探针自伤"
ok "token 就绪（VALID_USER1 / VALID_USER2 / EXPIRED / BADSIG / BLACKLISTED）"

# ── 8. 拉黑一个 token（黑名单对照组）─────────────────────────────────────
# ⚠️ 必须有「被拉黑的 token」对照组，否则 a06 的 401 无意义：
#    「两侧都返回 401」有两种可能 ——（a）真的查到了黑名单；（b）根本没在查。
say "把 BLACKLISTED token 写进共用 Redis 的黑名单（对照组）"
docker exec "${REDIS_CONTAINER}" redis-cli SET "user:jwt_blacklist:${BLACKLISTED}" 1 >/dev/null
BL_COUNT="$(docker exec "${REDIS_CONTAINER}" redis-cli exists "user:jwt_blacklist:${BLACKLISTED}" | tr -d '\r')"
[ "${BL_COUNT}" = "1" ] || die "黑名单 key 没写进去（exists=${BL_COUNT}）⇒ 对照组无意义"
ok "黑名单对照 token 就绪（Redis exists=1）"

# ⚠️ **ONLY_SCENARIOS=1** ⇒ 只跑场景轮（跳过矩阵 + gRPC 面）
#    ⇒ 用途：**场景轮的变异验证**（变异注入在场景轮内部 ⇒ 矩阵阶段会先红 ⇒ 抢在前面 exit）
#    ⇒ ⇒ 变异验证必须能**只**跑到被验的那一面 ⇒ 这个开关就是为此存在的。
if [ "${ONLY_SCENARIOS:-0}" != "1" ]; then

# ── 9. 编译并跑探针（JDK-only ⇒ classpath 恒为空）────────────────────────
# ⚠️ SKIP_HTTP=1 ⇒ **只跑 gRPC 面**（HTTP 面整段跳过）。
#    用途：**变异验证**要单独验 gRPC 面的判据 ⇒ HTTP 面会先红并 exit(1)
#    ⇒ ⇒ 不跳过就**永远走不到** gRPC 面 ⇒ 那就验不了 gRPC 面的牙齿。
if [ "${SKIP_HTTP:-0}" = "1" ]; then
  say "SKIP_HTTP=1 ⇒ 跳过 HTTP 面（只验 gRPC 面）"
else
say "编译并跑探针（同一个 HttpClient 打两侧）"
mkdir -p "${TMP}/probe-classes"
javac -d "${TMP}/probe-classes" "${HERE}/probe/FavoriteApiParityProbe.java" > "${TMP}/javac.log" 2>&1 \
  || { bad "探针编译失败"; cat "${TMP}/javac.log"; exit 2; }
export PROBE_B_API="http://127.0.0.1:${B_API_PORT}/favorite/v1"
export PROBE_C_API="http://127.0.0.1:${C_HTTP_PORT}/favorite/v1"
export PROBE_TOKEN_VALID_USER1="${VALID_USER1}"
export PROBE_TOKEN_VALID_USER2="${VALID_USER2}"
export PROBE_TOKEN_EXPIRED="${EXPIRED}"
export PROBE_TOKEN_BADSIG="${BADSIG}"
export PROBE_TOKEN_BLACKLISTED="${BLACKLISTED}"
export PROBE_OUT_B="${TMP}/b.txt"
export PROBE_OUT_C="${TMP}/c.txt"
java -cp "${TMP}/probe-classes" probe.FavoriteApiParityProbe > "${TMP}/probe.log" 2>&1
PROBE_RC=$?
if [ "${PROBE_RC}" -ne 0 ]; then
  bad "探针失败（exit ${PROBE_RC}）"
  cat "${TMP}/probe.log"
  echo "---- B 侧日志尾部 ----"; tail -25 "${TMP}/b-api.log"
  echo "---- C 侧日志尾部 ----"; tail -25 "${TMP}/c.log"
  exit 2
fi
[ -s "${TMP}/b.txt" ] || die "探针没产出 B 侧结果文件"
[ -s "${TMP}/c.txt" ] || die "探针没产出 C 侧结果文件"
ok "两侧各产出 $(grep -c '' "${TMP}/b.txt") 个 case"
fi   # ← 收口 step 9 的 SKIP_HTTP 分支

# ── 10. HTTP 面三方逐条比对 ───────────────────────────────────────────────
if [ "${SKIP_HTTP:-0}" = "1" ]; then
  say "SKIP_HTTP=1 ⇒ 跳过 HTTP 面比对"
else
say "HTTP 面逐条比对（Go 冻结字节 vs B 侧活体 vs C 侧活体）"
python3 "${HERE}/compare.py" "${HERE}/expected.txt" "${TMP}/b.txt" "${TMP}/c.txt"
RC=$?
[ "${RC}" -eq 0 ] || exit 1
fi

# ══════════════════════════════════════════════════════════════════════════
# ── 11. gRPC 面（rpc 对 rpc）· 三方双侧活体对拍 ───────────────────────────
# ══════════════════════════════════════════════════════════════════════════
# ⚠️ **为什么要单独做这一层**：
#    HTTP 面的 JSON 字节一致 **推不出** gRPC wire 字节一致
#    （proto 编码 / 字段号 / status description / metadata key 全是另一套面）
#    ⇒ ⇒ 这一层才是「**客户端拿到的 favorite 契约**」的直接证据。
# ⚠️ **探针不用任何 pb 生成码**（手构 proto3 字节 + byte[] marshaller）
#    ⇒ **同一个 jar 跑两侧** ⇒ 比的是纯 wire 字节。
#    ⚠️ 两侧的 pb 包名不同（B 侧 com.rag.favorite.favorite.proto / C 侧 com.rag.proto.favorite）
#    ⇒ 用各自的生成类构造请求 ⇒ 两侧跑的不是同一段代码 ⇒ 证据被污染。

# ── 11.1 重灌种子（🔴 **必须**：HTTP 面的写用例已经改过库了）──────────────
# ⚠️ HTTP 面的 w01/w02/w03 改了库（改名 9002 / 删 7001 / 删 9002）
#    ⇒ ⇒ gRPC 面若接着跑，读到的就不是**种子态** ⇒ 冻结值对不上 ⇒ 假红。
# ⇒ 处置：gRPC 面之前**清空 + 重灌同一份种子** ⇒ 两侧回到同一个起点。
# ⚠️ 清库**不重置序列** ⇒ 但种子**不写 id 列**（自增代理键由序列走）
#    ⇒ ⇒ 重灌后的自增值与首次灌入相同 ⇒ 与冻结锚同形。
# ⚠️ 两张表**无外键**（V3/V4/V5 只有 CHECK，没有 REFERENCES）⇒ 删除顺序不敏感，
#    但仍按「先 item 后 folder」删（避免任何将来的外键加进来时炸）。
say "重灌种子（gRPC 面要 pristine 行：HTTP 面的写用例已改过库）"
for db in first_db fav_c_db; do
  docker exec -i "${PG_CONTAINER}" psql -U postgres -h 127.0.0.1 -d "${db}" -v ON_ERROR_STOP=1 \
    -c "DELETE FROM favorite_item; DELETE FROM favorite_folder;" > "${TMP}/reseed-${db}.log" 2>&1 \
    || { bad "清库 ${db} 失败"; cat "${TMP}/reseed-${db}.log"; exit 2; }
  docker exec -i "${PG_CONTAINER}" psql -U postgres -h 127.0.0.1 -d "${db}" -v ON_ERROR_STOP=1 \
    -f - < "${HERE}/seed.sql" >> "${TMP}/reseed-${db}.log" 2>&1 \
    || { bad "重灌种子 ${db} 失败"; cat "${TMP}/reseed-${db}.log"; exit 2; }
done
ok "两侧种子已重灌（回到 pristine 态）"

# ── 11.2 编译 gRPC wire 探针（classpath **唯一来源** = C 侧 fat jar）───────
# 🔴 **为什么 classpath 从 fat jar 里解，而不是从 ~/.m2 里找**：
#    「探针量到的是哪一版依赖」本身就会成疑点 ⇒ ⇒ 探针的依赖必须**唯一来源**
#    ⇒ 从**被测产物**的 BOOT-INF/lib 解出来 ⇒ 与运行期完全一致。
#    （本仓先例：tools/difftest/users-user/run.sh:253-266 的 pick_jar 是弱版本，
#      这里改用更强的「从产物解」。）
say "编译 gRPC wire 探针（classpath 取自 C 侧 fat jar ⇒ 依赖版本唯一来源）"
WIRE_LIB="${TMP}/wire-lib"
WIRE_CLASSES="${TMP}/wire-classes"
mkdir -p "${WIRE_LIB}" "${WIRE_CLASSES}"
unzip -o -q -j "${C_JAR}" 'BOOT-INF/lib/*.jar' -d "${WIRE_LIB}" \
  || die "从 C 侧 fat jar 解依赖失败"
javac -nowarn -encoding UTF-8 -cp "${WIRE_LIB}/*" -d "${WIRE_CLASSES}" \
  "${HERE}/grpc-wire/FavoriteWireProbe.java" > "${TMP}/wire-javac.log" 2>&1 \
  || { bad "gRPC wire 探针编译失败"; cat "${TMP}/wire-javac.log"; exit 2; }
ok "gRPC wire 探针编译完成（grpc-api $(basename "$(ls "${WIRE_LIB}"/grpc-api-*.jar 2>/dev/null | head -1)"))"

# ── 11.3 跑探针（同一个 jar 打两侧）──────────────────────────────────────
say "跑 gRPC wire 探针（B 侧 :${B_RPC_PORT} / C 侧 :${C_GRPC_PORT}）"
WIRE_CP="${WIRE_CLASSES}:${WIRE_LIB}/*"
java -cp "${WIRE_CP}" com.rag.favorite.parity.FavoriteWireProbe \
  127.0.0.1 "${B_RPC_PORT}" "${TMP}/wire-b.txt" > "${TMP}/wire-b.log" 2>&1
WIRE_B_RC=$?
java -cp "${WIRE_CP}" com.rag.favorite.parity.FavoriteWireProbe \
  127.0.0.1 "${C_GRPC_PORT}" "${TMP}/wire-c.txt" > "${TMP}/wire-c.log" 2>&1
WIRE_C_RC=$?
if [ "${WIRE_B_RC}" != "0" ] || [ "${WIRE_C_RC}" != "0" ]; then
  bad "gRPC wire 探针异常退出：B=${WIRE_B_RC} · C=${WIRE_C_RC}"
  echo "---- B 侧日志尾部 ----"; tail -20 "${TMP}/wire-b.log" | sed 's/^/      | /'
  echo "---- C 侧日志尾部 ----"; tail -20 "${TMP}/wire-c.log" | sed 's/^/      | /'
  exit 2
fi
[ -s "${TMP}/wire-b.txt" ] || die "gRPC wire 探针没产出 B 侧结果"
[ -s "${TMP}/wire-c.txt" ] || die "gRPC wire 探针没产出 C 侧结果"
ok "两侧各产出 $(grep -c '' "${TMP}/wire-b.txt") 个 gRPC case"

# ── 11.4 三方逐条比对（Go 冻结锚 ↔ B 侧活体 ↔ C 侧活体）─────────────────
say "gRPC 面逐条比对（Go 冻结锚 vs B 侧活体 vs C 侧活体）"
python3 "${HERE}/grpc-wire/compare-grpc.py" "${C_GRPC_EXPECTED}" "${TMP}/wire-b.txt" "${TMP}/wire-c.txt" \
  "${HERE}/grpc-wire/kinds-bc.txt"
RC=$?
if [ "${RC}" -ne 0 ]; then
  # 🔴 退出码要**原样传出去**：2 = 环境/判据不完整（不是「有差异」）⇒ 别混为一谈
  exit "${RC}"
fi

fi   # ← 收口 ONLY_SCENARIOS 分支

# ══════════════════════════════════════════════════════════════════════════
# ── 12. 场景轮（6 类场景 · 双侧活体 · **无第三方锚**）────────────────────
# ⚠️ **为什么单独一层**：
#    32 case 矩阵是「单请求 × 请求面」的矩阵 ⇒ ⇒ **结构性覆盖不到**下面 5 类：
#      create 成功路径（矩阵里下游缺席）· 并发（矩阵串行）· 超时（缺席是立即失败，
#      压根不触发出向预算）· 部分成功（没有多步写中途失败）· Redis 故障（Redis 一直在）。
# ⚠️ **证据强度低于 32 case**：Go 真源跑不起来 ⇒ ⇒ 本层**只有 B↔C 双侧**，**没有第三方锚**
#    ⇒ ⇒ 判据必须**绝对断言 + 两侧对称**两条都要过（只比相等 ⇒ 两侧都坏也算「相等」）。
if [ "${SKIP_SCENARIOS:-0}" = "1" ]; then
  say "SKIP_SCENARIOS=1 ⇒ 跳过场景轮"
else

say "── 场景轮：编译下游桩 + 场景动作探针（classpath 取自 C 侧 fat jar ⇒ 依赖版本唯一来源）"
SCEN_LIB="${TMP}/scen-lib"
SCEN_CLASSES="${TMP}/scen-classes"
mkdir -p "${SCEN_LIB}" "${SCEN_CLASSES}"
unzip -o -q -j "${C_JAR}" 'BOOT-INF/lib/*.jar' -d "${SCEN_LIB}" \
  || die "从 C 侧 fat jar 解依赖失败"
javac -nowarn -encoding UTF-8 -proc:none -cp "${SCEN_LIB}/*" -d "${SCEN_CLASSES}" \
  "${HERE}/stub/DownstreamStub.java" "${HERE}/scenarios/FavoriteScenarioProbe.java" \
  > "${TMP}/scen-javac.log" 2>&1 \
  || { bad "场景轮代码编译失败"; cat "${TMP}/scen-javac.log"; exit 2; }
ok "场景轮代码编译完成"

# ── 12.1 起下游桩（user-rpc + article-rpc **同进程** ⇒ 两侧同址 ⇒ 同形路径）──
STUB_PORT="$(free_port ${USED_PORTS[@]+"${USED_PORTS[@]}"})"; USED_PORTS+=("${STUB_PORT}")
STUB_MODE_FILE="${TMP}/stub.mode"
echo "mode=normal" > "${STUB_MODE_FILE}"
STUB_PORT="${STUB_PORT}" STUB_MODE_FILE="${STUB_MODE_FILE}" \
  java -cp "${SCEN_CLASSES}:${SCEN_LIB}/*" stub.DownstreamStub > "${TMP}/stub.log" 2>&1 &
STUB_PID=$!
PIDS+=("${STUB_PID}")
wait_port "${STUB_PORT}" "下游桩" 60 || { tail -30 "${TMP}/stub.log"; exit 2; }
ok "下游桩已就绪（:${STUB_PORT} · user-rpc + article-rpc）"

# ── 12.2 换下游地址重启两侧（矩阵要「缺席」· 场景轮要「在场」）─────────────
say "重启两侧（user-rpc / article-rpc 改指向下游桩）"
stop_sides
start_sides "static://127.0.0.1:${STUB_PORT}" "static://127.0.0.1:${STUB_PORT}"

# ── 12.3 重灌种子（HTTP 矩阵的写用例改过库 ⇒ 场景轮要 pristine 行）────────
say "重灌种子（场景轮要 pristine 行）"
for db in first_db fav_c_db; do
  docker exec -i "${PG_CONTAINER}" psql -U postgres -h 127.0.0.1 -d "${db}" -v ON_ERROR_STOP=1 \
    -c "DELETE FROM favorite_fact_outbox; DELETE FROM favorite_item; DELETE FROM favorite_folder;" \
    > "${TMP}/sc-reseed-${db}.log" 2>&1 \
    || { bad "清库 ${db} 失败"; cat "${TMP}/sc-reseed-${db}.log"; exit 2; }
  docker exec -i "${PG_CONTAINER}" psql -U postgres -h 127.0.0.1 -d "${db}" -v ON_ERROR_STOP=1 \
    -f - < "${HERE}/seed.sql" >> "${TMP}/sc-reseed-${db}.log" 2>&1 \
    || { bad "重灌种子 ${db} 失败"; cat "${TMP}/sc-reseed-${db}.log"; exit 2; }
done
ok "两侧种子已重灌"

# ── 12.3.1 变异钩子（**只动 C 侧** ⇒ 证明判据能抓住「只有 C 侧坏了」）──────
# 🔴 为什么要有这个钩子：场景轮的判据若只能抓住「两侧都坏」，那它证明不了什么
#    ⇒ ⇒ 变异必须**只**改一侧 ⇒ ⇒ 才能证明「它真的在比两侧」。
if [ -n "${SCENARIO_MUTATION:-}" ]; then
  say "⚠️ 施加场景轮变异（**仅 C 侧**）：${SCENARIO_MUTATION}"
  docker exec -i "${PG_CONTAINER}" psql -U postgres -h 127.0.0.1 -d fav_c_db -v ON_ERROR_STOP=1 \
    -c "${SCENARIO_MUTATION}" > "${TMP}/sc-mutation.log" 2>&1 \
    || { bad "变异 SQL 执行失败"; cat "${TMP}/sc-mutation.log"; exit 2; }
  ok "变异已施加（仅 C 侧）"
fi

# ── 12.4 跑场景轮 ────────────────────────────────────────────────────────
say "跑场景轮（create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障 / 重复请求）"
python3 "${HERE}/scenarios/scenarios.py" \
  --b-http "http://127.0.0.1:${B_API_PORT}/favorite/v1" \
  --c-http "http://127.0.0.1:${C_HTTP_PORT}/favorite/v1" \
  --token "${VALID_USER1}" \
  --probe-cp "${SCEN_CLASSES}:${SCEN_LIB}/*" \
  --tmp "${TMP}" \
  --pg-container "${PG_CONTAINER}" \
  --b-db first_db --c-db fav_c_db \
  --redis-container "${REDIS_CONTAINER}" \
  --stub-mode-file "${STUB_MODE_FILE}"
RC=$?
# 🔴 退出码要**原样传出去**：2 = 环境/判据不完整（不是「有差异」）⇒ 别混为一谈
if [ "${RC}" -ne 0 ]; then
  exit "${RC}"
fi
ok "场景轮通过"

fi   # ← SKIP_SCENARIOS 分支

echo
if [ "${ONLY_SCENARIOS:-0}" = "1" ]; then
  ok "favorite 域场景轮通过（ONLY_SCENARIOS=1 ⇒ 只跑了场景轮）"
elif [ "${SKIP_HTTP:-0}" = "1" ] && [ "${SKIP_SCENARIOS:-0}" = "1" ]; then
  ok "favorite 域 gRPC 面双侧活体对拍通过（SKIP_HTTP=1 + SKIP_SCENARIOS=1 ⇒ 只跑了 gRPC 面）"
elif [ "${SKIP_HTTP:-0}" = "1" ]; then
  ok "favorite 域 gRPC 面 + 场景轮通过（SKIP_HTTP=1 ⇒ HTTP 面未跑）"
elif [ "${SKIP_SCENARIOS:-0}" = "1" ]; then
  ok "favorite 域双侧活体对拍通过（HTTP 面 + gRPC 面 · SKIP_SCENARIOS=1 ⇒ 场景轮未跑）"
else
  ok "favorite 域双侧活体对拍通过（HTTP 面 + gRPC 面 + 场景轮）"
fi
echo "   证据边界见 tools/difftest/favorite/README.md"
exit 0
