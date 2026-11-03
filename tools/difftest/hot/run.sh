#!/usr/bin/env bash
#
# hot 域 **双侧活体对拍**探针 —— **B 侧（RAG / Boot 4）↔ C 侧（RAG-V1 / Boot 3.5）**
#
# 被测两侧（**同时起、同时活、同请求、同字节**）：
#   · B 侧 = /Users/pr/Documents/java&golang/RAG/service/hot/{hot-api,hot-rpc}（Boot 4.0.8）
#   · C 侧 = 本仓 service/hot（Boot 3.5.11 · D1 合并进程：HTTP + gRPC 同进程）
#
# 对拍面（四个面，全部**逐字节**）：
#   · M1  HTTP 字节面   —— 原始状态行 + Content-Type + body hex
#   · M2  gRPC wire 面  —— **原始 proto3 字节**（不用任何 pb 生成码 ⇒ 同一个 jar 跑两侧）
#   · M3  Redis 状态面  —— `hot:articles` 的成员与分数（+ 分片 key + TTL）
#   · M4  Kafka 三面    —— 发同款事件 ⇒ 两侧消费 ⇒ HeavyKeeper ⇒ 合并 ⇒ 比 `hot:articles`
#
# ⚠️ **为什么没有「第三方锚」**：本机没有 Go 真源可活体（无 Sea-etcd 容器）⇒
#    本探针的判定条件是 **C == B（双侧活体逐字节相等）**，
#    强度记为 **E-4（双侧活体对拍）**，**不冒充「三方一致」**。
#    Go 侧的冻结字节锚在 B 仓 `tools/api-full-parity-probe/hot/`，本探针**不引用**它
#    （引用一个本机不可复现的锚 ⇒ 锚坏了也看不出来 ⇒ 那是「摆设锚」）。
#
# ⚠️ **B 侧只读**：本探针只在 B 仓跑 `mvn package`（产物落 `target/`，已被 .gitignore 排除）
#   ⇒ **不碰 B 侧任何源码 / 配置**（第 0 步与第 2 步各断言一次 B 仓 `git status` 干净）。
#
# 用法：
#   bash tools/difftest/hot/run.sh
#   B_REPO=/path/to/RAG bash tools/difftest/hot/run.sh
#   SKIP_KAFKA=1 bash tools/difftest/hot/run.sh     # 只跑 M1/M2/M3（不起 Kafka broker）
#   KEEP_WORK=1 bash tools/difftest/hot/run.sh      # 保留临时目录与日志
#
# 退出码：0 = 双侧逐字节一致 · 1 = 有真差异 · 2 = 环境问题 / 依赖缺失 / 真源漂移。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"

# ── 两侧仓路径 ─────────────────────────────────────────────────────────────────
B_REPO="${B_REPO:-/Users/pr/Documents/java&golang/RAG}"

# ── 宿主中间件端口 ───────────────────────────────────────────────────────────
# ⚠️ **只能走发布口**：Docker Desktop(macOS) 的宿主**不可达容器 IP** ⇒ 探针自己注册
#    **127.0.0.1** 形态的实例（与 like / follow 域探针同款做法）。
NACOS_PORT="${NACOS_PORT:-49848}"    # Sea-nacos 49848→8848
JAEGER_PORT="${JAEGER_PORT:-49317}"  # Sea-jaeger 49317→4317（OTLP gRPC）

# ── 端口（**本探针专用段 18221-18232**；follow 占 18201-18209 · like 占 18211-18219）──
B_API_PORT="${B_API_PORT:-18221}"            # B 侧 hot-api（HTTP）
B_RPC_PORT="${B_RPC_PORT:-18222}"            # B 侧 hot-rpc（gRPC 业务口）
B_RPC_MGMT_PORT="${B_RPC_MGMT_PORT:-18223}"  # B 侧 hot-rpc 的 Actuator 载体
C_HTTP_PORT="${C_HTTP_PORT:-18224}"          # C 侧 hot-service（HTTP）
C_GRPC_PORT="${C_GRPC_PORT:-18225}"          # C 侧 hot-service（gRPC）
B_REDIS_PORT="${B_REDIS_PORT:-18226}"        # B 侧 Redis（**必须与 C 侧物理隔离**）
C_REDIS_PORT="${C_REDIS_PORT:-18227}"        # C 侧 Redis
STUB_PORT="${STUB_PORT:-18228}"              # article-rpc 桩（**两侧共用同一进程**）
C_PG_PORT="${C_PG_PORT:-18229}"              # C 侧 Postgres（hot 域无表，但 C 侧配了 datasource）
B_KAFKA_PORT="${B_KAFKA_PORT:-18231}"        # B 侧 Kafka broker
C_KAFKA_PORT="${C_KAFKA_PORT:-18232}"        # C 侧 Kafka broker

# ── Nacos 分组隔离（🔴 关键）──────────────────────────────────────────────────
# ⚠️ **B 侧的注册组是「半隔离」**，这是 B 侧代码的既成事实，探针必须照单全收：
#   · B 侧 hot-rpc 的 `NacosRegistration` **硬编码 `DEFAULT_GROUP`**
#     （`service/hot/hot-rpc/.../bootstrap/NacosRegistration.java:58,113`）
#     ⇒ 它的 `hot-rpc` 实例**永远**落在 DEFAULT_GROUP；
#   · B 侧 hot-api 的 yml 是 `group: ${NACOS_GROUP:DEFAULT_GROUP}`
#     ⇒ 探针**不传** `NACOS_GROUP` 给 B 侧 ⇒ 它的 discovery group = DEFAULT_GROUP
#     ⇒ ⇒ 两者**天然对上**（探针不能改 B 侧任何文件 ⇒ 只能这么用）。
#   · C 侧的一切（注册 + 查找）都在 `E5C` ⇒ 与 B 侧**完全隔离**
#     ⇒ 同名服务不会互拨 ⇒ 对拍对象不会「静默地错了」。
#   · article-rpc 桩**同一个进程注册进两个组** ⇒ 两侧拿到**逐字节相同**的 article 投影
#     ⇒ ⇒ 任何差异都只能归因于 hot 域自身。
B_GROUP="${B_GROUP:-DEFAULT_GROUP}"   # B 侧 hot-rpc / hot-api 的 discovery group
C_GROUP="${C_GROUP:-E5C}"             # C 侧 hot-service 的注册组 + 客户端查找组
NACOS_NAMESPACE="${NACOS_NAMESPACE:-hotprobe}"   # **专属命名空间** ⇒ 与别的 lane 完全隔离

# ── 两侧各自的容器（容器名带 $$ ⇒ 别的 lane 的 cleanup 删不到本轮的库）─────────
# 🔴 **Redis 必须两侧物理隔离**（不是「省一个容器」的取舍，是正确性）：
#   `hot:articles` 是**写路径**（Kafka 合并会 RENAME 覆盖它）
#   ⇒ 共用一个 Redis 时，B 侧合并完 C 侧再合并 ⇒ 两侧读数**必然不同** ⇒ **假漂移**。
# 🔴 **Kafka 必须两侧物理隔离**：两侧的 `spring.kafka.consumer.group-id` 都是**字面量**
#   `article-hot-group`、topic 都是字面量 `article-hot-events`（**B 侧不可覆盖**）
#   ⇒ 共用一个 broker ⇒ 两侧互吃对方的消息 ⇒ **假漂移**。
B_REDIS_CONTAINER="${B_REDIS_CONTAINER:-e5hot-b-redis-$$}"
C_REDIS_CONTAINER="${C_REDIS_CONTAINER:-e5hot-c-redis-$$}"
C_PG_CONTAINER="${C_PG_CONTAINER:-e5hot-c-pg-$$}"
B_KAFKA_CONTAINER="${B_KAFKA_CONTAINER:-e5hot-b-kafka-$$}"
C_KAFKA_CONTAINER="${C_KAFKA_CONTAINER:-e5hot-c-kafka-$$}"

# ── Kafka 开关 ───────────────────────────────────────────────────────────────
SKIP_KAFKA="${SKIP_KAFKA:-0}"
# ⚠️ **SKIP_KAFKA=1 ⇒ 关掉 Kafka listener**（像域探针同款）：
#    不起 broker 时，listener 会在后台无限重连 ⇒ 日志被刷爆 ⇒ 真故障被噪声淹没。
KAFKA_LISTENER_ENV=""
[ "${SKIP_KAFKA}" = "1" ] && KAFKA_LISTENER_ENV="SPRING_KAFKA_LISTENER_AUTO_STARTUP=false"
# ⚠️ **两侧同样覆盖 `auto-offset-reset=earliest`**：
#   两侧 yml 都是 `latest`（B 侧 Go 是 sarama.OffsetNewest）⇒ 若 consumer 还没 join
#   就发消息，消息会被**跳过** ⇒ 那是**竞态**，不是漂移。
#   ⇒ 覆盖**对称**施加于两侧 ⇒ 不影响对拍公平性（两侧读数口径完全相同）。
KAFKA_EVENTS="${KAFKA_EVENTS:-600}"
KAFKA_ARTICLE_ID="${KAFKA_ARTICLE_ID:-probe-kafka-1}"
KAFKA_EVENT_TYPE="${KAFKA_EVENT_TYPE:-like}"   # 权重 1 ⇒ 期望分数 = 事件条数
KAFKA_EXPECT_SCORE="${KAFKA_EXPECT_SCORE:-${KAFKA_EVENTS}}"

