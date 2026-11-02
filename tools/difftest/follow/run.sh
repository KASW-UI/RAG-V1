#!/usr/bin/env bash
#
# follow 域 **E-4 双侧活体对拍**探针 —— **B 侧（RAG / Boot 4）↔ C 侧（RAG-V1 / Boot 3.5）**
#
# 被测两侧（**同时起、同时活、同请求、同字节**）：
#   · B 侧 = /Users/pr/Documents/java&golang/RAG/service/follow/{follow-api,follow-rpc}   （Boot 4.0.8）
#   · C 侧 = 本仓 service/follow/follow-service                                            （Boot 3.5.11）
#
# 第三方锚 = expected.txt —— **Go 真源冻结字节**（B 侧自己 2026-10-29 录的，20 条用例）
#   ⇒ 判定条件是 **C == B == 冻结的 Go 字节** 三方相等（期望值本身参与判定，不是摆设）。
#
# ⚠️ **为什么本探针不跑 Go 侧**（与 B 侧探针的唯一结构性差异）：
#   B 侧的 `tools/api-full-parity-probe/follow/run.sh` 起的是 **Go 真源**（走 etcd）。
#   本机 **没有 Sea-etcd 容器**（Nacos/PG/Redis/Jaeger/Kafka 在跑，etcd 不在）
#   ⇒ Go 侧**无法活体** ⇒ 改用 B 侧已冻结的 `expected.txt`（Go 字节）作第三方锚。
#   ⇒ **三方等价的强度不降**：C == B == Go 字节，比 B 侧原探针的 Go == B == Go 字节等价。
#
# ⚠️ **B 侧只读**：本探针只在 B 仓跑 `mvn package`（产物落 `target/`，已被 .gitignore 排除）
#   ⇒ **不碰 B 侧任何源码 / 配置**（第 3 步会断言 B 仓 `git status` 干净）。
#
# 用法：
#   bash tools/difftest/follow/run.sh
#   B_REPO=/path/to/RAG bash tools/difftest/follow/run.sh
#   KEEP_WORK=1 bash tools/difftest/follow/run.sh     # 保留临时目录与日志
#
# 退出码：0 = 三方逐字节一致 · 1 = 有差异 · 2 = 环境问题 / 依赖缺失 / 真源漂移。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"

# ── 两侧仓路径 ─────────────────────────────────────────────────────────────────
B_REPO="${B_REPO:-/Users/pr/Documents/java&golang/RAG}"

# ── 宿主中间件端口（env 可覆盖；默认值 = 本机 Sea 栈实测的**发布口**）──────────
# ⚠️ **只能走发布口**：Docker Desktop(macOS) 的宿主**不可达容器 IP**（实测 172.19.0.x 全部超时）
#    ⇒ Nacos 里 `user-rpc` 注册的容器 IP 对本探针不可用 ⇒ 探针自己注册 **127.0.0.1** 形态的实例。
NACOS_PORT="${NACOS_PORT:-49848}"    # Sea-nacos   49848→8848
REDIS_PORT="${REDIS_PORT:-49380}"    # Sea-redis   49380→6379
JAEGER_PORT="${JAEGER_PORT:-49317}"  # Sea-jaeger  49317→4317 (OTLP gRPC)

# ── 端口（**本探针专用段 18201-18209**；B 侧探针占 18101-18110，Sea 栈占 49xxx/55xxx）──
B_API_PORT="${B_API_PORT:-18201}"          # B 侧 follow-api（HTTP）
B_RPC_PORT="${B_RPC_PORT:-18202}"          # B 侧 follow-rpc（gRPC）
USER_RPC_PORT="${USER_RPC_PORT:-18203}"    # B 侧 user-rpc（gRPC）—— **两侧共用的下游**
C_HTTP_PORT="${C_HTTP_PORT:-18204}"        # C 侧 follow-service（HTTP · D1 合并进程）
C_GRPC_PORT="${C_GRPC_PORT:-18205}"        # C 侧 follow-service（gRPC）
B_PG_PORT="${B_PG_PORT:-18206}"            # B 侧 Postgres（user-rpc + follow-rpc 共用）
C_PG_PORT="${C_PG_PORT:-18207}"            # C 侧 Postgres（follow-service 的 Flyway 建表）
B_RPC_MGMT_PORT="${B_RPC_MGMT_PORT:-18208}"      # B 侧 follow-rpc 的 Actuator 载体（默认 16065 ⇒ 必须覆盖）
USER_RPC_MGMT_PORT="${USER_RPC_MGMT_PORT:-18209}" # B 侧 user-rpc 的 Actuator 载体（默认 9094 ⇒ **与 Sea-user-rpc 冲突**）

# ── Nacos 分组隔离（🔴 关键，见 README「为什么必须分组」）──────────────────────
B_GROUP="${B_GROUP:-E4B}"   # B 侧三进程（follow-api / follow-rpc / user-rpc）
C_GROUP="${C_GROUP:-E4C}"   # C 侧 follow-service

# ── 两侧各自的 Postgres（容器名带 $$ ⇒ 别的 lane 的 cleanup 绝不可能删掉本轮的库）──
B_PG_CONTAINER="${B_PG_CONTAINER:-e4follow-b-pg-$$}"
C_PG_CONTAINER="${C_PG_CONTAINER:-e4follow-c-pg-$$}"

# ── 验签密钥（**探针本地值**，两侧同一把 ⇒ 一个 token 两边都验得过）────────────
# ⚠️ **刻意不用任何仓里的历史明文密钥**：探针只需要「两侧验签口径一致」。
#    ≥32 字节 ⇒ 两侧的 `padToHmacKeySize` 都是 no-op ⇒ 与 Go 的裸 secret 同形。
JWT_SECRET="${JWT_SECRET:-e4-follow-parity-probe-secret-0001}"
JWT_USER_ID="${JWT_USER_ID:-1001}"

WORK="$(mktemp -d)"
PIDS=()
C_API_PID=""          # 测试钩子 PROBE_STOP_C_AFTER 用（见下）
KEEPALIVE_PID=""
say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
dump_logs() {
  local f
  for f in b-user-rpc.log b-rpc.log b-api.log c-service.log; do
    [ -f "${WORK:-/nonexistent}/${f}" ] || continue
    printf '\n---- %s（末 40 行）----\n' "${f}" >&2
    tail -40 "${WORK}/${f}" | sed 's/^/   | /' >&2
  done
}
die()  { printf '!! %s\n' "$*" >&2; dump_logs; exit 2; }

host_port_open() { nc -z -G 2 127.0.0.1 "$1" >/dev/null 2>&1; }

# ══════════════════════════ Nacos 实例管理（**分组感知**）══════════════════════
# ⚠️ **必须带 groupName**：B 侧与 C 侧都注册 `follow-rpc` 这个服务名
#    ⇒ 同组 ⇒ C 侧的实例会进 B 侧 follow-api 的解析池 ⇒ 请求随机打到对面 ⇒ 假漂移。
nacos_list_instances() {   # $1 = 服务名  $2 = 组
  curl -s -m 10 "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance/list?serviceName=$1&groupName=$2&namespaceId=public" 2>/dev/null \
  | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for h in (d.get("hosts") or []):
    ip, port = h.get("ip"), h.get("port")
    if ip and port:
        print("%s %s" % (ip, port))
'
}
# ⚠️ **ephemeral=true + 保活循环**（不是 persistent）：
#    ① persistent（ephemeral=false）走 Nacos 的 **Raft** 持久化组 ⇒ 本机 Nacos 无 leader ⇒ 注册不进去
#    ② ephemeral 实例靠**客户端心跳**保活；这里是 curl 手工注册 ⇒ 没人发心跳
#    ⇒ 约 15s 后被 Nacos 摘掉 ⇒ 表现为「服务名在、hosts 为空」⇒ 下游 `No servers found`
nacos_register() {   # $1=服务名 $2=组 $3=ip $4=端口
  curl -s -m 10 -X POST "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance" \
    --data-urlencode "serviceName=$1" \
    --data-urlencode "groupName=$2" \
    --data-urlencode "namespaceId=public" \
    --data-urlencode "ip=$3" \
    --data-urlencode "port=$4" \
    --data-urlencode "ephemeral=true" >/dev/null 2>&1
}
nacos_keepalive() {  # $1=服务名 $2=组 $3=ip $4=端口
  while true; do
    nacos_register "$1" "$2" "$3" "$4"
    sleep 5
  done
}
# ⚠️ **注册完必须断言实例真的在列表里**（hosts 非空且端口匹配）——
#    「服务名存在」≠「实例存在」（临时实例被摘掉时就是这个样子）。
nacos_assert_instance() {  # $1=服务名 $2=组 $3=端口
  local i
  for i in $(seq 1 30); do
    nacos_list_instances "$1" "$2" | grep -q " ${3}$" && return 0
    sleep 1
  done
  return 1
}
nacos_deregister() {  # $1=服务名 $2=组 $3=ip $4=端口
  curl -s -m 5 -X DELETE "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance?serviceName=$1&groupName=$2&namespaceId=public&ip=$3&port=$4" >/dev/null 2>&1
}
# ⚠️ **只删「端口已经没人监听」的死实例** —— 别的 lane 若有活着的同名实例，
#    删掉它会让对端的 client 随机选中死实例 ⇒ 探针结果不确定。
nacos_deregister_dead() {  # $1=服务名 $2=组
  local svc="$1" grp="$2" ip port
  while read -r ip port; do
    [ -n "${ip}" ] && [ -n "${port}" ] || continue
    if ! host_port_open "${port}"; then
      nacos_deregister "${svc}" "${grp}" "${ip}" "${port}"
      say "   · 已注销 Nacos 死实例 ${svc}[${grp}] ${ip}:${port}"
    fi
  done <<EOF
$(nacos_list_instances "$1" "$2")
EOF
}