# ── 播种数据（**两侧逐条相同**）────────────────────────────────────────────────# ⚠️ **成员名里带标记** ⇒ 用来触发 hot 域的**四条 skip 分支**：
#   · probe-a1 / probe-a2   —— 正常条目
#   · probe-a3              —— 分数 3000000000（> 2^31）⇒ 测 uint32 的**无符号还原**
#   · probe-tags            —— 桩返回非空 secondary_tags ⇒ 测「数组 vs null」
#   · probe-draft           —— 桩返回 status=DRAFT ⇒ 触发 **article_not_published** skip
#   · probe-missing         —— 桩返回空响应     ⇒ 触发 **article_missing** skip
#   · probe-rpcerr          —— 桩返回 gRPC 错误 ⇒ 触发 **article_rpc_error** skip
#   · ""（空成员）          —— 触发 **empty_article_id** skip
# ⚠️ **分数用整数**：Redis 的 score 是 float64 ⇒ 整数分在两侧都是同一个 double
#    ⇒ 逐字节可比（浮点尾数差异会变成假红）。
SEED_A1="probe-a1";  SEED_A1_SCORE=100
SEED_A2="probe-a2";  SEED_A2_SCORE=90
SEED_A3="probe-a3";  SEED_A3_SCORE=3000000000
SEED_TAGS="probe-tags"; SEED_TAGS_SCORE=80
SEED_DRAFT="probe-draft"; SEED_DRAFT_SCORE=70
SEED_MISSING="probe-missing"; SEED_MISSING_SCORE=60
SEED_RPCERR="probe-rpcerr"; SEED_RPCERR_SCORE=50
SEED_EMPTY="";       SEED_EMPTY_SCORE=40
# ⚠️ **25 条填充成员（分数 1..25）**：让 `top_k` 的两条边界**在字节上可分辨**。
#   没有填充时，库里只有 8 条 ⇒ `top_k` 取 20 / 0 / -1 / 100 / 101 **返回的是同一批 8 条**
#   ⇒ 响应字节完全相同 ⇒ **「k <= 0 ⇒ 20」与「k > 100 ⇒ 100」这两条契约根本验不到**
#   ⇒ 那是**判据空洞**（不是「跑绿」）。
#   有了 25 条填充 ⇒ 库里共 33 条 ⇒ top_k∈{20,0,-1} 返回 **20** 条、top_k∈{100,101} 返回 **33** 条
#   ⇒ 两条边界各自显形。
# ⚠️ **填充成员的分数必须低于 40**（空成员那条）⇒ 不打乱前 4 名的 rank 连续性断言。
SEED_FILL_COUNT=25
SEED_FILL_BASE=1

WORK="$(mktemp -d)"
PIDS=()
KEEPALIVE_PIDS=()
say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
dump_logs() {
  local f
  for f in b-rpc.log b-api.log c-service.log stub.log; do
    [ -f "${WORK:-/nonexistent}/${f}" ] || continue
    printf '\n---- %s（末 40 行）----\n' "${f}" >&2
    tail -40 "${WORK}/${f}" | sed 's/^/   | /' >&2
  done
}
die()  { printf '!! %s\n' "$*" >&2; dump_logs; exit 2; }

host_port_open() { nc -z -G 2 127.0.0.1 "$1" >/dev/null 2>&1; }

# ══════════════════════════ Nacos 实例管理（**分组 + 命名空间感知**）═════════════
# ⚠️ **必须带 groupName**：C 侧把 `hot-rpc` 注册进 `E5C`，B 侧注册进 `DEFAULT_GROUP`
#    ⇒ 不分组 ⇒ 两边同名 ⇒ 解析池里混进对面的实例 ⇒ **对拍对象静默地错了**。
nacos_list_instances() {   # $1 = 服务名  $2 = 组
  curl -s -m 10 "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance/list?serviceName=$1&groupName=$2&namespaceId=${NACOS_NAMESPACE}" 2>/dev/null \
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
#    persistent（ephemeral=false）走 Nacos 的 **Raft** 持久化组 ⇒ 本机 Nacos 无 leader
#    ⇒ 注册不进去；ephemeral 实例靠客户端心跳保活 ⇒ 这里用 curl 手工注册 ⇒ 必须自己保活。
nacos_register() {   # $1=服务名 $2=组 $3=ip $4=端口
  curl -s -m 10 -X POST "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance" \
    --data-urlencode "serviceName=$1" \
    --data-urlencode "groupName=$2" \
    --data-urlencode "namespaceId=${NACOS_NAMESPACE}" \
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
  local i listing
  for i in $(seq 1 30); do
    # ⚠️ **先抓进变量再匹配**（不要 `| grep -q`）：`grep -q` 命中即退出 ⇒ 上游收 SIGPIPE
    #    ⇒ 本脚本开了 `pipefail` ⇒ 整条管道退出码变 141 ⇒ `&& return 0` 不会执行
    #    ⇒ **断言会假阴性**（明明在列表里也判成「不在」）。
    listing="$(nacos_list_instances "$1" "$2")"
    case "${listing}" in
      *" ${3}") return 0 ;;
    esac
    sleep 1
  done
  return 1
}
nacos_deregister() {  # $1=服务名 $2=组 $3=ip $4=端口
  curl -s -m 5 -X DELETE "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance?serviceName=$1&groupName=$2&namespaceId=${NACOS_NAMESPACE}&ip=$3&port=$4" >/dev/null 2>&1
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
  # ⚠️ **只清服务口，不清容器口**：容器口由 docker-proxy 持有 ⇒ 按端口无差别 kill
  #    会**误杀 Docker daemon 的代理** ⇒ 容器口走「换端口」而不是「杀进程」。
  # 🔴 **调用时机至关重要**：本函数会把占用端口的进程 **kill -9**
  #    ⇒ **必须在 article-rpc 桩启动之前调用**
  #    ⇒ 否则会把**自己的桩**踢掉 ⇒ 两侧补全整片 skip ⇒ HTTP 面退化成
  #    「两个空壳相等」的**假绿**（2026-11-04 实测踩到，见 §九·9.5）。
  for p in "${B_API_PORT}" "${B_RPC_PORT}" "${B_RPC_MGMT_PORT}" "${C_HTTP_PORT}" "${C_GRPC_PORT}" "${STUB_PORT}"; do
    for pid in $(lsof -ti tcp:"${p}" 2>/dev/null); do
      kill -9 "${pid}" 2>/dev/null
      say "   · 踢掉占用 :${p} 的残留进程 ${pid}"
    done
  done
  sleep 1
}

stop_stacks() {   # 真正把两侧服务停掉（**幂等** ⇒ 可被 cleanup 与收尾复核各调一次）
  local p
  for p in ${KEEPALIVE_PIDS[@]+"${KEEPALIVE_PIDS[@]}"}; do kill "$p" 2>/dev/null; done
  for p in ${PIDS[@]+"${PIDS[@]}"}; do kill "$p" 2>/dev/null; done
  sleep 1
  for p in ${PIDS[@]+"${PIDS[@]}"}; do kill -9 "$p" 2>/dev/null; done
  sleep 1
}

cleanup() {
  stop_stacks
  # 只注销**本探针自己注册**的实例（ip+port 精确匹配）
  nacos_deregister article-rpc "${B_GROUP}" 127.0.0.1 "${STUB_PORT}"
  nacos_deregister article-rpc "${C_GROUP}" 127.0.0.1 "${STUB_PORT}"
  docker rm -f "${B_REDIS_CONTAINER}" >/dev/null 2>&1
  docker rm -f "${C_REDIS_CONTAINER}" >/dev/null 2>&1
  docker rm -f "${C_PG_CONTAINER}" >/dev/null 2>&1
  docker rm -f "${B_KAFKA_CONTAINER}" >/dev/null 2>&1
  docker rm -f "${C_KAFKA_CONTAINER}" >/dev/null 2>&1
  # ⚠️ **不删临时目录**：`rm -rf` 在本机会被回收站守卫拦下 ⇒ 留着由系统清理。
  if [ "${KEEP_WORK:-0}" = "1" ]; then
    say "   · KEEP_WORK=1 ⇒ 保留 ${WORK}"
  fi
}
trap cleanup EXIT