# ══════════════════════════ 进程与容器管理 ════════════════════════════════════
free_probe_ports() {
  local p pid
  for p in "${B_API_PORT}" "${B_RPC_PORT}" "${USER_RPC_PORT}" "${C_HTTP_PORT}" \
           "${C_GRPC_PORT}" "${B_RPC_MGMT_PORT}" "${USER_RPC_MGMT_PORT}"; do
    for pid in $(lsof -ti tcp:"${p}" 2>/dev/null); do
      kill -9 "${pid}" 2>/dev/null
      say "   · 踢掉占用 :${p} 的残留进程 ${pid}"
    done
  done
  sleep 1
}

stop_stacks() {   # 真正把两侧服务停掉（**幂等** ⇒ 可被 cleanup 与收尾复核各调一次）
  local p
  [ -n "${KEEPALIVE_PID:-}" ] && kill "${KEEPALIVE_PID}" 2>/dev/null
  for p in ${PIDS[@]+"${PIDS[@]}"}; do kill "$p" 2>/dev/null; done
  sleep 1
  for p in ${PIDS[@]+"${PIDS[@]}"}; do kill -9 "$p" 2>/dev/null; done
  sleep 1
}

cleanup() {
  stop_stacks
  # 只注销**本探针自己注册**的实例（ip+port 精确匹配）
  nacos_deregister user-rpc "${C_GROUP}" 127.0.0.1 "${USER_RPC_PORT}"
  docker rm -f "${B_PG_CONTAINER}" >/dev/null 2>&1
  docker rm -f "${C_PG_CONTAINER}" >/dev/null 2>&1
  # ⚠️ **不删临时目录**：`rm -rf` 在本机会被回收站守卫拦下 ⇒ 留着由系统清理。
  if [ "${KEEP_WORK:-0}" = "1" ]; then
    say "   · KEEP_WORK=1 ⇒ 保留 ${WORK}"
  fi
}
trap cleanup EXIT

# ══════════════════════════ 0. 前置检查（缺 ⇒ exit 2，绝不静默降级）═════════════
step "0/9 前置检查"
for cmd in docker mvn java curl xxd python3 lsof nc; do
  command -v "${cmd}" >/dev/null 2>&1 || die "缺命令：${cmd}"
done
[ -d "${B_REPO}/service/follow" ] || die "找不到 B 侧仓：${B_REPO}/service/follow（用 B_REPO=... 指定）"
[ -d "${B_REPO}/service/users/user/user-rpc" ] || die "找不到 B 侧 user-rpc：${B_REPO}/service/users/user/user-rpc"
[ -d "${C_ROOT}/service/follow" ] || die "找不到 C 侧 follow 模块：${C_ROOT}/service/follow"
for pair in "Sea-redis:${REDIS_PORT}" "Sea-nacos:${NACOS_PORT}" "Sea-jaeger:${JAEGER_PORT}"; do
  c="${pair%%:*}"; p="${pair##*:}"
  docker ps --format '{{.Names}}' | grep -qx "${c}" || die "容器 ${c} 没在跑（宿主中间件缺失）"
  nc -z -G 2 127.0.0.1 "${p}" || die "宿主端口 ${p} 不通（${c} 的映射口）"
done
say "   ✓ 命令齐 / 中间件在跑 / 两侧仓都在"

# ══════════════════════════ 1. 两侧各自的 Postgres ════════════════════════════
step "1/9 两侧 Postgres"
# ⚠️ **必须先清掉「上一轮遗留的同前缀容器」**：容器名带 `$$` ⇒ 每轮唯一
#    ⇒ 上一轮的容器**不会被本轮的 cleanup 删掉**（上一轮若被强杀 ⇒ cleanup 没跑）
#    ⇒ ⚠️ **Exited 的容器仍然占着发布端口**（docker-proxy 还在）⇒ `docker run -p` 报
#    `port is already allocated` ⇒ **与代码无关的假故障**。
purge_stale_my_pg() {
  local c
  for c in $(docker ps -aq --filter "name=e4follow-" 2>/dev/null); do
    docker rm -f "${c}" >/dev/null 2>&1
    say "   · 已清理上一轮遗留的探针容器 ${c}"
  done
  sleep 3   # docker-proxy 释放监听口要一会儿
}
purge_stale_my_pg
# ⚠️ 就绪判据必须是「**宿主端口**通」：`docker exec psql` 走的是**容器内 socket**，
#    与 `-p` 发布**无关** ⇒ 容器活着 ≠ 宿主端口通。
start_pg() {   # $1 = 容器名  $2 = 宿主端口
  local c="$1" p="$2"
  docker rm -f "${c}" >/dev/null 2>&1
  docker run -d --name "${c}" -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=sea \
    -e POSTGRES_DB=first_db -p "${p}:5432" postgres:16-alpine >/dev/null 2>&1 || return 1
  for _ in $(seq 1 40); do
    docker exec "${c}" pg_isready -U admin -d first_db >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec "${c}" pg_isready -U admin -d first_db >/dev/null 2>&1 || return 1
  for _ in $(seq 1 15); do
    host_port_open "${p}" && return 0
    sleep 1
  done
  return 1
}
# ⚠️ **诊断信息必须走 stderr**：本函数的值是**命令替换**取回的
#    ⇒ `say`（stdout）会把端口号污染成「诊断文本 + 端口号」⇒ 后续 sed 替换串里带**换行**。
B_PG_CANDIDATES=(${B_PG_PORT:+${B_PG_PORT}} 18216 18226)
C_PG_CANDIDATES=(${C_PG_PORT:+${C_PG_PORT}} 18217 18227)
pick_pg_port() {   # $1 = 容器名  $2.. = 候选端口 ⇒ 成功时把端口打到 stdout
  local c="$1"; shift
  local p
  for p in "$@"; do
    if start_pg "${c}" "${p}"; then printf '%s' "${p}"; return 0; fi
    printf '   · :%s 起不来（容器内没就绪 / 宿主端口没通 / 端口被占）⇒ 换下一个\n' "${p}" >&2
    docker rm -f "${c}" >/dev/null 2>&1
  done
  return 1
}
B_PG_PORT="$(pick_pg_port "${B_PG_CONTAINER}" ${B_PG_CANDIDATES[@]+${B_PG_CANDIDATES[@]}})" \
  || die "B 侧 PG 起不来（候选端口：${B_PG_CANDIDATES[*]}）"
C_PG_PORT="$(pick_pg_port "${C_PG_CONTAINER}" ${C_PG_CANDIDATES[@]+${C_PG_CANDIDATES[@]}})" \
  || die "C 侧 PG 起不来（候选端口：${C_PG_CANDIDATES[*]}）"
say "   ✓ 两侧 Postgres 就绪（B :${B_PG_PORT} · C :${C_PG_PORT}，**宿主端口已验证可连**）"

# ══════════════════════════ 2. 构建两侧 ═══════════════════════════════════════
step "2/9 构建两侧（离线）"
( cd "${B_REPO}" && mvn -o -q -pl service/follow/follow-api,service/follow/follow-rpc,service/users/user/user-rpc -am package -DskipTests ) \
  || die "B 侧构建失败"
B_API_JAR="${B_REPO}/service/follow/follow-api/target/follow-api-0.0.1-SNAPSHOT.jar"
B_RPC_JAR="${B_REPO}/service/follow/follow-rpc/target/follow-rpc-0.0.1-SNAPSHOT.jar"
USER_RPC_JAR="${B_REPO}/service/users/user/user-rpc/target/user-rpc-0.0.1-SNAPSHOT.jar"
[ -f "${B_API_JAR}" ] && [ -f "${B_RPC_JAR}" ] && [ -f "${USER_RPC_JAR}" ] || die "B 侧 jar 没产出"
( cd "${C_ROOT}" && mvn -o -q -pl service/follow -am package -DskipTests ) || die "C 侧构建失败"
C_JAR="${C_ROOT}/service/follow/target/follow-service-1.0-SNAPSHOT.jar"
[ -f "${C_JAR}" ] || die "C 侧 jar 没产出：${C_JAR}"
# ⚠️ **顺带取 C 侧的运行时 classpath**：gRPC wire 探针要用它编译/运行
#    （wire 探针**只用 grpc 的原始字节 marshaller** ⇒ 不依赖任何 pb 生成码
#      ⇒ **同一个 jar 能跑两侧** ⇒ 比的是纯 wire 字节，不被「两侧 pb 包名不同」污染）。
( cd "${C_ROOT}" && mvn -o -q -pl service/follow dependency:build-classpath \
    -Dmdep.outputFile="${WORK}/follow-cp.txt" ) || die "取 C 侧 classpath 失败"
[ -s "${WORK}/follow-cp.txt" ] || die "C 侧 classpath 文件为空：${WORK}/follow-cp.txt"
# ⚠️ **B 侧只读断言**：本探针只在 B 仓产出 target/（.gitignore 排除）⇒ 源码必须一个字节没动。
( cd "${B_REPO}" && git status --porcelain -- service/follow service/users service/common | grep -q . ) \
  && die "B 仓 service/follow|service/users|service/common 有改动 ⇒ 本探针**不得**改真源"