# ══════════════════════════ 0. 前置检查（缺 ⇒ exit 2，绝不静默降级）═════════════
step "0/13 前置检查"
for cmd in docker mvn java javac curl python3 lsof nc unzip; do
  command -v "${cmd}" >/dev/null 2>&1 || die "缺命令：${cmd}"
done
[ -d "${B_REPO}/service/hot/hot-api" ] || die "找不到 B 侧仓：${B_REPO}/service/hot（用 B_REPO=... 指定）"
[ -d "${C_ROOT}/service/hot" ] || die "找不到 C 侧 hot 模块：${C_ROOT}/service/hot"
nc -z -G 2 127.0.0.1 "${NACOS_PORT}" || die "Nacos 端口 ${NACOS_PORT} 不通（Sea-nacos 没在跑？）"
nc -z -G 2 127.0.0.1 "${JAEGER_PORT}" || die "Jaeger 端口 ${JAEGER_PORT} 不通（Sea-jaeger 没在跑？）"
# ⚠️ **B 仓必须干净**：本探针只在 B 仓产出 target/（.gitignore 排除）⇒ 源码必须一个字节没动。
# ⚠️ **先抓进变量再判空**（不要 `| grep -q`）：`grep -q` 命中即退出 ⇒ `git` 收 SIGPIPE
#    ⇒ `pipefail` ⇒ 管道退出码变 141 ⇒ `&& die` 不会执行 ⇒ **守卫静默失效**。
B_REPO_DIRTY="$( cd "${B_REPO}" && git status --porcelain -- service/hot service/common )"
[ -n "${B_REPO_DIRTY}" ] && die "B 仓 service/hot|service/common 有改动 ⇒ 本探针**不得**改真源"
say "   ✓ 命令齐 / Nacos+Jaeger 在跑 / B 仓干净"

# ══════════════════════════ 1. 隔离资源 ═══════════════════════════════════════
step "1/13 隔离资源：2 × Redis（+ 1 × PG + 2 × Kafka broker）"
# ⚠️ **必须先清掉「上一轮遗留的同前缀容器」**：容器名带 `$$` ⇒ 每轮唯一
#    ⇒ 上一轮的容器**不会被本轮的 cleanup 删掉**（上一轮若被强杀 ⇒ cleanup 没跑）
#    ⇒ ⚠️ **Exited 的容器仍然占着发布端口**（docker-proxy 还在）⇒ `docker run -p` 报
#    `port is already allocated` ⇒ **与代码无关的假故障**。
purge_stale_my_containers() {
  local c
  for c in $(docker ps -aq --filter "name=e5hot-" 2>/dev/null); do
    docker rm -f "${c}" >/dev/null 2>&1
    say "   · 已清理上一轮遗留的探针容器 ${c}"
  done
  sleep 3   # docker-proxy 释放监听口要一会儿
}
purge_stale_my_containers
# ⚠️ 就绪判据必须是「**宿主端口**通」：`docker exec redis-cli` 走的是**容器内 socket**，
#    与 `-p` 发布**无关** ⇒ 容器活着 ≠ 宿主端口通。
start_redis() {  # $1 = 容器名  $2 = 宿主端口
  local c="$1" p="$2"
  docker rm -f "${c}" >/dev/null 2>&1
  docker run -d --name "${c}" -p "${p}:6379" redis:7-alpine >/dev/null 2>&1 || return 1
  for _ in $(seq 1 30); do
    host_port_open "${p}" && return 0
    sleep 1
  done
  return 1
}
start_pg() {     # $1 = 容器名  $2 = 宿主端口
  local c="$1" p="$2"
  docker rm -f "${c}" >/dev/null 2>&1
  docker run -d --name "${c}" -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=sea \
    -e POSTGRES_DB=first_db -p "${p}:5432" postgres:16-alpine >/dev/null 2>&1 || return 1
  for _ in $(seq 1 60); do
    docker exec "${c}" pg_isready -U admin -d first_db >/dev/null 2>&1 && break
    sleep 1
  done
  for _ in $(seq 1 15); do
    host_port_open "${p}" && return 0
    sleep 1
  done
  return 1
}
start_redis "${B_REDIS_CONTAINER}" "${B_REDIS_PORT}" \
  || die "B 侧 Redis 起不来（端口 ${B_REDIS_PORT}）"
start_redis "${C_REDIS_CONTAINER}" "${C_REDIS_PORT}" \
  || die "C 侧 Redis 起不来（端口 ${C_REDIS_PORT}）"
# ⚠️ **PG 只给 C 侧**：B 侧 hot-api / hot-rpc 的 pom **没有** datasource 依赖
#    ⇒ 不需要。C 侧 pom 有 mybatis-plus + postgresql ⇒ 配了 `spring.datasource.url`
#    ⇒ 给一个可达的 PG（hot 域本身**不写库** ⇒ 只为让上下文起得来）。
start_pg "${C_PG_CONTAINER}" "${C_PG_PORT}" \
  || die "C 侧 Postgres 起不来（端口 ${C_PG_PORT}）"
say "   ✓ 两侧 Redis 就绪（B :${B_REDIS_PORT} · C :${C_REDIS_PORT}）＋ C 侧 PG :${C_PG_PORT}"

# ── Kafka broker（KRaft 单节点）────────────────────────────────────────────────
# ⚠️ **两侧各一个 broker**：group-id 与 topic 两侧都是**字面量**且**不可覆盖**
#    ⇒ 共用一个 broker ⇒ 互吃对方的消息 ⇒ 假漂移。
# 🔴 **必须两个 listener（INTERNAL / EXTERNAL）**：
#    `ADVERTISED_LISTENERS` 里的地址是 broker **告诉客户端**该连哪儿 ⇒
#    若只写 `PLAINTEXT://127.0.0.1:<宿主口>` ⇒ **容器内**的 kafka-topics.sh 拿到元数据后
#    去连 `127.0.0.1:<宿主口>`（容器内**没有**这个口）⇒ CLI **永久挂住**（实测卡死 5 分钟）。
#    ⇒ 拆成：INTERNAL（容器内 CLI 用 `127.0.0.1:29092`）＋ EXTERNAL（宿主用 `127.0.0.1:<宿主口>`）。
KAFKA_INTERNAL_BOOTSTRAP="127.0.0.1:29092"
# ⚠️ **每次就绪检查都必须有上界**：`docker exec` 里的 kafka CLI 在连不上时**不返回**
#    ⇒ 没有 watchdog 的话整个探针会**死等**（这正是本轮第一次跑卡住的地方）。
kafka_ready_once() {   # $1 = 容器名 ⇒ 15s 内返回 0（就绪）/ 1（未就绪）
  docker exec "$1" bash -c '
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:29092 --list >/dev/null 2>&1 &
    p=$!
    ( sleep 15; kill -9 "$p" 2>/dev/null ) &
    w=$!
    wait "$p"; rc=$?
    kill "$w" 2>/dev/null
    exit "$rc"' >/dev/null 2>&1
}
start_kafka() {   # $1 = 容器名  $2 = 宿主端口
  local c="$1" p="$2"
  docker rm -f "${c}" >/dev/null 2>&1
  docker run -d --name "${c}" -p "${p}:9092" \
    -e KAFKA_NODE_ID=1 \
    -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS=INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093 \
    -e KAFKA_ADVERTISED_LISTENERS=INTERNAL://127.0.0.1:29092,EXTERNAL://127.0.0.1:${p} \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    -e KAFKA_NUM_PARTITIONS=3 \
    apache/kafka:3.7.2 >/dev/null 2>&1 || return 1
  for _ in $(seq 1 30); do
    if kafka_ready_once "${c}"; then
      return 0
    fi
    sleep 2
  done
  return 1
}
if [ "${SKIP_KAFKA}" = "1" ]; then
  say "   ⚠️ SKIP_KAFKA=1 ⇒ **不起 Kafka broker** ⇒ M4（Kafka 三面）不跑"
else
  start_kafka "${B_KAFKA_CONTAINER}" "${B_KAFKA_PORT}" \
    || die "B 侧 Kafka broker 起不来（端口 ${B_KAFKA_PORT}）"
  start_kafka "${C_KAFKA_CONTAINER}" "${C_KAFKA_PORT}" \
    || die "C 侧 Kafka broker 起不来（端口 ${C_KAFKA_PORT}）"
  # ⚠️ **必须显式建 topic 且分区数 = 3**：hot-rpc 的 `partition-count: 3`
  #    ⇒ merge 只读 p0..p2 ⇒ 分区数与它不一致 ⇒ 多出来的文章永远不出现在 hot:articles。
  for c in "${B_KAFKA_CONTAINER}" "${C_KAFKA_CONTAINER}"; do
    docker exec "${c}" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${KAFKA_INTERNAL_BOOTSTRAP}" \
      --create --if-not-exists --topic article-hot-events --partitions 3 --replication-factor 1 \
      >/dev/null 2>&1 || die "建 topic article-hot-events 失败（${c}）"
  done
  say "   ✓ 两侧 Kafka broker 就绪（B :${B_KAFKA_PORT} · C :${C_KAFKA_PORT}）＋ topic article-hot-events×3 分区"