say "   ✓ 两侧构建完成（B 三 jar + C 一 jar；**B 仓源码仍然干净**）"

# ══════════════════════════ 3. JWT（自签；两侧同一把 secret 验签）════════════════
step "3/9 签发 JWT（claim userId=${JWT_USER_ID}，两侧同一把 secret）"
JWT_TOKEN="$(python3 "${HERE}/mint-jwt.py" "${JWT_SECRET}" "${JWT_USER_ID}")"
[ -n "${JWT_TOKEN}" ] || die "JWT 签发失败"
say "   ✓ token 已签发（${#JWT_TOKEN} 字节）"

# ══════════════════════════ 4. 起 B 侧 user-rpc（下游，两侧共用同一个）══════════
step "4/9 起 B 侧 user-rpc（gRPC :${USER_RPC_PORT}；自注册 user-rpc[${B_GROUP}]）"
# ⚠️ **两侧必须调用同一个 user-rpc 进程** ⇒ ① B 侧 follow-rpc 走 Nacos 解析到它
#    ② C 侧 follow-service 也走 Nacos 解析到它（同一个注册地址，不同组）
#    ⇒ 任何「用户不存在 / 下游故障」的分歧都不可能来自下游不同。
#
# ⚠️ **抽成函数**（而不是内联）：场景轮要能**杀掉再重启**它（外部依赖失败 + 恢复）
#    ⇒ 启动逻辑必须可复用 ⇒ 且 **不能**用命令替换（子 shell 里 `PIDS+=` 不会回传）
#    ⇒ 用**全局变量** `USER_RPC_PID` 回传 PID。
start_user_rpc() {
  ( cd "${WORK}" && exec env \
    POSTGRES_ADDR=127.0.0.1 POSTGRES_PORT="${B_PG_PORT}" POSTGRES_DB=first_db \
    POSTGRES_USER=admin POSTGRES_PASSWORD=sea \
    REDIS_ADDR=127.0.0.1 REDIS_PORT="${REDIS_PORT}" REDIS_PASSWORD= \
    NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${B_GROUP}" \
    USER_RPC_PORT="${USER_RPC_PORT}" USER_RPC_MANAGEMENT_PORT="${USER_RPC_MGMT_PORT}" \
    USER_JWT_SECRET="${JWT_SECRET}" USER_JWT_EXPIRE=31536000 SNOWFLAKE_NODE_ID=0 \
    OTEL_ADDR=127.0.0.1 OTLP_PORT="${JAEGER_PORT}" \
    java -jar "${USER_RPC_JAR}" >>"${WORK}/b-user-rpc.log" 2>&1 ) &
  USER_RPC_PID=$!
  PIDS+=("${USER_RPC_PID}")
  for _ in $(seq 1 60); do host_port_open "${USER_RPC_PORT}" && break; sleep 1; done
  host_port_open "${USER_RPC_PORT}" || return 1
  return 0
}
USER_RPC_PID=""
start_user_rpc || die "B 侧 user-rpc 没监听 :${USER_RPC_PORT}（见 ${WORK}/b-user-rpc.log）"
nacos_assert_instance user-rpc "${B_GROUP}" "${USER_RPC_PORT}" \
  || die "user-rpc 没出现在 Nacos[${B_GROUP}] 实例列表里（服务名在、hosts 为空 ⇒ 临时实例被摘掉？）"
say "   ✓ B 侧 user-rpc 已监听 :${USER_RPC_PORT} 且已自注册进 Nacos[${B_GROUP}]（pid=${USER_RPC_PID}）"
# ⚠️ **C 侧要用同一个进程，但 C 侧在 C_GROUP** ⇒ 手工把 **127.0.0.1** 形态的实例
#    注册进 C_GROUP（宿主可达；容器 IP 在 macOS 上不可达 ⇒ 不能直接复用 B 侧那条）。
nacos_register user-rpc "${C_GROUP}" 127.0.0.1 "${USER_RPC_PORT}"
nacos_assert_instance user-rpc "${C_GROUP}" "${USER_RPC_PORT}" \
  || die "user-rpc 没出现在 Nacos[${C_GROUP}] 实例列表里"
nacos_keepalive user-rpc "${C_GROUP}" 127.0.0.1 "${USER_RPC_PORT}" &
KEEPALIVE_PID=$!
say "   ✓ 同一 user-rpc 已登记进 Nacos[${C_GROUP}]（含保活循环）⇒ 两侧下游完全同源"

# ══════════════════════════ 5. 起 B 侧 follow-rpc + follow-api ══════════════════
step "5/9 起 B 侧 follow-rpc（gRPC :${B_RPC_PORT}）与 follow-api（HTTP :${B_API_PORT}）"
( cd "${WORK}" && exec env \
  POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${B_PG_PORT}" POSTGRES_DB=first_db \
  POSTGRES_USER=admin POSTGRES_PASSWORD=sea \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${B_GROUP}" \
  FOLLOW_RPC_PORT="${B_RPC_PORT}" FOLLOW_RPC_MANAGEMENT_PORT="${B_RPC_MGMT_PORT}" \
  OTEL_ADDR=127.0.0.1 OTLP_PORT="${JAEGER_PORT}" \
  java -jar "${B_RPC_JAR}" >"${WORK}/b-rpc.log" 2>&1 ) &
PIDS+=($!)
for _ in $(seq 1 60); do host_port_open "${B_RPC_PORT}" && break; sleep 1; done
host_port_open "${B_RPC_PORT}" || die "B 侧 follow-rpc 没监听 :${B_RPC_PORT}（见 ${WORK}/b-rpc.log）"
nacos_assert_instance follow-rpc "${B_GROUP}" "${B_RPC_PORT}" \
  || die "B 侧 follow-rpc 没出现在 Nacos[${B_GROUP}] 实例列表里"
say "   ✓ B 侧 follow-rpc 已监听 :${B_RPC_PORT} 且已注册进 Nacos[${B_GROUP}]"
# ⚠️ B 侧 follow-api 的 `grpc.client.follow-rpc.immediate-connect-timeout: 3s`
#    （= Go `zrpc.MustNewClient` 的 3s 阻塞拨号）⇒ 下游没完全就绪就**起不来**。
#    ⇒ 探针专用覆盖成 60s（**只放宽启动期**，请求路径一字未改）+ 启动重试 3 次。
sleep 5
B_API_STARTED=0
for attempt in 1 2 3; do
  ( cd "${WORK}" && exec env \
    REDIS_HOST=127.0.0.1 REDIS_PORT="${REDIS_PORT}" REDIS_PASSWORD= \
    NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${B_GROUP}" \
    FOLLOW_API_PORT="${B_API_PORT}" USER_JWT_SECRET="${JWT_SECRET}" USER_JWT_EXPIRE=31536000 \
    OTEL_ADDR=127.0.0.1 OTLP_PORT="${JAEGER_PORT}" \
    java -jar "${B_API_JAR}" \
      --grpc.client.follow-rpc.immediate-connect-timeout=60s \
      >"${WORK}/b-api.log" 2>&1 ) &
  api_pid=$!
  PIDS+=($!)
  for _ in $(seq 1 60); do
    host_port_open "${B_API_PORT}" && { B_API_STARTED=1; break; }
    kill -0 "${api_pid}" 2>/dev/null || break
    sleep 1
  done
  if [ "${B_API_STARTED}" = "1" ]; then
    say "   ✓ B 侧 follow-api 第 ${attempt} 次启动成功（:${B_API_PORT}）"
    break
  fi
  say "   · B 侧 follow-api 第 ${attempt} 次启动没起来（见 ${WORK}/b-api.log）⇒ 重试"
  kill -9 "${api_pid}" 2>/dev/null
  sleep 3
done
[ "${B_API_STARTED}" = "1" ] || die "B 侧 follow-api 三次都没起来（见 ${WORK}/b-api.log）"

# ══════════════════════════ 6. 起 C 侧 follow-service ═══════════════════════════
step "6/9 起 C 侧 follow-service（HTTP :${C_HTTP_PORT} · gRPC :${C_GRPC_PORT}）"
# ⚠️ **C 侧是 D1 合并后的单进程**（api + rpc 同进程）⇒ 一个 jar 同时起 HTTP 与 gRPC。
C_STARTED=0
for attempt in 1 2 3; do
  ( cd "${WORK}" && exec env \
    POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${C_PG_PORT}" POSTGRES_DB=first_db \
    POSTGRES_USER=admin POSTGRES_PASSWORD=sea \
    REDIS_HOST=127.0.0.1 REDIS_PORT="${REDIS_PORT}" REDIS_PASSWORD= \
    NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${C_GROUP}" \
    FOLLOW_HTTP_PORT="${C_HTTP_PORT}" FOLLOW_GRPC_PORT="${C_GRPC_PORT}" \
    USER_JWT_SECRET="${JWT_SECRET}" USER_JWT_EXPIRE=31536000 \
    OTEL_ADDR=127.0.0.1 OTLP_PORT="${JAEGER_PORT}" \
    java -jar "${C_JAR}" >"${WORK}/c-service.log" 2>&1 ) &
  c_pid=$!
  C_API_PID="${c_pid}"
  PIDS+=($!)
  for _ in $(seq 1 60); do
    host_port_open "${C_HTTP_PORT}" && { C_STARTED=1; break; }
    kill -0 "${c_pid}" 2>/dev/null || break
    sleep 1
  done
  if [ "${C_STARTED}" = "1" ]; then
    say "   ✓ C 侧 follow-service 第 ${attempt} 次启动成功（:${C_HTTP_PORT}）"
    break
  fi
  say "   · C 侧 follow-service 第 ${attempt} 次启动没起来（见 ${WORK}/c-service.log）⇒ 重试"
  kill -9 "${c_pid}" 2>/dev/null
  sleep 3
done
[ "${C_STARTED}" = "1" ] || die "C 侧 follow-service 三次都没起来（见 ${WORK}/c-service.log）"

# ══════════════════════════ 7. 两侧灌同一行 ════════════════════════════════════
step "7/9 两侧灌同一行（follow_relation / block_relation）+ user-rpc 的 users_user"
# ⚠️ **两侧都必须有数据**：「两侧都空」分不出「相同」与「都空」⇒ 播种同一批行。
# ⚠️ create_time 全部**互不相同** ⇒ `ORDER BY create_time desc` 的顺序确定
#    （同值时 PG 的顺序不保证 ⇒ 输出顺序会随机 ⇒ 探针会**假红**）。
SEED_FOLLOW="INSERT INTO follow_relation (user_id,target_id,create_time,update_time) VALUES
 (1001,1002,'2026-01-01 00:00:01+00','2026-01-01 00:00:01+00'),
 (1001,1003,'2026-01-01 00:00:02+00','2026-01-01 00:00:02+00'),
 (1002,1001,'2026-01-01 00:00:03+00','2026-01-01 00:00:03+00'),
 (1002,1004,'2026-01-01 00:00:04+00','2026-01-01 00:00:04+00');"
SEED_BLOCK="INSERT INTO block_relation (user_id,target_id,create_time,update_time) VALUES
 (1001,1005,'2026-01-01 00:00:05+00','2026-01-01 00:00:05+00');"
for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  docker exec "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "${SEED_FOLLOW}" >/dev/null || die "灌 follow_relation 失败：${c}"
  docker exec "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "${SEED_BLOCK}"  >/dev/null || die "灌 block_relation 失败：${c}"
done
# ⚠️ **B 侧 Java 的 users 表叫 `users_user`**（Go 侧叫 `users`）⇒
#    表名 + 列名都按 B 侧 `users-common/db/01_create_user.sql` 写。
SEED_USERS="INSERT INTO users_user (uid,username,password,email,status,score,create_time,update_time) VALUES
 (1001,'probe_u1001','x','probe1001@example.invalid',0,0,now(),now()),
 (1002,'probe_u1002','x','probe1002@example.invalid',0,0,now(),now()),
 (1003,'probe_u1003','x','probe1003@example.invalid',0,0,now(),now()),
 (1004,'probe_u1004','x','probe1004@example.invalid',0,0,now(),now()),
 (1005,'probe_u1005','x','probe1005@example.invalid',0,0,now(),now());"
docker exec "${B_PG_CONTAINER}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "${SEED_USERS}" >/dev/null \
  || die "灌 users_user 失败：${B_PG_CONTAINER}"
say "   ✓ 两侧同一批行（follow 4 行 / block 1 行）+ users_user 5 行（user-rpc 侧）"

# ══════════════════════════ 8. 请求矩阵：C vs B vs expected.txt ══════════════════
step "8/9 请求矩阵（C vs B vs 冻结的 Go 字节 ⇒ 逐字节）"
EXPECTED="${HERE}/expected.txt"
[ -f "${EXPECTED}" ] || die "缺少冻结的期望值：${EXPECTED}"

# ⚠️ **就绪闸门必须等 `code=200`**：
#    「端口已监听」≠「下游 rpc 已就绪」⇒ 首请求可能超时 ⇒ 业务码塌成 1015
#    ⇒ 那不是「漂移」，是「没准备好」。
#    ⚠️ **5001 也不算就绪**：本域的 5001 = 「下游 user-rpc 挂了/找不到」
#    ⇒ 用它当就绪判据会把「下游没起来」当成「两侧一致」。
wait_ready() {   # $1 = 端口  $2 = 标签
  local p="$1" tag="$2" body
  for _ in $(seq 1 40); do
    body=$(curl -s -m 10 -X POST "http://127.0.0.1:${p}/follow/v1/follow/list" \
             -H "Authorization: Bearer ${JWT_TOKEN}" -H 'Content-Type: application/json' \
             --data-binary '{"user_id":"1001"}' 2>/dev/null)
    case "${body}" in
      *'"code":200'*) say "   ✓ ${tag} 就绪（首个 code=200 响应已到）"; return 0 ;;
      *) sleep 2 ;;
    esac
  done
  say "   ❌ ${tag} 轮询 40 次仍拿不到 code=200 响应 ⇒ 不进请求矩阵"
  return 1
}

# ⚠️ **FAILING-010 的修法（关键）**：每个请求用**独立的 mktemp 文件**收 body。
#    旧探针用**固定 -o 文件** ⇒ 请求失败时读到的是**上一次的残留 body** ⇒ 假绿。
request_body() {   # $1=端口 $2=method $3=path $4=body $5=authmode ⇒ 打印 "<http_code>|<hex>"
  local port="$1" method="$2" path="$3" body="$4" auth="$5"
  local out code
  # ⚠️ 文件**不删**：本机的 `rm` 被回收站守卫接管且**往 stdout 打印**
  #    ⇒ 会污染本函数的返回值 ⇒ 两侧全被误判为不等。一律落在 ${WORK}（mktemp -d）里。
  out="$(mktemp "${WORK}/body.XXXXXX")" || return 1
  if [ "${auth}" = "valid" ]; then
    code=$(curl -s -o "${out}" -w '%{http_code}' -m 30 -X "${method}" \
             "http://127.0.0.1:${port}${path}" \
             -H "Authorization: Bearer ${JWT_TOKEN}" \
             -H 'Content-Type: application/json' \
             --data-binary "${body}" 2>/dev/null)
  elif [ "${auth}" = "bad" ]; then
    code=$(curl -s -o "${out}" -w '%{http_code}' -m 30 -X "${method}" \
             "http://127.0.0.1:${port}${path}" \
             -H "Authorization: Bearer not-a-jwt" \
             -H 'Content-Type: application/json' \
             --data-binary "${body}" 2>/dev/null)
  else
    code=$(curl -s -o "${out}" -w '%{http_code}' -m 30 -X "${method}" \
             "http://127.0.0.1:${port}${path}" \
             -H 'Content-Type: application/json' \
             --data-binary "${body}" 2>/dev/null)
  fi
  printf '%s|%s' "${code}" "$(xxd -p "${out}" | tr -d '\n')"
}

# ⚠️ **场景轮专用**：两侧发**同一个**请求 ⇒ 比字节 + 断言「期望的形状」
#   $1=用例名 $2=method $3=path $4=body $5=auth $6=期望（"ok" = 业务码 200 · "fail" = 业务码**非** 200）
# ⚠️ **判据为什么按「业务码」而不是「HTTP 状态码」**：本域的**业务失败恒 HTTP 200** +
#   业务信封（`{"code":5001,...}`）⇒ 故障场景的 HTTP 状态码**仍然是 200**
#   ⇒ 若按 HTTP 状态码断言「非 200」，会把「故障被正确包成业务信封」误判为失败。
compare_both() {
  local name="$1" method="$2" path="$3" body="$4" auth="$5" expect="$6"
  local c b ccode chex bcode bhex cb bb
  c="$(request_body "${C_HTTP_PORT}" "${method}" "${path}" "${body}" "${auth}")"
  b="$(request_body "${B_API_PORT}"  "${method}" "${path}" "${body}" "${auth}")"
  ccode="${c%%|*}"; chex="${c##*|}"; bcode="${b%%|*}"; bhex="${b##*|}"
  if [ "${ccode}" = "000" ] || [ "${bcode}" = "000" ]; then
    FAILED=1
    say "   ❌ ${name}：请求失败（C=${ccode} · B=${bcode}）"
    return 1
  fi
  cb="$(printf '%s' "${chex}" | xxd -r -p)"
  bb="$(printf '%s' "${bhex}" | xxd -r -p)"
  if [ "${c}" != "${b}" ]; then
    FAILED=1
    say "   ❌ ${name}：两侧字节不一致"
    say "      C : ${ccode}|${cb}"
    say "      B : ${bcode}|${bb}"
    return 1
  fi
  case "${expect}" in
    ok)
      case "${cb}" in
        *'"code":200'*) : ;;
        *) FAILED=1
           say "   ❌ ${name}：期望业务码 200，实得「${cb}」⇒ 判据空洞（没真恢复）"
           return 1 ;;
      esac ;;
    fail)
      case "${cb}" in
        *'"code":200'*) FAILED=1
           say "   ❌ ${name}：期望业务码**非 200**（故障场景），实得「${cb}」⇒ 故障没生效？"
           return 1 ;;
        *) : ;;
      esac ;;
  esac
  say "   ✅ ${name}  两侧一致（HTTP ${ccode} · ${#chex} 字节 · body=${cb}）"
  return 0
}