fi

# ══════════════════════════ 2. 构建两侧 ═══════════════════════════════════════
step "2/13 构建两侧（离线；B 侧**只读**）"
( cd "${B_REPO}" && mvn -o -q -pl service/hot/hot-api,service/hot/hot-rpc -am package -DskipTests ) \
  || die "B 侧构建失败"
B_API_JAR="${B_REPO}/service/hot/hot-api/target/hot-api-0.0.1-SNAPSHOT.jar"
B_RPC_JAR="${B_REPO}/service/hot/hot-rpc/target/hot-rpc-0.0.1-SNAPSHOT.jar"
[ -f "${B_API_JAR}" ] && [ -f "${B_RPC_JAR}" ] || die "B 侧 jar 没产出"
( cd "${C_ROOT}" && mvn -o -q -pl service/hot -am package -DskipTests ) || die "C 侧构建失败"
C_JAR="${C_ROOT}/service/hot/target/hot-service-1.0-SNAPSHOT.jar"
[ -f "${C_JAR}" ] || die "C 侧 jar 没产出：${C_JAR}"
# ⚠️ **顺带取 C 侧的运行时 classpath**：gRPC wire 探针与 article-rpc 桩都要用它编译/运行
#    （wire 探针**只用 grpc 的原始字节 marshaller** ⇒ 不依赖任何 pb 生成码
#      ⇒ **同一个 jar 跑两侧** ⇒ 比的是纯 wire 字节，不被「两侧 pb 包名不同」污染）。
( cd "${C_ROOT}" && mvn -o -q -pl service/hot dependency:build-classpath \
    -Dmdep.outputFile="${WORK}/hot-cp.txt" -Dmdep.includeScope=test ) || die "取 C 侧 classpath 失败"
[ -s "${WORK}/hot-cp.txt" ] || die "C 侧 classpath 文件为空：${WORK}/hot-cp.txt"
# ⚠️ **B 侧只读断言**（第 2 次）：构建过程也可能碰到 B 仓的工作区。
B_REPO_DIRTY2="$( cd "${B_REPO}" && git status --porcelain -- service/hot service/common )"
[ -n "${B_REPO_DIRTY2}" ] && die "B 仓 service/hot|service/common 有改动 ⇒ 本探针**不得**改真源"
say "   ✓ 两侧构建完成（B 两 jar + C 一 jar；**B 仓源码仍然干净**）"

# ══════════════════════════ 3. 编译探针 ═══════════════════════════════════════
step "3/13 编译探针（article-rpc 桩 + gRPC wire 探针）"
PROBE_OUT="$(mktemp -d "${WORK}/probe.XXXXXX")" || die "建探针临时目录失败"
CP="$(cat "${WORK}/hot-cp.txt")"
javac -nowarn -cp "${CP}" -d "${PROBE_OUT}" \
  "${HERE}/article-rpc-stub/ArticleRpcStub.java" \
  "${HERE}/grpc-wire/HotWireProbe.java" \
  || die "探针编译失败"
PROBE_CP="${PROBE_OUT}:${CP}"
say "   ✓ 探针编译完成（桩 + wire）"

# ══════════════════════════ 4. Nacos 命名空间 + 桩注册 ═════════════════════════
step "4/13 Nacos 专属命名空间 + article-rpc 桩注册（**两个组**）"
# ⚠️ **专属命名空间**：避免与其它 lane（public / probe-b-smoke / probe-c-smoke）同名串扰。
curl -s -m 10 -X POST "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/namespaces" \
  --data-urlencode "customNamespaceId=${NACOS_NAMESPACE}" \
  --data-urlencode "namespaceName=${NACOS_NAMESPACE}" \
  --data-urlencode "namespaceDesc=hot parity probe" >/dev/null 2>&1
NS_LIST="$(curl -s -m 10 "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/namespaces" 2>/dev/null)"
case "${NS_LIST}" in
  *"\"namespace\":\"${NACOS_NAMESPACE}\""*) : ;;
  *) die "Nacos 命名空间 ${NACOS_NAMESPACE} 没建出来" ;;
esac
say "   ✓ 命名空间 ${NACOS_NAMESPACE} 就绪"

# ⚠️ **先腾端口，再起桩** —— `free_probe_ports` 会 kill -9 占用端口的进程
#    ⇒ **必须在桩启动之前调用**（否则会把自己的桩踢掉 ⇒ 补全整片 skip ⇒ 假绿）。
free_probe_ports
# ⚠️ **桩必须先起、且先注册** ⇒ devh 的 `@GrpcClient("article-rpc")` 在 **Bean 初始化期**
#    解析实例 ⇒ 解析不到 ⇒ 上下文起不来。
( cd "${WORK}" && exec java -cp "${PROBE_CP}" com.rag.hot.parity.ArticleRpcStub "${STUB_PORT}" \
    >"${WORK}/stub.log" 2>&1 ) &
PIDS+=($!)
disown %% 2>/dev/null
for _ in $(seq 1 40); do host_port_open "${STUB_PORT}" && break; sleep 1; done
host_port_open "${STUB_PORT}" || die "article-rpc 桩没监听 :${STUB_PORT}（见 ${WORK}/stub.log）"

nacos_deregister_dead article-rpc "${B_GROUP}"
nacos_deregister_dead article-rpc "${C_GROUP}"
nacos_register article-rpc "${B_GROUP}" 127.0.0.1 "${STUB_PORT}"
nacos_assert_instance article-rpc "${B_GROUP}" "${STUB_PORT}" \
  || die "article-rpc 桩没出现在 Nacos[${B_GROUP}] 实例列表里"
nacos_keepalive article-rpc "${B_GROUP}" 127.0.0.1 "${STUB_PORT}" &
KEEPALIVE_PIDS+=($!)
disown %% 2>/dev/null   # ⚠️ **必须 disown**：否则收尾 kill 时 bash 会打印 "Terminated" 噪声
nacos_register article-rpc "${C_GROUP}" 127.0.0.1 "${STUB_PORT}"
nacos_assert_instance article-rpc "${C_GROUP}" "${STUB_PORT}" \
  || die "article-rpc 桩没出现在 Nacos[${C_GROUP}] 实例列表里"
nacos_keepalive article-rpc "${C_GROUP}" 127.0.0.1 "${STUB_PORT}" &
KEEPALIVE_PIDS+=($!)
disown %% 2>/dev/null
say "   ✓ article-rpc 桩已注册进 Nacos[${B_GROUP}] 与 [${C_GROUP}]（同一进程 · 127.0.0.1:${STUB_PORT}）"

# ══════════════════════════ 5. 起 B 侧 hot-rpc ════════════════════════════════
step "5/13 起 B 侧 hot-rpc（gRPC :${B_RPC_PORT}）"
# ⚠️ **B 侧的 Redis 必须指向 B 侧容器** ⇒ 两侧的 `hot:articles` 物理隔离。
# ⚠️ **B 侧不传 NACOS_GROUP** ⇒ 它的 discovery group = DEFAULT_GROUP
#    ⇒ 与 hot-rpc 的**硬编码注册组**天然对上（见文件头「Nacos 分组隔离」）。
( cd "${WORK}" && exec env \
  REDIS_HOST=127.0.0.1 REDIS_PORT="${B_REDIS_PORT}" REDIS_PASSWORD= \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE="${NACOS_NAMESPACE}" \
  HOT_RPC_GRPC_PORT="${B_RPC_PORT}" HOT_RPC_MANAGEMENT_PORT="${B_RPC_MGMT_PORT}" \
  KAFKA_BROKERS="127.0.0.1:${B_KAFKA_PORT}" \
  SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=earliest \
  ${KAFKA_LISTENER_ENV} \
  OTEL_EXPORTER_OTLP_ENDPOINT="127.0.0.1:${JAEGER_PORT}" \
  java -jar "${B_RPC_JAR}" >"${WORK}/b-rpc.log" 2>&1 ) &
PIDS+=($!)
disown %% 2>/dev/null
for _ in $(seq 1 120); do host_port_open "${B_RPC_PORT}" && break; sleep 1; done
host_port_open "${B_RPC_PORT}" || die "B 侧 hot-rpc 没监听 :${B_RPC_PORT}（见 ${WORK}/b-rpc.log）"
nacos_assert_instance hot-rpc "${B_GROUP}" "${B_RPC_PORT}" \
  || die "B 侧 hot-rpc 没出现在 Nacos[${B_GROUP}] 实例列表里（见 ${WORK}/b-rpc.log）"