FAILED=0
# 🧪 测试钩子（只给 mutate-parity.sh 用，正常跑不用）：
#    PROBE_STOP_C_AFTER=<case 名> ⇒ 该用例跑完后**杀掉 C 侧进程**。
#    用途：验证「请求失败时探针必须转红，而不是读到上一次的残留 body」。
PROBE_STOP_C_AFTER="${PROBE_STOP_C_AFTER:-}"
maybe_stop_c() {   # $1 = case 名
  [ -n "${PROBE_STOP_C_AFTER}" ] || return 0
  [ "$1" = "${PROBE_STOP_C_AFTER}" ] || return 0
  [ -n "${C_API_PID}" ] || return 0
  say "   🧪 测试钩子：杀掉 C 侧进程（PROBE_STOP_C_AFTER=${PROBE_STOP_C_AFTER}）"
  kill -9 "${C_API_PID}" 2>/dev/null
}
run_case() {   # $1=name $2=method $3=path $4=body $5=authmode
  run_case_impl "$@"
  maybe_stop_c "$1"
}
run_case_impl() {   # $1=name $2=method $3=path $4=body $5=authmode
  local name="$1" method="$2" path="$3" body="$4" auth="$5"
  local c b ch bh eh
  c="$(request_body "${C_HTTP_PORT}" "${method}" "${path}" "${body}" "${auth}")"
  b="$(request_body "${B_API_PORT}"  "${method}" "${path}" "${body}" "${auth}")"
  ch="${c##*|}"; bh="${b##*|}"
  local ccode="${c%%|*}"; bcode="${b%%|*}"
  if [ "${ccode}" = "000" ] || [ "${bcode}" = "000" ]; then
    FAILED=1
    say "   ❌ ${name}：请求失败（C http_code=${ccode} · B http_code=${bcode}）"
    return
  fi
  eh=$(awk -F'|' -v n="CASE|${name}" '$1"|"$2==n {print $3"|"$4}' "${EXPECTED}")
  if [ "${c}" = "${b}" ] && [ "${c}" = "${eh}" ]; then
    say "   ✅ ${name}  C == B == expected.txt（HTTP ${ccode} · ${#ch} 字节）"
  else
    FAILED=1
    say "   ❌ ${name}"
    [ "${ccode}" != "${bcode}" ] && say "      HTTP 状态码不同：C=${ccode} · B=${bcode}"
    [ "${ch}" != "${bh}" ] && {
      say "      C != B"
      say "      C : $(printf '%s' "${ch}" | xxd -r -p)"
      say "      B : $(printf '%s' "${bh}" | xxd -r -p)"
    }
    [ "${c}" != "${eh}" ] && {
      say "      C != expected.txt（冻结的 Go 字节）"
      say "      C     : $(printf '%s' "${ch}" | xxd -r -p)"
      say "      期望值: $(printf '%s' "${eh##*|}" | xxd -r -p)"
    }
  fi
}

wait_ready "${B_API_PORT}"  "B 侧" || die "B 侧没就绪（见上面的日志）"
wait_ready "${C_HTTP_PORT}" "C 侧" || die "C 侧没就绪（见上面的日志）"

# ── 读路径（不改状态）────────────────────────────────────────────────────────
run_case "follow_list_seeded"      POST "/follow/v1/follow/list"    '{"user_id":"1001"}' valid
run_case "follower_list_seeded"    POST "/follow/v1/follower/list"  '{"user_id":"1001"}' valid
run_case "block_list_seeded"       POST "/follow/v1/block/list"     '{"user_id":"1001"}' valid
run_case "follow_list_empty"       POST "/follow/v1/follow/list"    '{"user_id":"1005"}' valid
run_case "recommendations_seeded"  POST "/follow/v1/recommendations" '{"user_id":"1001"}' valid
run_case "follow_list_limit_zero"  POST "/follow/v1/follow/list"    '{"user_id":"1001","limit":0}' valid
run_case "follow_list_limit_max"   POST "/follow/v1/follow/list"    '{"user_id":"1001","limit":1000}' valid
run_case "follow_list_offset_neg"  POST "/follow/v1/follow/list"    '{"user_id":"1001","offset":-1}' valid
run_case "block_list_wrong_owner"  POST "/follow/v1/block/list"     '{"user_id":"1002"}' valid
# ── 协议层（不改状态）────────────────────────────────────────────────────────
run_case "no_auth_header"          POST "/follow/v1/follow/list"    '{"user_id":"1001"}' none
run_case "bad_jwt"                 POST "/follow/v1/follow/list"    '{"user_id":"1001"}' bad
run_case "follow_missing_target"   POST "/follow/v1/follow"         '{}' valid
run_case "follow_bad_target_type"  POST "/follow/v1/follow"         '{"target_id":"1.5"}' valid
run_case "follow_unknown_path"     POST "/follow/v1/nope"           '{}' valid
run_case "follow_wrong_method"     GET  "/follow/v1/follow"         '' none
# ── 写路径（**顺序敏感**：放在最后，且各用不同的 target）───────────────────────
run_case "follow_self_relation"    POST "/follow/v1/follow"         '{"target_id":"1001"}' valid
run_case "follow_target_not_found" POST "/follow/v1/follow"         '{"target_id":"999999"}' valid
run_case "follow_new_relation"     POST "/follow/v1/follow"         '{"target_id":"1004"}' valid
run_case "unfollow_seeded"         POST "/follow/v1/unfollow"       '{"target_id":"1003"}' valid
run_case "unblock_seeded"          POST "/follow/v1/unblock"        '{"target_id":"1005"}' valid

# ── 增量用例：黑名单（🔴 **双边**，无 Go 锚点）────────────────────────────────
# ⚠️ **为什么单列**：冻结的 20 条**不含黑名单**（Go 真源那轮没覆盖）
#    ⇒ 本条只能 **C == B** 双边比 ⇒ **不能**声称「三方一致」。
# ⚠️ **判据不能空洞**：必须**同时**满足 ① 两侧都是 401 ② 两侧 body **非空**
#    （验签失败也是 401 但 body 为空 ⇒ 只有「非空」才能证明**命中的是黑名单**而不是验签失败）。
step "8b/9 增量用例：JWT 黑名单（双边 · C == B）"
BL_KEY="user:jwt_blacklist:${JWT_TOKEN}"
docker exec Sea-redis redis-cli SET "${BL_KEY}" "1" >/dev/null 2>&1 \
  || die "写 Redis 黑名单 key 失败（Sea-redis）"
c="$(request_body "${C_HTTP_PORT}" POST "/follow/v1/follow/list" '{"user_id":"1001"}' valid)"
b="$(request_body "${B_API_PORT}"  POST "/follow/v1/follow/list" '{"user_id":"1001"}' valid)"
cbody="${c##*|}"; bbody="${b##*|}"
ccode="${c%%|*}"; bcode="${b%%|*}"
if [ "${ccode}" != "401" ] || [ "${bcode}" != "401" ]; then
  FAILED=1
  say "   ❌ blacklist：HTTP 状态码不是 401（C=${ccode} · B=${bcode}）⇒ 黑名单没生效？"
elif [ -z "${cbody}" ] || [ -z "${bbody}" ]; then
  FAILED=1
  say "   ❌ blacklist：body 为空（C=${#cbody} 字节 · B=${#bbody} 字节）⇒ 命中的是验签失败而不是黑名单"
elif [ "${c}" != "${b}" ]; then
  FAILED=1
  say "   ❌ blacklist：C != B"
  say "      C : $(printf '%s' "${cbody}" | xxd -r -p)"
  say "      B : $(printf '%s' "${bbody}" | xxd -r -p)"
else
  say "   ✅ blacklist  C == B（HTTP 401 · ${#cbody} 字节 · body 非空 ⇒ 命中黑名单）"
  say "      body: $(printf '%s' "${cbody}" | xxd -r -p)"
fi
docker exec Sea-redis redis-cli DEL "${BL_KEY}" >/dev/null 2>&1

# ── 增量用例：gRPC wire 层原始字节（🔴 **双边 + 第三方锚**）────────────────────
# ⚠️ **为什么单独做这一层**：HTTP 层的 JSON 字节一致 **推不出** gRPC wire 字节一致
#    （proto 编码、字段号、status description 全是另一套面）
#    ⇒ §11.6 的 NOT_VERIFIED ① 就是这一层 ⇒ 本步把它补上。
# ⚠️ **探针只用手工构造的 wire 字节**（不碰任何 pb 生成码）⇒
#    **同一个 jar 跑两侧** ⇒ 比的是纯粹的 wire 字节。
step "8c/9 gRPC wire 层原始字节比对（C 侧 gRPC :${C_GRPC_PORT} ↔ B 侧 follow-rpc :${B_RPC_PORT}）"
WIRE_SRC="${HERE}/grpc-wire/FollowWireProbe.java"
[ -f "${WIRE_SRC}" ] || die "缺 wire 探针源码：${WIRE_SRC}"
WIRE_OUT="$(mktemp -d "${WORK}/wire.XXXXXX")" || die "建 wire 临时目录失败"
javac -nowarn -cp "$(cat "${WORK}/follow-cp.txt")" -d "${WIRE_OUT}" "${WIRE_SRC}" \
  || die "wire 探针编译失败"
WIRE_CP="${WIRE_OUT}:$(cat "${WORK}/follow-cp.txt")"
java -cp "${WIRE_CP}" com.rag.follow.parity.FollowWireProbe 127.0.0.1 "${B_RPC_PORT}" "${WIRE_OUT}/b.txt" \
  || die "B 侧 wire 探针执行失败"
java -cp "${WIRE_CP}" com.rag.follow.parity.FollowWireProbe 127.0.0.1 "${C_GRPC_PORT}" "${WIRE_OUT}/c.txt" \
  || die "C 侧 wire 探针执行失败"
WIRE_COUNT=0
WIRE_MISMATCH=0
while IFS= read -r bline; do
  [ -n "${bline}" ] || continue
  wname="${bline#CASE|}"; wname="${wname%%|*}"
  wbody="${bline##*|}"
  cline="$(grep -F "CASE|${wname}|" "${WIRE_OUT}/c.txt" | head -1)"
  WIRE_COUNT=$((WIRE_COUNT + 1))
  if [ -z "${cline}" ]; then
    WIRE_MISMATCH=1
    say "   ❌ ${wname}  C 侧 wire 探针**没有这一条** ⇒ 两侧方法集不同"
    continue
  fi
  if [ "${bline}" = "${cline}" ]; then
    say "   ✅ ${wname}  wire 字节一致（C == B · ${#wbody} 字节）"
  else
    WIRE_MISMATCH=1
    say "   ❌ ${wname}  wire 字节不一致"
    say "      C : ${cline}"
    say "      B : ${bline}"
  fi
done < "${WIRE_OUT}/b.txt"
# ⚠️ **零命中是假信号**：必须确认真的跑到了用例（探针空跑 ⇒ 「一致」毫无意义）
if [ "${WIRE_COUNT}" -lt 8 ]; then
  WIRE_MISMATCH=1
  say "   ❌ wire 探针只产出 ${WIRE_COUNT} 条（应 ≥ 8）⇒ 探针没真跑"
else
  say "   ✓ wire 层共比对 ${WIRE_COUNT} 条"
fi
[ "${WIRE_MISMATCH}" = "0" ] || FAILED=1

# ── 场景轮：§九 的「超时 / 外部依赖失败 / 恢复 / 并发 / 事务回滚」（**双侧**）──────
# ⚠️ **为什么放在最后**：本轮会**改库**（并发关注新增行 · block 失败注入）
#    ⇒ 必须排在 HTTP 矩阵与 wire 层**之后** ⇒ 前两轮的期望值不受影响。
step "8d/9 场景轮（双侧）：超时 / 恢复 / 外部依赖失败 / 恢复 / 并发 / 事务回滚"

pg_count() {   # $1=容器 $2=SQL ⇒ 打印计数（去空白）
  docker exec "$1" psql -U admin -d first_db -tAc "$2" 2>/dev/null | tr -d ' \n\r'
}

# ⚠️ **业务码提取**（不是 HTTP 状态码）—— 本域业务失败恒 HTTP 200 + 业务信封。
biz_code_of() {   # $1="httpcode|hex" ⇒ 打印业务码（取不到 ⇒ 空串）
  printf '%s' "${1##*|}" | xxd -r -p 2>/dev/null | grep -o '"code":[0-9]\+' | head -1 | cut -d: -f2
}

# ⚠️ **并发场景的码集合**（每行 "httpcode|hex" ⇒ 去重后的业务码集合，逗号分隔）。
codes_of() {   # $1=文件
  while IFS='|' read -r _c _hex; do
    [ -z "${_hex}" ] && continue
    printf '%s' "${_hex}" | xxd -r -p 2>/dev/null | grep -o '"code":[0-9]\+' | head -1 | cut -d: -f2
  done < "$1" | sort -u | tr '\n' ',' | sed 's/,$//'
}

# ⚠️ **恢复轮要同时看两侧**（本域的对拍判据是「两侧一致」，不是「系统恢复」）：
#   恢复失败若**两侧同样失败** ⇒ 那是**系统级现象**（服务发现条目在挂起期间过期 /
#   客户端通道退避 / 熔断冷却）⇒ 两侧对称 ⇒ **不是 follow 域的漂移**；
#   但必须**如实报出** ⇒ 绝不能让「两边都坏」变成一条无声的假绿。
#   ⇒ 只轮询 C 侧会漏掉「B 侧也没恢复」这个事实 ⇒ 这里两侧都轮询、都打印。
#   $1=预算秒数 ⇒ 两侧都恢复返回 0；否则返回 1（但**不**置 FAILED，由调用方决定判据）。
wait_both_recovered() {   # $1=秒数 · $2=需要的连续成功轮数（默认 3）
  local budget="$1" need="${2:-3}" waited=0 streak=0 c_raw b_raw c_code b_code
  c_raw=""; b_raw=""; c_code=""; b_code=""
  while [ "${waited}" -lt "${budget}" ]; do
    c_raw="$(request_body "${C_HTTP_PORT}" POST "/follow/v1/follow/list" '{"user_id":"1001"}' valid)"
    b_raw="$(request_body "${B_API_PORT}"  POST "/follow/v1/follow/list" '{"user_id":"1001"}' valid)"
    # ⚠️ **必须解 hex 后再比**：request_body 的输出是 "httpcode|hex" ⇒ body 是**十六进制**
    #    ⇒ 直接拿明文模式去匹配**永远不中** ⇒ 会把「已恢复」误报成「没恢复」
    #    ⇒ ⇒ 一律走 biz_code_of（内部先 xxd -r -p 解码）。
    c_code="$(biz_code_of "${c_raw}")"; b_code="$(biz_code_of "${b_raw}")"
    if [ "${c_code}" = "200" ] && [ "${b_code}" = "200" ]; then
      streak=$((streak+1))
      if [ "${streak}" -ge "${need}" ]; then
        say "      ✅ 两侧**连续 ${need} 轮**都回 200（用时 ${waited}s）⇒ 已进入**稳定态**"
        return 0
      fi
    else
      streak=0
    fi
    sleep 1; waited=$((waited+1))
  done
  say "      ⚠️ ${budget}s 内两侧**没有连续 ${need} 轮**都回 200（C=${c_code:-?} · B=${b_code:-?}）"
  say "         ⇒ **两侧对称** ⇒ 系统级现象（服务发现条目过期 / 通道退避 / 熔断冷却），非本域漂移"
  SCEN_WARN="${SCEN_WARN}「恢复轮：两侧在 ${budget}s 内未进入稳定态（对称）· "; 
  return 1
}

# ⚠️ **恢复后的比对必须取「稳定态」并容忍过渡态**：
#   下游刚回来时系统处于**过渡态**（客户端通道在重连、服务端在消化积压的取消请求）
#   ⇒ 实测过一次「已经等到 200，紧接着的下一轮却是 C=5001 · B=1015」
#   ⇒ ⇒ ① 恢复判定要**连续多轮**都 200（见 wait_both_recovered 的 streak）；
#   ⇒ ⇒ ② 比对允许**重试**（过渡态会过去）⇒ 但**必须如实报出「第 N 次才一致」**。
# ⚠️ **重试不是「把红洗成绿」**：3 次都不同 ⇒ 判为漂移 ⇒ FAILED=1。
compare_recovered() {   # $1=用例名
  local name="$1" attempt=0 c_raw b_raw
  while [ "${attempt}" -lt 3 ]; do
    attempt=$((attempt+1))
    c_raw="$(request_body "${C_HTTP_PORT}" POST "/follow/v1/follow/list" '{"user_id":"1001"}' valid)"
    b_raw="$(request_body "${B_API_PORT}"  POST "/follow/v1/follow/list" '{"user_id":"1001"}' valid)"
    if [ "${c_raw}" = "${b_raw}" ]; then
      say "   ✅ ${name}  两侧**逐字节**一致（业务码=$(biz_code_of "${c_raw}")）"
      if [ "${attempt}" != "1" ]; then
        say "      ⚠️ 第 ${attempt} 次才一致 ⇒ **过渡态**不稳定（已如实记录，不当绿灯吞掉）"
        SCEN_WARN="${SCEN_WARN}「${name}：第 ${attempt} 次才一致（过渡态抖动）· ";
      fi
      return 0
    fi
    say "      · ${name} 第 ${attempt} 次：两侧不一致（C=$(biz_code_of "${c_raw}") · B=$(biz_code_of "${b_raw}")）⇒ 再试"
    sleep 3
  done
  FAILED=1
  say "   ❌ ${name}：连续 3 次两侧都不一致 ⇒ 判为漂移"
  return 1
}
# ⚠️ **场景轮的「对称异常」收集器** —— 收集到的东西**不置 FAILED**（对拍判据是两侧一致），
#    但**必须**在收尾摘要里显式打出来 ⇒ 不许让它变成一条无声的假绿。
SCEN_WARN=""

# ── 阶段 0：补 user-rpc 的 users_user（场景轮的两个 target）────────────────────
# ⚠️ **为什么必须补**：user-rpc 只认 `users_user` 表里的 uid ⇒ target 不存在时
#    两侧**都在更早的分支**（5101 user not found）上短路 ⇒ 根本到不了
#    「并发唯一索引」与「CHECK 约束注入」这两条被测路径
#    ⇒ 判据**空洞**（「两侧一致」只证明了「两边都 5101」）。
#    ⇒ 补 1008（并发用）/ 1009（事务回滚用）⇒ 让场景轮打在**真目标分支**上。
# ⚠️ 只灌 B 侧 PG（user-rpc 的库）—— 两侧**共用同一个 user-rpc 进程** ⇒ 一次即可。
say "   · 阶段0：补 users_user（1008 = 并发 target · 1009 = 事务回滚 target）"
SEED_SCEN_USERS="INSERT INTO users_user (uid,username,password,email,status,score,create_time,update_time) VALUES
 (1008,'probe_u1008','x','probe1008@example.invalid',0,0,now(),now()),
 (1009,'probe_u1009','x','probe1009@example.invalid',0,0,now(),now())
 ON CONFLICT DO NOTHING;"
docker exec "${B_PG_CONTAINER}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "${SEED_SCEN_USERS}" >/dev/null \
  || die "灌场景轮用的 users_user 失败：${B_PG_CONTAINER}"
n="$(pg_count "${B_PG_CONTAINER}" "SELECT count(*) FROM users_user WHERE uid IN (1008,1009)")"
[ "${n}" = "2" ] || die "users_user 的 1008/1009 没灌进去（count=${n}）⇒ 场景轮会打在 5101 分支上"
say "   ✓ users_user 已有 1008/1009（count=${n}）⇒ 场景轮打在**真目标分支**上"

# ── 阶段 1：超时（下游 **SIGSTOP** ⇒ 连接可建但无响应 ⇒ 触发 2s rpc 预算）──────
# ⚠️ **判据为什么重复 3 轮**：1015 vs 5001 的差异有两种可能 ——
#    ① **契约差异**（B 侧稳定 1015）⇒ C 侧必须改（错误码值是 L1 契约）；
#    ② **竞态**（B 侧多轮摇摆）⇒ 不改，登记「B 侧该场景非确定」。
#    ⇒ **一轮读数分不清** ⇒ 必须多轮看**分布**。
say "   · 阶段1：把下游 user-rpc 挂起（SIGSTOP）⇒ 触发 rpc 超时（3 轮看分布）"
kill -STOP "${USER_RPC_PID}" 2>/dev/null
sleep 1
TMO_C_CODES=""; TMO_B_CODES=""
for round in 1 2 3; do
  c_raw="$(request_body "${C_HTTP_PORT}" POST "/follow/v1/follow/list" '{"user_id":"1001"}' valid)"
  b_raw="$(request_body "${B_API_PORT}"  POST "/follow/v1/follow/list" '{"user_id":"1001"}' valid)"
  c_code="$(biz_code_of "${c_raw}")"; b_code="$(biz_code_of "${b_raw}")"
  TMO_C_CODES="${TMO_C_CODES}${c_code:-?} "; TMO_B_CODES="${TMO_B_CODES}${b_code:-?} "
  say "      第 ${round} 轮：C 业务码=${c_code:-?} · B 业务码=${b_code:-?}"
  if [ "${c_raw}" = "${b_raw}" ]; then
    say "      ✅ 第 ${round} 轮：两侧**逐字节**一致（HTTP ${c_raw%%|*} · 业务码 ${c_code}）"
  else
    FAILED=1
    say "      ❌ 第 ${round} 轮：两侧**不一致** ⇒ C=${c_code} vs B=${b_code}"
  fi
done
say "      3 轮分布：C = [${TMO_C_CODES}] · B = [${TMO_B_CODES}]"

# ── 阶段 1b：gRPC 层（同一挂起场景）各自的对外出口（**观察项 · 不参与判定**）─────
# ⚠️ **为什么这一段只记录、不判失败**（实测逼出来的）：
#   同一场景下，B 侧 follow-rpc 的 gRPC 层**两次运行给出不同状态码** ——
#   2026-11-02 22:0x 的两轮 = `DEADLINE_EXCEEDED`（服务端 2s 超时调度器先关 call），
#   22:2x 的一轮 = `INTERNAL`（领域层折叠后的错误先写出去）⇒
#   ⇒ **B 侧这一层本身就是竞态**（服务端的 2s 调度器与内层 user-rpc 的 2s 预算同一瞬间到期）。
#   ⇒ ⇒ **gRPC 层不能作契约判据**；**契约判据取 HTTP 层**（实测 6/6 轮稳定 1015）。
#   ⚠️ **不判失败 ≠ 不看**：本段照样打印两侧的状态码 + description + 分布，
#   并且把「非确定」这个事实写进日志与台账（§5.5 漂移 I / §5.6 漂移 J）。
say "   · 阶段1b：gRPC 层分层探测（同一挂起场景 · **观察项**：该层两侧均非确定 ⇒ 不作契约判据）"
WIRE_TMO_DIR="$(mktemp -d "${WORK}/wire-tmo.XXXXXX")" || die "建 wire 临时目录失败"
TMO1B_B=""; TMO1B_C=""; TMO1B_B_DESC=""; TMO1B_C_DESC=""
for round in 1 2 3; do
  java -cp "${WIRE_CP}" com.rag.follow.parity.FollowWireProbe 127.0.0.1 "${B_RPC_PORT}" \
       "${WIRE_TMO_DIR}/b.txt" wire_follow_list || die "B 侧 wire 超时探针执行失败"
  java -cp "${WIRE_CP}" com.rag.follow.parity.FollowWireProbe 127.0.0.1 "${C_GRPC_PORT}" \
       "${WIRE_TMO_DIR}/c.txt" wire_follow_list || die "C 侧 wire 超时探针执行失败"
  b_line="$(grep '^CASE|wire_follow_list|' "${WIRE_TMO_DIR}/b.txt" | head -1)"
  c_line="$(grep '^CASE|wire_follow_list|' "${WIRE_TMO_DIR}/c.txt" | head -1)"
  [ -n "${b_line}" ] || die "B 侧 wire 超时探针没产出 wire_follow_list 这一条"
  [ -n "${c_line}" ] || die "C 侧 wire 超时探针没产出 wire_follow_list 这一条"
  # ⚠️ **只取状态码**：description 里带「过了几毫秒」⇒ 比字节会**恒红**且毫无信息量。
  b_status="$(printf '%s' "${b_line}" | awk -F'|' '{print $3}')"
  c_status="$(printf '%s' "${c_line}" | awk -F'|' '{print $3}')"
  TMO1B_B="${TMO1B_B}${b_status} "; TMO1B_C="${TMO1B_C}${c_status} "
  TMO1B_B_DESC="$(printf '%s' "${b_line}" | awk -F'|' '{print $4}' | xxd -r -p 2>/dev/null)"
  TMO1B_C_DESC="$(printf '%s' "${c_line}" | awk -F'|' '{print $4}' | xxd -r -p 2>/dev/null)"
  say "      第 ${round} 轮：B=${b_status} · C=${c_status}"
done
say "      3 轮分布：B = [${TMO1B_B}] · C = [${TMO1B_C}]"
say "      description：B=「${TMO1B_B_DESC}」· C=「${TMO1B_C_DESC}」"
if [ "${TMO1B_B}" = "${TMO1B_C}" ]; then
  say "      ✅ 本轮 gRPC 层两侧状态码一致（3/3）"
else
  say "      ⚠️ gRPC 层两侧状态码**不一致** ⇒ **该层两侧均非确定（竞态）** ⇒ 不作契约判据"
  say "         ⇒ **契约判据取 HTTP 层**（实测 6/6 轮稳定 1015 · 逐字节一致）"
  SCEN_WARN="${SCEN_WARN}「gRPC 层超时场景两侧状态码非确定（B=[${TMO1B_B}] · C=[${TMO1B_C}]）· "; 
fi
# ⚠️ **必须放行**：后面的阶段还要用这个下游
kill -CONT "${USER_RPC_PID}" 2>/dev/null
# ⚠️ **服务发现条目在挂起期间会过期**：user-rpc 是 Nacos 的**临时实例**（TTL 数秒）
#    ⇒ 被 SIGSTOP 的那十几秒里它**没法续期** ⇒ 条目过期 ⇒ 两侧的客户端都拿不到实例
#    ⇒ ⇒ 「恢复」失败是**服务发现层**的现象，不是 follow 域的行为。
#    ⇒ ⇒ 放行后要**等它自己续回来**（SIGCONT ⇒ 续期线程恢复 ⇒ 重新注册）。
for _ in $(seq 1 15); do
  nacos_assert_instance user-rpc "${B_GROUP}" "${USER_RPC_PORT}" && break
  sleep 1
done
nacos_assert_instance user-rpc "${B_GROUP}" "${USER_RPC_PORT}" \
  || say "   ⚠️ B 侧 user-rpc 的 Nacos[${B_GROUP}] 条目还没回来（恢复轮可能因此不恢复）"
say "   ✓ 下游已放行（SIGCONT）· 服务发现条目已确认"

# ── 阶段 2：恢复（SIGCONT 后应回到 200）───────────────────────────────────────
say "   · 阶段2：下游恢复（SIGCONT）⇒ 应回到业务码 200"
wait_both_recovered 60 3 || true
# ⚠️ **判据是「两侧一致」**（对拍的判据），恢复与否是**附带观察**（上面已如实打印）。
compare_recovered "scn_recover_after_stall"

# ── 阶段 3：外部依赖失败（下游 **进程被杀** ⇒ 连接被拒）────────────────────────
# ⚠️ **窗口纪律**：kill 后要**立刻**发请求 ⇒ 两侧的 Nacos 实例都还在解析池里
#    ⇒ 两侧都得到「连接被拒」⇒ 不会因为「实例已被摘除」而让两侧走出不同分支。
say "   · 阶段3：杀掉下游 user-rpc ⇒ 连接被拒（业务码非 200）"
kill -9 "${USER_RPC_PID}" 2>/dev/null
sleep 1
compare_both "scn_downstream_down" POST "/follow/v1/follow/list" '{"user_id":"1001"}' valid fail

# ── 阶段 4：恢复（重启下游 ⇒ 应回到 200）─────────────────────────────────────
say "   · 阶段4：重启下游 user-rpc ⇒ 应回到业务码 200"
start_user_rpc || die "重启 B 侧 user-rpc 失败"
sleep 3
wait_both_recovered 60 3 || true
compare_recovered "scn_recover_after_restart"

# ── 阶段 5：并发（**双侧** 8 路并发关注同一 target）───────────────────────────
# ⚠️ **响应 body 允许因竞态而不同**（谁先抢到唯一索引谁返回「已关注」）
#    ⇒ 判据是 ① 两侧**响应集合**（排序去重后）相同 ② 两侧各自库里**恰好 1 行**
#    ⇒ **不**逐条比 body。
run_concurrent_follow() {   # $1=端口 $2=输出文件 $3=target_id
  local port="$1" out="$2" target="$3" i tmp code pids=()
  : > "${out}"
  for i in $(seq 1 8); do
    (
      tmp="$(mktemp "${WORK}/cc.XXXXXX")"
      code="$(curl -s -o "${tmp}" -w '%{http_code}' -m 30 -X POST \
               "http://127.0.0.1:${port}/follow/v1/follow" \
               -H "Authorization: Bearer ${JWT_TOKEN}" -H 'Content-Type: application/json' \
               --data-binary "{\"target_id\":\"${target}\"}" 2>/dev/null)"
      printf '%s|%s\n' "${code}" "$(xxd -p "${tmp}" | tr -d '\n')" >> "${out}"
    ) &
    pids+=($!)
  done
  # ⚠️ **只等这 8 个**：`wait` 不带参数会等**脚本的所有**后台任务（含 4 个 java 进程）⇒ 死等
  for p in "${pids[@]}"; do wait "${p}" 2>/dev/null; done
}
say "   · 阶段5：并发关注（两侧各 8 路并发 ⇒ 各自恰好 1 行）"
run_concurrent_follow "${C_HTTP_PORT}" "${WORK}/cc-c.txt" 1008
run_concurrent_follow "${B_API_PORT}"  "${WORK}/cc-b.txt" 1008
c_codes="$(codes_of "${WORK}/cc-c.txt")"
b_codes="$(codes_of "${WORK}/cc-b.txt")"
c_rows="$(pg_count "${C_PG_CONTAINER}" "SELECT count(*) FROM follow_relation WHERE user_id=1001 AND target_id=1008")"
b_rows="$(pg_count "${B_PG_CONTAINER}" "SELECT count(*) FROM follow_relation WHERE user_id=1001 AND target_id=1008")"
say "      业务码集合：C=[${c_codes}] · B=[${b_codes}]"
# ⚠️ **必须断言「打到了真分支」**：target 不存在 ⇒ 两侧都在 5101（user not found）短路
#    ⇒ 「两侧一致 + 行数 0」也是「一致」⇒ 空洞判据 ⇒ 这里显式要求**业务码集合 = {200}**
#    （B 侧既定行为：重复关注返回 SUCCESS，唯一键 + DO NOTHING ⇒ 8 路并发全部 200）。
if [ "${c_codes}" != "200" ] || [ "${b_codes}" != "200" ]; then
  FAILED=1
  say "   ❌ scn_concurrent_follow：业务码集合不是 {200}（C=[${c_codes}] · B=[${b_codes}]）⇒ 没打到写路径 / 并发语义不一致"
elif [ "${c_rows}" != "1" ] || [ "${b_rows}" != "1" ]; then
  FAILED=1
  say "   ❌ scn_concurrent_follow：行数不是 1（C=${c_rows} · B=${b_rows}）⇒ 并发语义不一致"
else
  say "   ✅ scn_concurrent_follow  两侧业务码集合都是 {200} 且 各自恰好 1 行（C=${c_rows} · B=${b_rows}）"
fi

# ── 阶段 6：事务回滚（**双侧** 同款 CHECK 约束 ⇒ block 失败 ⇒ 回滚）───────────
# ⚠️ **失败注入必须两侧完全对称**：同一个 CHECK 约束、同一批种子行、同一个 target
#    ⇒ 两侧的失败时刻与失败原因同形 ⇒ 比出来的差异才是行为差异。
# ⚠️ `insertIgnoreConflict` 用的是 `ON CONFLICT DO NOTHING` ⇒ **CHECK 违反不是 conflict**
#    ⇒ 一定抛异常 ⇒ 事务回滚 ⇒ 随后的 `deleteFollowBothDirections` 根本不执行
#    ⇒ **follow 行应当仍在** ⇒ 这就是「回滚生效」的判据。
# ⚠️ **播种必须幂等**（`ON CONFLICT DO NOTHING`）：变异 M3 会在 C 侧**多灌一行 (1001,1009)**
#    ⇒ 若这里用裸 INSERT ⇒ **主键冲突 ⇒ 探针在阶段6 `die`（exit 2）**
#    ⇒ ⇒ 变异轮读到的会是「环境问题」而不是「矩阵红」⇒ **假红**（本轮实测踩过）。
#    ⇒ 幂等播种后：M3 轮里这一行已存在 ⇒ 阶段6 的判据依旧成立（follow 行 = 1 · block 行 = 0）。
say "   · 阶段6：事务回滚（两侧各加同款 CHECK 约束 ⇒ block 失败 ⇒ 回滚）"
SEED_TX="INSERT INTO follow_relation (user_id,target_id,create_time,update_time) VALUES (1001,1009,'2026-01-01 00:00:09+00','2026-01-01 00:00:09+00') ON CONFLICT DO NOTHING;"
for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  docker exec "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "${SEED_TX}" >/dev/null \
    || die "灌事务回滚用的 follow 行失败：${c}"
  docker exec "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 \
    -c "ALTER TABLE block_relation ADD CONSTRAINT e4_force_rollback CHECK (target_id <> 1009);" >/dev/null \
    || die "加回滚注入约束失败：${c}"