say "   ✓ B 侧 hot-rpc 已监听 :${B_RPC_PORT} 且已自注册进 Nacos[${B_GROUP}]"

# ══════════════════════════ 6. 起 B 侧 hot-api ════════════════════════════════
step "6/13 起 B 侧 hot-api（HTTP :${B_API_PORT}）"
# ⚠️ **必须先起 hot-rpc 再起 hot-api**：hot-api 的 `grpc.client.hot-rpc.address`
#    = `discovery:///hot-rpc` ⇒ 下游没就绪时首请求会拿到 5001。
( cd "${WORK}" && exec env \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE="${NACOS_NAMESPACE}" \
  HOT_API_PORT="${B_API_PORT}" \
  OTEL_EXPORTER_OTLP_ENDPOINT="127.0.0.1:${JAEGER_PORT}" \
  java -jar "${B_API_JAR}" >"${WORK}/b-api.log" 2>&1 ) &
PIDS+=($!)
disown %% 2>/dev/null
for _ in $(seq 1 120); do host_port_open "${B_API_PORT}" && break; sleep 1; done
host_port_open "${B_API_PORT}" || die "B 侧 hot-api 没监听 :${B_API_PORT}（见 ${WORK}/b-api.log）"
say "   ✓ B 侧 hot-api 已监听 :${B_API_PORT}"

# ══════════════════════════ 7. 起 C 侧 hot-service ═══════════════════════════
step "7/13 起 C 侧 hot-service（HTTP :${C_HTTP_PORT} · gRPC :${C_GRPC_PORT}）"
( cd "${WORK}" && exec env \
  REDIS_HOST=127.0.0.1 REDIS_PORT="${C_REDIS_PORT}" REDIS_PASSWORD= \
  POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${C_PG_PORT}" POSTGRES_DB=first_db \
  POSTGRES_USER=admin POSTGRES_PASSWORD=sea \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" \
  NACOS_NAMESPACE="${NACOS_NAMESPACE}" NACOS_GROUP="${C_GROUP}" \
  HOT_HTTP_PORT="${C_HTTP_PORT}" HOT_GRPC_PORT="${C_GRPC_PORT}" \
  KAFKA_BROKERS="127.0.0.1:${C_KAFKA_PORT}" \
  SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=earliest \
  ${KAFKA_LISTENER_ENV} \
  OTEL_ADDR=127.0.0.1 OTLP_PORT="${JAEGER_PORT}" \
  java -jar "${C_JAR}" >"${WORK}/c-service.log" 2>&1 ) &
PIDS+=($!)
disown %% 2>/dev/null
for _ in $(seq 1 150); do host_port_open "${C_HTTP_PORT}" && break; sleep 1; done
host_port_open "${C_HTTP_PORT}" || die "C 侧 hot-service 没监听 :${C_HTTP_PORT}（见 ${WORK}/c-service.log）"
for _ in $(seq 1 60); do host_port_open "${C_GRPC_PORT}" && break; sleep 1; done
host_port_open "${C_GRPC_PORT}" || die "C 侧 hot-service 的 gRPC 没监听 :${C_GRPC_PORT}（见 ${WORK}/c-service.log）"
nacos_assert_instance hot-rpc "${C_GROUP}" "${C_GRPC_PORT}" \
  || die "C 侧 hot-rpc 没出现在 Nacos[${C_GROUP}] 实例列表里（见 ${WORK}/c-service.log）"
say "   ✓ C 侧 hot-service 已监听 HTTP :${C_HTTP_PORT} · gRPC :${C_GRPC_PORT}，已注册进 Nacos[${C_GROUP}]"

# ══════════════════════════ 8. 就绪闸门 ═══════════════════════════════════════
# ⚠️ **就绪闸门必须等「业务码是 200」的响应**：
#    「端口已监听」≠「下游 hot-rpc / article-rpc 已就绪」⇒ 首请求会拿到 5001。
# ⚠️ **判据看 body 里的业务码**，不是看 HTTP 状态码 —— 本域的失败恒 HTTP 200 + 信封。
body_text_of() {  # $1 = "HTTP|status|ctype|hex" ⇒ 打印 body 明文
  printf '%s' "${1##*|}" | xxd -r -p 2>/dev/null
}
wait_ready() {   # $1 = 端口  $2 = 标签
  local p="$1" tag="$2" raw txt
  for _ in $(seq 1 60); do
    raw="$(python3 "${HERE}/http_probe.py" "${p}" GET "/hot/v1/articles" 2>/dev/null)"
    txt="$(body_text_of "${raw}")"
    case "${txt}" in
      *'"code":200'*) say "   ✓ ${tag} 就绪（首个业务码 200 的响应已到）"; return 0 ;;
      *) sleep 2 ;;
    esac
  done
  say "   ❌ ${tag} 轮询 60 次仍拿不到业务码 200 的响应 ⇒ 不进请求矩阵（最后读到：${txt:0:120}）"
  return 1
}
wait_ready "${B_API_PORT}"  "B 侧" || die "B 侧没就绪（见上面的日志）"
wait_ready "${C_HTTP_PORT}" "C 侧" || die "C 侧没就绪（见上面的日志）"

# ══════════════════════════ 9. 阶段 M1 · HTTP 字节面 ══════════════════════════
step "9/13 阶段 M1 · HTTP 字节面（C :${C_HTTP_PORT} ↔ B :${B_API_PORT}）"
# ⚠️ **比对口径**：原始状态行（含 HTTP 版本与 reason phrase）+ Content-Type + body hex。
#    `Date` / `Server` 等易变头**刻意不比**（每请求都变 ⇒ 恒红）。
# ⚠️ **用裸 socket 而不是 curl**：curl 的 `-w '%{http_code}'` 只给状态码，
#    不给**状态行** ⇒ 而本域的 F-09 关注的正是「404 的状态行长什么样」。
http_case() {   # $1=用例名 $2=method $3=path $4=body（空串 = 无 body）
  local name="$1" method="$2" path="$3" body="$4"
  local c b
  if [ -n "${body}" ]; then
    c="$(python3 "${HERE}/http_probe.py" "${C_HTTP_PORT}" "${method}" "${path}" "${body}" 2>/dev/null)"
    b="$(python3 "${HERE}/http_probe.py" "${B_API_PORT}"  "${method}" "${path}" "${body}" 2>/dev/null)"
  else
    c="$(python3 "${HERE}/http_probe.py" "${C_HTTP_PORT}" "${method}" "${path}" 2>/dev/null)"
    b="$(python3 "${HERE}/http_probe.py" "${B_API_PORT}"  "${method}" "${path}" 2>/dev/null)"
  fi
  HTTP_C="${c}"; HTTP_B="${b}"
}

FAILED=0        # 真差异（计失败）
KNOWN=0         # 已知现象（不计失败，但必须显式上报）
SCEN_WARN=""
FAILED_CASES=""