done
c_raw="$(request_body "${C_HTTP_PORT}" POST "/follow/v1/block" '{"target_id":"1009"}' valid)"
b_raw="$(request_body "${B_API_PORT}"  POST "/follow/v1/block" '{"target_id":"1009"}' valid)"
c_code="$(biz_code_of "${c_raw}")"; b_code="$(biz_code_of "${b_raw}")"
TX_ROLLBACK_OK=1
if [ "${c_raw}" != "${b_raw}" ]; then
  TX_ROLLBACK_OK=0
  FAILED=1
  say "   ❌ scn_tx_rollback_block：两侧字节不一致（C=${c_code} · B=${b_code}）"
fi
# ⚠️ **判据必须强到「排除空洞」**：只断言「两侧一致 + 行数」⇒ 5101（目标用户不存在）
#    也会通过 ⇒ 等于没验到注入。
#    ⇒ 这里**显式**要求业务码 == 5008（写路径 DB 失败）
#    ⇒ 这才证明「注入的 CHECK 约束真的炸了，且炸在**写路径**上」。
if [ "${c_code}" != "5008" ] || [ "${b_code}" != "5008" ]; then
  TX_ROLLBACK_OK=0
  FAILED=1
  say "   ❌ scn_tx_rollback_block：业务码不是 5008（写路径 DB 失败）（C=${c_code} · B=${b_code}）⇒ 注入没触发 / 打错分支"