report_http() {  # $1 = 用例名
  local name="$1" c="${HTTP_C}" b="${HTTP_B}" brief
  if [ -z "${c}" ] || [ -z "${b}" ]; then
    FAILED=$((FAILED+1)); FAILED_CASES="${FAILED_CASES} ${name}"
    say "   ❌ ${name}：探针没拿到响应（C='${c}' · B='${b}'）"
    return
  fi
  if [ "${c}" = "${b}" ]; then
    brief="$(printf '%s' "${c#HTTP|}" | cut -c1-200)"
    say "   ✅ ${name}  逐字节一致（${brief}）"
  else
    FAILED=$((FAILED+1)); FAILED_CASES="${FAILED_CASES} ${name}"
    say "   ❌ ${name}"
    say "      C : $(printf '%s' "${c}" | cut -c1-200)"
    say "      B : $(printf '%s' "${b}" | cut -c1-200)"
    say "      C body: $(body_text_of "${c}" | head -c 300)"
    say "      B body: $(body_text_of "${b}" | head -c 300)"
  fi
}

# ── 9a. **空热榜**（播种之前）────────────────────────────────────────────────
# ⚠️ **必须先跑这一条**：`hot:articles` 不存在 ⇒ hot-rpc 返回**空 items、不报错**
#    ⇒ HTTP 层应产出 `items:[]`（不是 null、不是 5001）。
http_case "empty_hot_set" GET "/hot/v1/articles" ""
report_http "empty_hot_set"

# ── 9b. 播种（**两侧逐条相同**）────────────────────────────────────────────────
say "   · 播种两侧 Redis 的 hot:articles（8 个成员 · 含 4 条 skip 分支触发器）"
seed_redis() {   # $1 = 容器名
  local c="$1" i member
  docker exec "${c}" redis-cli ZADD hot:articles \
    "${SEED_A1_SCORE}" "${SEED_A1}" \
    "${SEED_A2_SCORE}" "${SEED_A2}" \
    "${SEED_A3_SCORE}" "${SEED_A3}" \
    "${SEED_TAGS_SCORE}" "${SEED_TAGS}" \
    "${SEED_DRAFT_SCORE}" "${SEED_DRAFT}" \
    "${SEED_MISSING_SCORE}" "${SEED_MISSING}" \
    "${SEED_RPCERR_SCORE}" "${SEED_RPCERR}" \
    "${SEED_EMPTY_SCORE}" "${SEED_EMPTY}" >/dev/null || die "seed ZADD 失败（${c}）"
  # ⚠️ **填充成员单独一条 ZADD**：分数 1..25（低于空成员的 40 ⇒ 不打乱前 4 名）。
  i=0
  while [ "${i}" -lt "${SEED_FILL_COUNT}" ]; do
    member="$(printf 'probe-fill-%02d' "${i}")"
    docker exec "${c}" redis-cli ZADD hot:articles "$(( SEED_FILL_BASE + i ))" "${member}" >/dev/null \
      || die "seed ZADD 填充成员失败（${c} · ${member}）"
    i=$((i+1))
  done
}
seed_redis "${B_REDIS_CONTAINER}"
seed_redis "${C_REDIS_CONTAINER}"
# 🧪 **测试钩子（只给 mutate-parity.sh 用，正常跑不用）**：
#    PROBE_SEED_ASYMMETRIC=1 ⇒ **只给 B 侧多播一个成员**
#    ⇒ 用途 = 证明「探针真的在读两侧的数据，而不是在比两个空壳」
#    （两侧种子相同时，若探针读错了容器/读错了 key，读数也会相等 ⇒ 判据空转）。
if [ "${PROBE_SEED_ASYMMETRIC:-0}" = "1" ]; then
  docker exec "${B_REDIS_CONTAINER}" redis-cli ZADD hot:articles 999999 "probe-asymmetric-only-in-b" >/dev/null \
    || die "seed 不对称失败（B 侧）"
  say "   🧪 测试钩子：只给 B 侧多播一个成员（PROBE_SEED_ASYMMETRIC=1）⇒ 探针**必须**报红"
fi
B_SEED="$(docker exec "${B_REDIS_CONTAINER}" redis-cli ZRANGE hot:articles 0 -1 WITHSCORES | tr -d '\r\n')"
C_SEED="$(docker exec "${C_REDIS_CONTAINER}" redis-cli ZRANGE hot:articles 0 -1 WITHSCORES | tr -d '\r\n')"
if [ "${PROBE_SEED_ASYMMETRIC:-0}" = "1" ]; then
  [ "${B_SEED}" != "${C_SEED}" ] || die "测试钩子没生效（两侧种子竟然相同）"
else
  [ "${B_SEED}" = "${C_SEED}" ] || die "两侧播种结果不一致（B='${B_SEED}' · C='${C_SEED}'）⇒ 播种本身有问题"
fi
say "   ✓ 两侧 hot:articles 播种一致（${#B_SEED} 字节）"

# ── 9b-1. 🔴 **数据面闸门（防「探针空转」的假绿）** ──────────────────────────────
# ⚠️ **为什么必须有这道闸门**（2026-11-04 实测踩到）：
#    两侧都返回 `items:[]` 时，比对**必然**相等 ⇒ 那是**假绿**，不是「一致」。
#    成因：article-rpc 桩被探针自己的 `free_probe_ports` 踢掉 ⇒ 两侧的补全**整片 skip**
#    ⇒ 热榜恒空 ⇒ **18 条 HTTP 用例全在比两个空壳**（而 wire 面读的是 ZSet ⇒ 仍有数据
#    ⇒ 更容易骗人）。
# ⇒ ⇒ 这里断言「**返回的 total ≥ 期望存活条数**」
#    （种子共 N 条 − 4 条 skip 分支触发器）⇒ 补全必须**真的成功**。
#    ⇒ 桩不可达 ⇒ total=0 ⇒ **直接 die**，而不是「全绿」。
# ⚠️ **判据用「≥」而不是「==」**：探针钩子 `PROBE_SEED_ASYMMETRIC=1` 会**故意**只给
#    一侧多播一个成员 ⇒ 那时两侧的 total **本就不同** ⇒ 用「==」会把「刻意的不对称」
#    误判成「数据面没跑起来」⇒ ⇒ 闸门只管「数据面在不在」，**不管两侧一不一样**。
SEED_TOTAL=$(( 8 + SEED_FILL_COUNT ))
EXPECT_SURVIVORS=$(( SEED_TOTAL - 4 ))
assert_data_plane() {   # $1 = 端口  $2 = 标签
  local raw txt total
  raw="$(python3 "${HERE}/http_probe.py" "$1" GET "/hot/v1/articles" 2>/dev/null)"
  txt="$(body_text_of "${raw}")"
  total="$(printf '%s' "${txt}" | grep -o '"total":[0-9]\+' | head -1 | cut -d: -f2)"
  case "${total}" in ''|*[!0-9]*) total=0 ;; esac
  if [ "${total}" -lt "${EXPECT_SURVIVORS}" ]; then
    die "${2} 侧**数据面没跑起来**（total=${total} · 期望 ≥ ${EXPECT_SURVIVORS} = ${SEED_TOTAL} 条种子 − 4 条 skip 分支）
     ⇒ 补全被整片 skip（article-rpc 桩不可达？）⇒ **此时的「两侧一致」是假绿**（两个空壳相等）
     ⇒ 先修数据面，再看比对结果。（原始响应：$(printf '%s' "${txt}" | head -c 200)）"
  fi
  say "   ✓ ${2} 侧数据面就绪（total=${total} ≥ ${EXPECT_SURVIVORS} = ${SEED_TOTAL} 条种子 − 4 条 skip 分支）⇒ 比对的是**真数据**"
}
assert_data_plane "${C_HTTP_PORT}" "C 侧"
assert_data_plane "${B_API_PORT}"  "B 侧"

# ── 9c. 请求矩阵 ─────────────────────────────────────────────────────────────
# ⚠️ **顺序敏感**：全部是**只读**用例 ⇒ 顺序无关；但「空榜」必须在播种前。
http_case "default"           GET  "/hot/v1/articles" ""
report_http "default"
http_case "page1_size2"       GET  "/hot/v1/articles?page=1&page_size=2" ""
report_http "page1_size2"
http_case "page2_size2"       GET  "/hot/v1/articles?page=2&page_size=2" ""
report_http "page2_size2"
http_case "page3_size2"        GET  "/hot/v1/articles?page=3&page_size=2" ""
report_http "page3_size2"
http_case "page20_out_of_range" GET "/hot/v1/articles?page=20&page_size=2" ""
report_http "page20_out_of_range"
http_case "page_zero"         GET  "/hot/v1/articles?page=0" ""
report_http "page_zero"
http_case "page_negative"     GET  "/hot/v1/articles?page=-1" ""
report_http "page_negative"
http_case "page_size_zero"    GET  "/hot/v1/articles?page_size=0" ""
report_http "page_size_zero"
http_case "page_size_over_max" GET "/hot/v1/articles?page_size=999" ""
report_http "page_size_over_max"
http_case "page_not_a_number"  GET  "/hot/v1/articles?page=abc" ""
report_http "page_not_a_number"
http_case "page_int32_overflow" GET "/hot/v1/articles?page=2147483648" ""
report_http "page_int32_overflow"
http_case "page_multi_value"   GET  "/hot/v1/articles?page=1&page=5" ""
report_http "page_multi_value"
http_case "page_array_notation" GET "/hot/v1/articles?page[]=2" ""
report_http "page_array_notation"
http_case "malformed_json_body" GET "/hot/v1/articles" "{"
report_http "malformed_json_body"
http_case "json_body_not_object" GET "/hot/v1/articles" "[1,2]"
report_http "json_body_not_object"
http_case "unknown_path"       GET  "/hot/v1/nope" ""
report_http "unknown_path"
http_case "method_not_allowed" POST "/hot/v1/articles" ""
report_http "method_not_allowed"

# ══════════════════════════ 10. 阶段 M2 · gRPC wire 字节面 ═════════════════════
step "10/13 阶段 M2 · gRPC wire 字节面（C :${C_GRPC_PORT} ↔ B :${B_RPC_PORT}）"
# ⚠️ **为什么单独做这一层**：HTTP 层的 JSON 字节一致 **推不出** gRPC wire 字节一致
#    （proto 编码、字段号、status description 全是另一套面）。
# ⚠️ **探针不用任何 pb 生成码**（手构 proto3 字节 ＋ byte[] marshaller）⇒ **同一个 jar 跑两侧**
#    ⇒ 比的是纯 wire 字节。⚠️ **没有 Go 锚** ⇒ 读作「**双侧一致**」，**不计入「三方一致」**。
WIRE_OUT="$(mktemp -d "${WORK}/wire.XXXXXX")" || die "建 wire 临时目录失败"
java -cp "${PROBE_CP}" com.rag.hot.parity.HotWireProbe 127.0.0.1 "${B_RPC_PORT}" "${WIRE_OUT}/b.txt" \
  || die "B 侧 wire 探针执行失败"
java -cp "${PROBE_CP}" com.rag.hot.parity.HotWireProbe 127.0.0.1 "${C_GRPC_PORT}" "${WIRE_OUT}/c.txt" \
  || die "C 侧 wire 探针执行失败"
WIRE_COUNT=0
while IFS= read -r bline; do
  case "${bline}" in CASE\|*) ;; *) continue ;; esac
  wname="${bline#CASE|}"; wname="${wname%%|*}"
  cline="$(grep -F "CASE|${wname}|" "${WIRE_OUT}/c.txt" | head -1)"
  WIRE_COUNT=$((WIRE_COUNT + 1))
  if [ -z "${cline}" ]; then
    FAILED=$((FAILED+1)); FAILED_CASES="${FAILED_CASES} wire:${wname}"
    say "   ❌ ${wname}  C 侧 wire 探针**没有这一条** ⇒ 两侧方法集不同"
    continue
  fi
  if [ "${bline}" = "${cline}" ]; then
    say "   ✅ ${wname}  wire 字节一致（C == B · ${#bline} 字节）"
  else
    FAILED=$((FAILED+1)); FAILED_CASES="${FAILED_CASES} wire:${wname}"
    say "   ❌ ${wname}  wire 字节不一致"
    say "      C : ${cline}"
    say "      B : ${bline}"
  fi
done < "${WIRE_OUT}/b.txt"
# ⚠️ **零命中是假信号**：必须确认真的跑到了用例（探针空跑 ⇒ 「一致」毫无意义）
if [ "${WIRE_COUNT}" -lt 9 ]; then
  FAILED=$((FAILED+1))
  say "   ❌ wire 探针只产出 ${WIRE_COUNT} 条（应 = 9）⇒ 探针没真跑"
else
  say "   ✓ wire 层共比对 ${WIRE_COUNT} 条（**双边**判定 · 无 Go 锚）"
fi

# ⚠️ **判据自检：top_k 的两条 clamp 边界必须「可分辨」**
#   若种子里的成员数 ≤ 20 ⇒ top_k∈{20,0,-1} 与 top_k∈{100,101} 返回**同一批**
#   ⇒ 响应字节完全相同 ⇒ 「k<=0 ⇒ 20」与「k>100 ⇒ 100」这两条契约**根本验不到**
#   ⇒ 那是**判据空洞**（不是「跑绿」）。
#   ⇒ 这里**显式断言两条边界的响应字节不同** ⇒ 种子退化了直接报错，而不是默默「全绿」。
wire_hex_of() {  # $1 = 输出文件  $2 = 用例名 ⇒ 打印该用例的响应 hex
  grep -F "CASE|${2}|" "$1" | head -1 | sed 's/^[^|]*|[^|]*|[^|]*|//'
}
W_TOPK_LOW="$(wire_hex_of "${WIRE_OUT}/b.txt" wire_topk_20)"
W_TOPK_HIGH="$(wire_hex_of "${WIRE_OUT}/b.txt" wire_topk_100)"
if [ -z "${W_TOPK_LOW}" ] || [ -z "${W_TOPK_HIGH}" ]; then
  FAILED=$((FAILED+1)); FAILED_CASES="${FAILED_CASES} wire:topk_boundary"
  say "   ❌ top_k 边界自检：没取到 top_k=20 / top_k=100 的响应字节"
elif [ "${W_TOPK_LOW}" = "${W_TOPK_HIGH}" ]; then
  FAILED=$((FAILED+1)); FAILED_CASES="${FAILED_CASES} wire:topk_boundary"
  say "   ❌ **top_k 边界不可分辨**：top_k=20 与 top_k=100 的响应字节**相同**"
  say "      ⇒ 种子成员数 ≤ 20 ⇒ 「k<=0⇒20」与「k>100⇒100」两条契约**验不到** ⇒ 判据空洞"
else
  say "   ✓ top_k 边界**可分辨**（top_k=20 与 top_k=100 的响应字节不同 ⇒ 两条 clamp 契约都被覆盖）"
fi

# ══════════════════════════ 11. 阶段 M3 · Redis 状态面 ═════════════════════════
step "11/13 阶段 M3 · Redis 状态面（hot:articles 成员 + 分数 + TTL）"
# ⚠️ **TTL 刻意单列、不进「逐字节」判据**：
#    合并出来的 `hot:articles` 带 `EXPIRE 3600` ⇒ TTL 是**剩余秒数**
#    ⇒ 两侧的合并 tick 差 1 秒 ⇒ TTL 就差 1 ⇒ 进逐字节判据必然假红。
#    ⇒ TTL 走「**容差比较**」（≤5 秒），其余（类型 / 基数 / 成员 / 分数）**逐字节**。
redis_state() {   # $1 = 容器名
  local c="$1"
  {
    printf 'TYPE=%s\n' "$(docker exec "${c}" redis-cli TYPE hot:articles | tr -d '\r\n')"
    printf 'CARD=%s\n' "$(docker exec "${c}" redis-cli ZCARD hot:articles | tr -d '\r\n')"
    printf 'ITEMS:\n'
    docker exec "${c}" redis-cli ZRANGE hot:articles 0 -1 WITHSCORES | tr -d '\r'
  }
}
redis_ttl() {   # $1 = 容器名
  docker exec "$1" redis-cli TTL hot:articles | tr -d '\r\n'
}
# ⚠️ **TTL 的判定口径（只判「设了 EXPIRE 且落在契约区间」，**不判两侧相等**）**：
#    合并出来的 key 的 TTL = `Interaction.TTL`（3600）**从「该侧自己那次合并」起算**
#    ⇒ 两侧的合并 tick 相差几秒 ⇒ **两侧 TTL 天生不相等**
#    ⇒ **相等比较 / 容差比较都会假红** ⇒ ⇒ 只断言「两侧都设了 TTL，且都在 (0, 3600] 区间内」。
ttl_in_contract_range() {   # $1 = TTL 值
  local t="$1"
  case "${t}" in ''|*[!0-9]*) return 1 ;; esac
  [ "${t}" -gt 0 ] && [ "${t}" -le 3600 ]
}
B_REDIS_STATE="$(redis_state "${B_REDIS_CONTAINER}")"
C_REDIS_STATE="$(redis_state "${C_REDIS_CONTAINER}")"
B_REDIS_TTL="$(redis_ttl "${B_REDIS_CONTAINER}")"
C_REDIS_TTL="$(redis_ttl "${C_REDIS_CONTAINER}")"
# ⚠️ **M3 的 TTL 只报告、不判定**：
#    此时的 `hot:articles` 是**手工播种**的 key（TTL = -1，两侧相同）；
#    但一旦某个合并 tick 真的写了它 ⇒ 两侧 TTL 各从**自己那次合并**起算 ⇒ 天生不等
#    ⇒ 在这一层判 TTL 相等 = **埋一颗必然的假红**。
#    ⇒ TTL 的**契约判定**放在 M4（合并后，断言「确实设了 EXPIRE 且落在 (0,3600]」）。
if [ "${B_REDIS_STATE}" = "${C_REDIS_STATE}" ]; then
  say "   ✅ Redis 状态面 逐字节一致（成员 + 分数 + 类型 + 基数；TTL B=${B_REDIS_TTL} · C=${C_REDIS_TTL}（**本层只报告不判定**））"
  say "      $(printf '%s' "${B_REDIS_STATE}" | tr '\n' ' ')"
else
  FAILED=$((FAILED+1)); FAILED_CASES="${FAILED_CASES} redis:hot:articles"
  say "   ❌ Redis 状态面 不一致"
  say "      B : $(printf '%s' "${B_REDIS_STATE}" | tr '\n' ' ')（TTL ${B_REDIS_TTL}）"
  say "      C : $(printf '%s' "${C_REDIS_STATE}" | tr '\n' ' ')（TTL ${C_REDIS_TTL}）"
fi

# ══════════════════════════ 12. 阶段 M4 · Kafka 三面 ══════════════════════════
if [ "${SKIP_KAFKA}" = "1" ]; then
  step "12/13 阶段 M4 · Kafka 三面（**已跳过** · SKIP_KAFKA=1）"