fi
for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  n="$(pg_count "${c}" "SELECT count(*) FROM follow_relation WHERE user_id=1001 AND target_id=1009")"
  m="$(pg_count "${c}" "SELECT count(*) FROM block_relation WHERE user_id=1001 AND target_id=1009")"
  if [ "${n}" != "1" ] || [ "${m}" != "0" ]; then
    TX_ROLLBACK_OK=0
    FAILED=1
    say "   ❌ scn_tx_rollback_block：回滚判据不成立（follow 行=${n}（应 1）· block 行=${m}（应 0））⇒ 容器 ${c}"
  fi
done
[ "${TX_ROLLBACK_OK}" = "1" ] \
  && say "   ✅ scn_tx_rollback_block  两侧都是 5008 且都回滚了（follow 行仍在 · block 行未写入）"
# 复原（对称移除）
for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  docker exec "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 \
    -c "ALTER TABLE block_relation DROP CONSTRAINT IF EXISTS e4_force_rollback;" >/dev/null 2>&1
done

# ══════════════════════════ 9. 收尾：服务**真正**停掉 + 端口零残留 ══════════════
# ⚠️ **必须先真正收尾（kill + 等），再查残留**（stop_stacks 幂等 ⇒ trap 再调一次无害）。
#    反过来（先查残留后收尾）⇒ 进程还活着 ⇒ **必然**报残留 ⇒ **恒红的假信号**。
step "9/9 收尾复核（服务真停 · 端口零残留）"
stop_stacks
LEFTOVER=0
for p in "${B_API_PORT}" "${B_RPC_PORT}" "${USER_RPC_PORT}" "${C_HTTP_PORT}" \
         "${C_GRPC_PORT}" "${B_RPC_MGMT_PORT}" "${USER_RPC_MGMT_PORT}"; do
  for pid in $(lsof -ti tcp:"${p}" 2>/dev/null); do
    say "   ❌ 端口 :${p} 仍有残留（pid ${pid}）"
    LEFTOVER=1
  done
done
if [ "${LEFTOVER}" = "1" ]; then
  say ""
  say "❌ 收尾后本探针的端口仍有残留 ⇒ 探针污染了宿主环境（下一轮会撞车）"
  exit 2
fi
say "   ✓ 收尾复核：本探针的 7 个端口**零残留**"

echo
if [ -n "${SCEN_WARN}" ]; then
  say "⚠️ 场景轮的**对称异常**（两侧一致 ⇒ 非漂移，但如实记录）：${SCEN_WARN}"
fi
if [ "${FAILED}" = "0" ]; then
  say "✅ 三方逐字节一致（C 侧 == B 侧 == 冻结的 Go 字节）"
  exit 0
fi
say "❌ 存在字节差异"
dump_logs
exit 1