else
  step "12/13 阶段 M4 · Kafka 三面（发同款事件 → 两侧消费 → HeavyKeeper → 合并 → 比 hot:articles）"
  # ⚠️ **为什么只用 1 个 article_id**：
  #   HeavyKeeper 是**有损概率 sketch** —— 桶冲突时按 `decay^count` **概率**让位
  #   （B 侧 `HeavyKeeper.java:138` 的 `ThreadLocalRandom.current().nextDouble()`）
  #   ⇒ **多 key 场景下两侧的分数天生不可复现** ⇒ 字节对拍必然假红。
  #   ⇒ 本探针用**单 key** ⇒ 每个分区里该 key 的 5 行计数都只走「指纹匹配累加」分支
  #   ⇒ 分数 = 事件总条数（**确定性**）⇒ 字节可比。
  #   ⚠️ **这不是「把差异藏起来」**：多 key 的随机性是 **B 侧既有语义**
  #   （B 侧自己的 javadoc:31 就写着「随机分支不可断言精确值」）⇒ 本探针在 README 里写明。
  # ⚠️ **合并会 RENAME 覆盖 hot:articles** ⇒ M4 必须排在 M1/M2/M3 **之后**。
  EVENT_FILE="${WORK}/events.txt"
  : > "${EVENT_FILE}"
  i=0
  while [ "${i}" -lt "${KAFKA_EVENTS}" ]; do
    printf '{"article_id":"%s","type":"%s"}\n' "${KAFKA_ARTICLE_ID}" "${KAFKA_EVENT_TYPE}" >> "${EVENT_FILE}"
    i=$((i+1))
  done
  for c in "${B_KAFKA_CONTAINER}" "${C_KAFKA_CONTAINER}"; do
    docker exec -i "${c}" bash -c \
      "/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server ${KAFKA_INTERNAL_BOOTSTRAP} --topic article-hot-events" \
      < "${EVENT_FILE}" >/dev/null 2>&1 || die "生产事件失败（${c}）"
  done
  say "   ✓ 两侧各发布 ${KAFKA_EVENTS} 条同款事件（article_id=${KAFKA_ARTICLE_ID} · type=${KAFKA_EVENT_TYPE}）"

  # ⚠️ **轮询等「合并完成」而不是死等固定秒数**：
  #   链路 = 消费 → HeavyKeeper → （每 100 条或每 30s）flush 到 `hot:articles:p{i}`
  #        → 合并调度器（每 10s）读 p0..p2 → RENAME 覆盖 `hot:articles`
  #   ⇒ 合并 tick 必须**晚于**那次 flush ⇒ 固定等待会读到半成品 ⇒ **假红**。
  merged_score_of() {   # $1 = 容器名 ⇒ 打印该 article 的分数（没有 ⇒ 空串）
    docker exec "$1" redis-cli ZSCORE hot:articles "${KAFKA_ARTICLE_ID}" 2>/dev/null | tr -d '\r\n'
  }
  wait_merged() {      # $1 = 容器名  $2 = 标签
    local c="$1" tag="$2" s
    for _ in $(seq 1 120); do
      s="$(merged_score_of "${c}")"
      if [ "${s}" = "${KAFKA_EXPECT_SCORE}" ] || [ "${s}" = "${KAFKA_EXPECT_SCORE}.0" ]; then
        say "   ✓ ${tag} 已合并（hot:articles 的 ${KAFKA_ARTICLE_ID} 分数 = ${s}）"
        return 0
      fi
      sleep 2
    done
    say "   ⚠️ ${tag} 240s 内没合并到期望分数（最后读到 '${s}' · 期望 ${KAFKA_EXPECT_SCORE}）"
    return 1
  }
  wait_merged "${B_REDIS_CONTAINER}" "B 侧" || true
  wait_merged "${C_REDIS_CONTAINER}" "C 侧" || true
  B_MERGED="$(redis_state "${B_REDIS_CONTAINER}")"
  C_MERGED="$(redis_state "${C_REDIS_CONTAINER}")"
  B_MERGED_TTL="$(redis_ttl "${B_REDIS_CONTAINER}")"
  C_MERGED_TTL="$(redis_ttl "${C_REDIS_CONTAINER}")"
  if [ "${B_MERGED}" = "${C_MERGED}" ] \
     && ttl_in_contract_range "${B_MERGED_TTL}" && ttl_in_contract_range "${C_MERGED_TTL}"; then
    say "   ✅ M4 Redis 终态  两侧逐字节一致（TTL B=${B_MERGED_TTL} · C=${C_MERGED_TTL}，各自从本侧合并时刻起算 ⇒ **不相等是正常的**，只断言落在 (0,3600]）"
    say "      $(printf '%s' "${B_MERGED}" | tr '\n' ' ')"
  else
    FAILED=$((FAILED+1)); FAILED_CASES="${FAILED_CASES} kafka:hot:articles"
    say "   ❌ M4 Redis 终态 两侧不一致"
    say "      B : $(printf '%s' "${B_MERGED}" | tr '\n' ' ')（TTL ${B_MERGED_TTL}）"
    say "      C : $(printf '%s' "${C_MERGED}" | tr '\n' ' ')（TTL ${C_MERGED_TTL}）"
  fi
  # ⚠️ **合并后的 HTTP 面也要比**：Kafka → 热榜 → HTTP 是完整的业务闭环。
  http_case "post_kafka_hot_articles" GET "/hot/v1/articles" ""
  report_http "post_kafka_hot_articles"
  http_case "post_kafka_hot_score" GET "/hot/v1/articles?page=1&page_size=1" ""
  report_http "post_kafka_hot_score"
fi

# ══════════════════════════ 13. 收尾：服务**真正**停掉 + 端口零残留 ═══════════
# ⚠️ **必须先真正收尾（kill + 等），再查残留**（stop_stacks 幂等 ⇒ trap 再调一次无害）。
#    反过来（先查残留后收尾）⇒ 进程还活着 ⇒ **必然**报残留 ⇒ **恒红的假信号**。
step "13/13 收尾复核（服务真停 · 端口零残留）"
stop_stacks
# ⚠️ **必须先删容器、再查端口**：容器发布端口时是 **docker-proxy** 持有那个口
#    ⇒ `docker rm -f` 之后 proxy 还会 linger 几秒 ⇒ 不删容器就查 ⇒ **必然**报残留
#    ⇒ **恒红的假信号**（follow / like 域探针的同类教训）。
docker rm -f "${B_REDIS_CONTAINER}" >/dev/null 2>&1
docker rm -f "${C_REDIS_CONTAINER}" >/dev/null 2>&1
docker rm -f "${C_PG_CONTAINER}" >/dev/null 2>&1
docker rm -f "${B_KAFKA_CONTAINER}" >/dev/null 2>&1
docker rm -f "${C_KAFKA_CONTAINER}" >/dev/null 2>&1
LEFTOVER=0
for _ in $(seq 1 20); do
  LEFTOVER=0
  for p in "${B_API_PORT}" "${B_RPC_PORT}" "${B_RPC_MGMT_PORT}" "${C_HTTP_PORT}" "${C_GRPC_PORT}" \
           "${STUB_PORT}" "${B_REDIS_PORT}" "${C_REDIS_PORT}" "${C_PG_PORT}" \
           "${B_KAFKA_PORT}" "${C_KAFKA_PORT}"; do
    for pid in $(lsof -ti tcp:"${p}" 2>/dev/null); do
      say "   · 端口 :${p} 仍有残留（pid ${pid}）⇒ 等 docker-proxy 把口还回来"
      LEFTOVER=1
    done
  done
  [ "${LEFTOVER}" = "0" ] && break
  sleep 1
done
if [ "${LEFTOVER}" = "1" ]; then
  say ""
  say "❌ 收尾后本探针的端口仍有残留 ⇒ 探针污染了宿主环境（下一轮会撞车）"
  exit 2
fi
say "   ✓ 收尾复核：本探针的端口**零残留**"

# ══════════════════════════ 机器摘要 ═════════════════════════════════════════
echo
if [ -n "${SCEN_WARN}" ]; then
  say "⚠️ 场景轮的**已知现象**（不计失败，但如实记录）：${SCEN_WARN}"
fi
say "—— 探针摘要：❌ 真差异 ${FAILED} ｜ ⚠️ 已知现象 ${KNOWN} ——"
if [ "${FAILED}" = "0" ]; then
  say "✅ **双侧逐字节一致**（C 侧 == B 侧 · Boot 3.5.11 ↔ Boot 4.0.8）"
  say "   · 判定强度 = **E-4（双侧活体对拍）**；**无 Go 锚** ⇒ 不读作「三方一致」"
  exit 0
fi
say "❌ 存在字节差异（用例：${FAILED_CASES}）"
dump_logs
exit 1
