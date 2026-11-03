#!/usr/bin/env bash
#
# like 域 **双侧活体对拍**探针 —— **B 侧（RAG / Boot 4）↔ C 侧（RAG-V1 / Boot 3.5）**
#
# 被测两侧（**同时起、同时活、同请求、同字节**）：
#   · B 侧 = /Users/pr/Documents/java&golang/RAG/service/like/{like-api,like-rpc}（Boot 4.0.8）
#   · C 侧 = 本仓 service/like（Boot 3.5.11 · D1 合并进程：HTTP + gRPC 同进程）
#
# 第三方锚 = expected.txt —— **Go 真源冻结字节**（B 侧 2026-10-29 录的 9 条用例）
#   ⇒ 判定条件是 **C == B == 冻结的 Go 字节** 三方相等（期望值本身参与判定，不是摆设）。
#
# ⚠️ **为什么本探针不跑 Go 侧**（与 B 侧探针的唯一结构性差异）：
#   B 侧的 `tools/api-full-parity-probe/like/run.sh` 起的是 **Go 真源**（走 etcd）。
#   本机 **没有 Sea-etcd 容器**（Nacos / PG / Redis / Jaeger / Kafka 在跑，etcd 不在）
#   ⇒ Go 侧**无法活体** ⇒ 改用 B 侧已冻结的 `expected.txt`（Go 字节）作第三方锚。
#   ⇒ **三方等价的强度不降**：C == B == Go 字节，比 B 侧原探针的 Go == B == Go 字节等价。
#
# ⚠️ **B 侧只读**：本探针只在 B 仓跑 `mvn package`（产物落 `target/`，已被 .gitignore 排除）
#   ⇒ **不碰 B 侧任何源码 / 配置**（第 2 步会断言 B 仓 `git status` 干净）。
#
# 用法：
#   bash tools/difftest/like/run.sh
#   B_REPO=/path/to/RAG bash tools/difftest/like/run.sh
#   KEEP_WORK=1 bash tools/difftest/like/run.sh     # 保留临时目录与日志
#
# 退出码：0 = 三方逐字节一致 · 1 = 有真差异 · 2 = 环境问题 / 依赖缺失 / 真源漂移。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"

# ── 两侧仓路径 ─────────────────────────────────────────────────────────────────
B_REPO="${B_REPO:-/Users/pr/Documents/java&golang/RAG}"

# ── 宿主中间件端口（env 可覆盖；默认值 = 本机 Sea 栈实测的**发布口**）──────────
# ⚠️ **只能走发布口**：Docker Desktop(macOS) 的宿主**不可达容器 IP**（实测 172.19.0.x 全部超时）
#    ⇒ Nacos 里 `message-rpc` 注册的 172.19.0.9:8086 对本探针不可用
#    ⇒ 探针自己注册 **127.0.0.1** 形态的实例。
NACOS_PORT="${NACOS_PORT:-49848}"    # Sea-nacos   49848→8848
REDIS_PORT="${REDIS_PORT:-49380}"    # Sea-redis   49380→6379（**本探针不用**：两侧各起独立容器）
JAEGER_PORT="${JAEGER_PORT:-49317}"  # Sea-jaeger  49317→4317 (OTLP gRPC)
KAFKA_PORT="${KAFKA_PORT:-49992}"    # Sea-kafka   49992→9093（**只用来发消息**，不消费）
# ⚠️ **message-rpc 的宿主口**：本机 `Sea-message-rpc` 容器发布在 8086
#    ⇒ 探针把它注册成 **127.0.0.1:8086** 的 Nacos 实例（容器 IP 不可达 ⇒ 必须手工注册）。
MESSAGE_RPC_PORT="${MESSAGE_RPC_PORT:-8086}"

# ── 端口（**本探针专用段 18211-18219**；follow 占 18201-18209；Sea 栈占 49xxx/55xxx）──
B_API_PORT="${B_API_PORT:-18211}"            # B 侧 like-api（HTTP · B 侧 yaml 默认 18887）
B_RPC_PORT="${B_RPC_PORT:-18212}"            # B 侧 like-rpc（gRPC · B 侧 yaml 默认 8082）
B_RPC_MGMT_PORT="${B_RPC_MGMT_PORT:-18213}"  # B 侧 like-rpc 的 Actuator 载体（默认 9098）
C_HTTP_PORT="${C_HTTP_PORT:-18214}"          # C 侧 like-service（HTTP · C 侧 yaml 默认 8896）
C_GRPC_PORT="${C_GRPC_PORT:-18215}"          # C 侧 like-service（gRPC · C 侧 yaml 默认 9005）
B_PG_PORT="${B_PG_PORT:-18216}"              # B 侧 Postgres（like-rpc 的库）
C_PG_PORT="${C_PG_PORT:-18217}"              # C 侧 Postgres（like-service 的库）
B_REDIS_PORT="${B_REDIS_PORT:-18218}"        # B 侧 Redis（**必须与 C 侧物理隔离**，见下）
C_REDIS_PORT="${C_REDIS_PORT:-18219}"        # C 侧 Redis

# ── Nacos 分组隔离（🔴 关键，见 README「为什么必须分组」）──────────────────────
# ⚠️ **B 侧的分组是「半隔离」**，这是 B 侧代码的既成事实，探针必须照单全收：
#   · B 侧 like-rpc 的 `NacosRegistration` **硬编码 `DEFAULT_GROUP`**
#     （`service/like/like-rpc/.../bootstrap/NacosRegistration.java:87`）
#     ⇒ 它的 `like-rpc` 实例**永远**落在 DEFAULT_GROUP；
#   · B 侧 like-api 的 yml **没有 `group:` 键** ⇒ 它的 discovery group = DEFAULT_GROUP
#     ⇒ ⇒ 两者**天然对上**（探针不能改 B 侧任何文件 ⇒ 只能这么用）。
#   · B 侧 like-rpc 的**出向**客户端 `message-rpc` 的 group = `${NACOS_GROUP:DEFAULT_GROUP}`
#     ⇒ 探针给 B 侧传 `NACOS_GROUP=E5B` ⇒ message-rpc 的查找落在 E5B。
#   · C 侧的一切（Nacos 注册 + message-rpc 查找）都在 `E5C` ⇒ 与 B 侧**完全隔离**
#     ⇒ 同名服务不会互拨 ⇒ 对拍对象不会「静默地错了」。
B_GROUP="${B_GROUP:-E5B}"   # B 侧 like-rpc 的**客户端**查找组（message-rpc）
C_GROUP="${C_GROUP:-E5C}"   # C 侧 like-service 的注册组 + 客户端查找组

# ── 两侧各自的 Postgres / Redis（容器名带 $$ ⇒ 别的 lane 的 cleanup 删不到本轮的库）──
# 🔴 **Redis 必须两侧物理隔离**（不是「省一个容器」的取舍，是正确性）：
#   `likeaction` 是**写路径** ⇒ 共用一个 Redis 时，B 侧写完计数变 3、C 侧再写变 4
#   ⇒ 两侧响应**必然不同** ⇒ **假漂移**。B 侧探针（`tools/api-full-parity-probe/like/run.sh`）
#   早就为此把 Go / RAG 拆成两个 Redis 容器 ⇒ 本探针照抄。
B_PG_CONTAINER="${B_PG_CONTAINER:-e5like-b-pg-$$}"
B_REDIS_CONTAINER="${B_REDIS_CONTAINER:-e5like-b-redis-$$}"
C_PG_CONTAINER="${C_PG_CONTAINER:-e5like-c-pg-$$}"
C_REDIS_CONTAINER="${C_REDIS_CONTAINER:-e5like-c-redis-$$}"

# ── 验签密钥（**探针本地值**，两侧同一把 ⇒ 一个 token 两边都验得过）────────────
# ⚠️ **刻意不用任何仓里的历史明文密钥**：探针只需要「两侧验签口径一致」。
#    ≥32 字节 ⇒ 两侧的 `padToHmacKeySize` 都是 no-op。
JWT_SECRET="${JWT_SECRET:-e5-like-parity-probe-secret-0001}"
JWT_READ_USER="${JWT_READ_USER:-1001}"     # 读路径的 userId（= 种子行里的点赞者）
JWT_ACTION_USER="${JWT_ACTION_USER:-2002}" # likeaction 的 userId（种子行里的**作者**是 1002）
JWT_E60_USER="${JWT_E60_USER:-3003}"       # E60 双失败用的 userId
JWT_WIRE_USER="${JWT_WIRE_USER:-7001}"     # gRPC wire 写路径用的 userId（独立 target）

# ── 种子数据（**两侧同款**）──────────────────────────────────────────────────
TARGET_TYPE="article"
TARGET_ID="9007199254740994"     # 主 target（冻结锚的基准）
# 🔴 **wire 层必须用「专属 target」**（不是主 target）—— 这是一条**确定性**约束：
#   `likeaction` 往 ZSet 里写的 score = **游标 ID**（`(now << 22) | …`）
#   ⇒ 读路径反解出的 timestamp = **墙钟毫秒** ⇒ 两侧的 likeaction 发生在**不同时刻**
#   ⇒ 毫秒不同 ⇒ **wire 字节必然不同** ⇒ 与对拍无关的**假红**。
#   ⇒ wire 读路径用一个**只有种子成员**的专属 target（⇒ timestamp 恒为 413843）；
#   ⇒ wire 写路径再用一个**全新** target（⇒ like_count 恒为 1）。
WIRE_READ_TARGET_ID="9007199254740995"    # wire 读路径专用（已播种）
WIRE_WRITE_TARGET_ID="9007199254740996"   # wire 写路径专用（全新，不播种）
CONC_TARGET_ID="9007199254740997"         # 并发轮专用
SEED_USER="1001"
SEED_AUTHOR="1002"
# ⚠️ **ZSet 的 score 必须是「裸毫秒」，不是游标 ID**：
#   读路径把 score 反解成毫秒 = `score >> 22`（Go `getuserlikelistlogic.go:70` /
#   `gettargetlikerlistlogic.go:77`；C 侧 `LikeCursorIdGenerator#timestampOf` 同款）
#   ⇒ 1735787045000 >> 22 = **413843** ⇒ 正是冻结锚里那个数。
#   ⇒ 若改种成「游标 ID 形态的 score」，列表里的 timestamp 会变成 1735787045000 ⇒ 与锚不符。
SEED_TS="1735787045000"
SEED_LIKE_COUNT="2"
SEED_DISLIKE_COUNT="1"
SEED_TOTAL_LIKE="5"

WORK="$(mktemp -d)"
PIDS=()
C_API_PID=""          # 测试钩子 PROBE_STOP_C_AFTER 用
KEEPALIVE_PIDS=()
say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
dump_logs() {
  local f
  for f in b-rpc.log b-api.log c-service.log; do
    [ -f "${WORK:-/nonexistent}/${f}" ] || continue
    printf '\n---- %s（末 40 行）----\n' "${f}" >&2
    tail -40 "${WORK}/${f}" | sed 's/^/   | /' >&2
  done
}
die()  { printf '!! %s\n' "$*" >&2; dump_logs; exit 2; }

host_port_open() { nc -z -G 2 127.0.0.1 "$1" >/dev/null 2>&1; }

# ══════════════════════════ Nacos 实例管理（**分组感知**）══════════════════════
# ⚠️ **必须带 groupName**：C 侧把 `like-rpc` 注册进 `E5C`，B 侧注册进 `DEFAULT_GROUP`
#    ⇒ 不分组 ⇒ 两边同名 ⇒ 解析池里混进对面的实例 ⇒ **对拍对象静默地错了**。
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
  # ⚠️ **只清服务口，不清容器口**：容器口由 docker-proxy 持有 ⇒ 按端口无差别 kill
  #    会**误杀 Docker daemon 的代理**（B 侧探针的实测教训）⇒ 容器口走「换端口」而不是「杀进程」。
  for p in "${B_API_PORT}" "${B_RPC_PORT}" "${B_RPC_MGMT_PORT}" "${C_HTTP_PORT}" "${C_GRPC_PORT}"; do
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
  nacos_deregister message-rpc "${B_GROUP}" 127.0.0.1 "${MESSAGE_RPC_PORT}"
  nacos_deregister message-rpc "${C_GROUP}" 127.0.0.1 "${MESSAGE_RPC_PORT}"
  docker rm -f "${B_PG_CONTAINER}" >/dev/null 2>&1
  docker rm -f "${B_REDIS_CONTAINER}" >/dev/null 2>&1
  docker rm -f "${C_PG_CONTAINER}" >/dev/null 2>&1
  docker rm -f "${C_REDIS_CONTAINER}" >/dev/null 2>&1
  # ⚠️ **不删临时目录**：`rm -rf` 在本机会被回收站守卫拦下 ⇒ 留着由系统清理。
  if [ "${KEEP_WORK:-0}" = "1" ]; then
    say "   · KEEP_WORK=1 ⇒ 保留 ${WORK}"
  fi
}
trap cleanup EXIT

# ══════════════════════════ 0. 前置检查（缺 ⇒ exit 2，绝不静默降级）═════════════
step "0/13 前置检查"
for cmd in docker mvn java curl xxd python3 lsof nc unzip; do
  command -v "${cmd}" >/dev/null 2>&1 || die "缺命令：${cmd}"
done
[ -d "${B_REPO}/service/like/like-api" ] || die "找不到 B 侧仓：${B_REPO}/service/like（用 B_REPO=... 指定）"
[ -d "${C_ROOT}/service/like" ] || die "找不到 C 侧 like 模块：${C_ROOT}/service/like"
for pair in "Sea-nacos:${NACOS_PORT}" "Sea-jaeger:${JAEGER_PORT}" "Sea-kafka:${KAFKA_PORT}" "Sea-message-rpc:${MESSAGE_RPC_PORT}"; do
  c="${pair%%:*}"; p="${pair##*:}"
  docker ps --format '{{.Names}}' | grep -qx "${c}" || die "容器 ${c} 没在跑（宿主中间件缺失）"
  nc -z -G 2 127.0.0.1 "${p}" || die "宿主端口 ${p} 不通（${c} 的映射口）"
done
# ⚠️ **B 仓必须干净**：本探针只在 B 仓产出 target/（.gitignore 排除）⇒ 源码必须一个字节没动。
# ⚠️ **先抓进变量再判空**（不要 `| grep -q`）：`grep -q` 命中即退出 ⇒ `git` 收 SIGPIPE
#    ⇒ `pipefail` ⇒ 管道退出码变 141 ⇒ `&& die` 不会执行 ⇒ **守卫静默失效**。
B_REPO_DIRTY="$( cd "${B_REPO}" && git status --porcelain -- service/like )"
[ -n "${B_REPO_DIRTY}" ] && die "B 仓 service/like 有改动 ⇒ 本探针**不得**改真源"
say "   ✓ 命令齐 / 中间件在跑 / B 仓干净"

# ══════════════════════════ 1. 两侧各自的 Postgres + Redis ════════════════════
step "1/13 两侧 Postgres + Redis（各一个容器，互不污染）"
# ⚠️ **必须先清掉「上一轮遗留的同前缀容器」**：容器名带 `$$` ⇒ 每轮唯一
#    ⇒ 上一轮的容器**不会被本轮的 cleanup 删掉**（上一轮若被强杀 ⇒ cleanup 没跑）
#    ⇒ ⚠️ **Exited 的容器仍然占着发布端口**（docker-proxy 还在）⇒ `docker run -p` 报
#    `port is already allocated` ⇒ **与代码无关的假故障**。
purge_stale_my_containers() {
  local c
  for c in $(docker ps -aq --filter "name=e5like-" 2>/dev/null); do
    docker rm -f "${c}" >/dev/null 2>&1
    say "   · 已清理上一轮遗留的探针容器 ${c}"
  done
  sleep 3   # docker-proxy 释放监听口要一会儿
}
purge_stale_my_containers
# ⚠️ 就绪判据必须是「**宿主端口**通」：`docker exec psql` 走的是**容器内 socket**，
#    与 `-p` 发布**无关** ⇒ 容器活着 ≠ 宿主端口通。
start_pg() {   # $1 = 容器名  $2 = 宿主端口
  local c="$1" p="$2"
  docker rm -f "${c}" >/dev/null 2>&1
  docker run -d --name "${c}" -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=sea \
    -e POSTGRES_DB=first_db -p "${p}:5432" postgres:16-alpine >/dev/null 2>&1 || return 1
  for _ in $(seq 1 60); do
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
# ⚠️ **诊断信息必须走 stderr**：本函数的值是**命令替换**取回的
#    ⇒ `say`（stdout）会把端口号污染成「诊断文本 + 端口号」。
B_PG_CANDIDATES=(${B_PG_PORT:+${B_PG_PORT}} 18226)
C_PG_CANDIDATES=(${C_PG_PORT:+${C_PG_PORT}} 18227)
B_REDIS_CANDIDATES=(${B_REDIS_PORT:+${B_REDIS_PORT}} 18228)
C_REDIS_CANDIDATES=(${C_REDIS_PORT:+${C_REDIS_PORT}} 18229)
pick_port() {   # $1 = 标签  $2 = 容器名  $3 = 起法(pg|redis)  $4.. = 候选端口 ⇒ 成功时把端口打到 stdout
  local label="$1" c="$2" kind="$3"; shift 3
  local p
  for p in "$@"; do
    if [ "${kind}" = "pg" ]; then
      start_pg "${c}" "${p}"
    else
      start_redis "${c}" "${p}"
    fi
    if [ "$?" = "0" ]; then printf '%s' "${p}"; return 0; fi
    printf '   · :%s 起不来（容器内没就绪 / 宿主端口没通 / 端口被占）⇒ 换下一个\n' "${p}" >&2
    docker rm -f "${c}" >/dev/null 2>&1
  done
  return 1
}
B_PG_PORT="$(pick_port "B 侧 PG" "${B_PG_CONTAINER}" pg ${B_PG_CANDIDATES[@]+${B_PG_CANDIDATES[@]}})" \
  || die "B 侧 PG 起不来（候选端口：${B_PG_CANDIDATES[*]}）"
C_PG_PORT="$(pick_port "C 侧 PG" "${C_PG_CONTAINER}" pg ${C_PG_CANDIDATES[@]+${C_PG_CANDIDATES[@]}})" \
  || die "C 侧 PG 起不来（候选端口：${C_PG_CANDIDATES[*]}）"
B_REDIS_PORT="$(pick_port "B 侧 Redis" "${B_REDIS_CONTAINER}" redis ${B_REDIS_CANDIDATES[@]+${B_REDIS_CANDIDATES[@]}})" \
  || die "B 侧 Redis 起不来（候选端口：${B_REDIS_CANDIDATES[*]}）"
C_REDIS_PORT="$(pick_port "C 侧 Redis" "${C_REDIS_CONTAINER}" redis ${C_REDIS_CANDIDATES[@]+${C_REDIS_CANDIDATES[@]}})" \
  || die "C 侧 Redis 起不来（候选端口：${C_REDIS_CANDIDATES[*]}）"
say "   ✓ 两侧 Postgres + Redis 就绪（B PG :${B_PG_PORT} · B Redis :${B_REDIS_PORT} · C PG :${C_PG_PORT} · C Redis :${C_REDIS_PORT}）"

# ══════════════════════════ 2. 构建两侧 ═══════════════════════════════════════
step "2/13 构建两侧（离线；B 侧**只读**）"
( cd "${B_REPO}" && mvn -o -q -pl service/like/like-api,service/like/like-rpc -am package -DskipTests ) \
  || die "B 侧构建失败"
B_API_JAR="${B_REPO}/service/like/like-api/target/like-api-0.0.1-SNAPSHOT.jar"
B_RPC_JAR="${B_REPO}/service/like/like-rpc/target/like-rpc-0.0.1-SNAPSHOT.jar"
[ -f "${B_API_JAR}" ] && [ -f "${B_RPC_JAR}" ] || die "B 侧 jar 没产出"
( cd "${C_ROOT}" && mvn -o -q -pl service/like -am package -DskipTests ) || die "C 侧构建失败"
C_JAR="${C_ROOT}/service/like/target/like-service-1.0-SNAPSHOT.jar"
[ -f "${C_JAR}" ] || die "C 侧 jar 没产出：${C_JAR}"
# ⚠️ **顺带取 C 侧的运行时 classpath**：gRPC wire 探针要用它编译/运行
#    （wire 探针**只用 grpc 的原始字节 marshaller** ⇒ 不依赖任何 pb 生成码
#      ⇒ **同一个 jar 能跑两侧** ⇒ 比的是纯 wire 字节，不被「两侧 pb 包名不同」污染）。
( cd "${C_ROOT}" && mvn -o -q -pl service/like dependency:build-classpath \
    -Dmdep.outputFile="${WORK}/like-cp.txt" -Dmdep.includeScope=test ) || die "取 C 侧 classpath 失败"
[ -s "${WORK}/like-cp.txt" ] || die "C 侧 classpath 文件为空：${WORK}/like-cp.txt"
# ⚠️ **B 侧只读断言**：本探针只在 B 仓产出 target/（.gitignore 排除）⇒ 源码必须一个字节没动。
B_REPO_DIRTY2="$( cd "${B_REPO}" && git status --porcelain -- service/like service/common )"
[ -n "${B_REPO_DIRTY2}" ] && die "B 仓 service/like|service/common 有改动 ⇒ 本探针**不得**改真源"
say "   ✓ 两侧构建完成（B 两 jar + C 一 jar；**B 仓源码仍然干净**）"

# ── C 侧 fat jar 的 **grpc-inprocess 依赖**（已修 ⇒ 正常路径不走兜底）────────────
# 📌 **历史（已修 · 2026-11-03 · 主 lane 改 `service/like/pom.xml`）**：
#   C 侧曾把 `io.grpc:grpc-inprocess` 声明成 `test` scope
#   ⇒ Maven「最近者优先」压过 devh starter 的传递 compile 依赖
#   ⇒ **运行时 classpath 上整个 artifact 消失**（fat jar 的 `BOOT-INF/lib` 里也没有它）
#   ⇒ devh 的 `shadedNettyGrpcChannelFactory` 建 channel 时链接
#   `io.grpc.inprocess.InProcessChannelBuilder` ⇒ **NoClassDefFoundError** ⇒ **真 jar 起不来**。
#   （B 出处 = B 侧 `service/like/like-rpc/pom.xml:313-344`（白纸黑字写着「scope 必须是 compile」）
#     · follow 域 2026-11-02 已修同款 · 本仓证据 = `LikeGrpcNettyServerRealTest.java`）
#   ⇒ **教训**：测试跑在 test classpath 上 ⇒ 依赖本来就在 ⇒ **缺陷看不见**
#     ⇒ 「跑绿 ≠ 本来就绿」（AGENTS.md §3.3.3 的教科书形态）。
# ⚠️ **本分支只是保险**：正常路径下 `C_JAR_HAS_INPROCESS=1` ⇒ 直接 `java -jar` 起。
#    ⇒ **若这里命中 ⇒ 说明 `service/like/pom.xml` 的修法回退了** ⇒ 探针会显式告警。
GRPC_INPROCESS_JAR="$(tr ':' '\n' < "${WORK}/like-cp.txt" | grep '/grpc-inprocess-' | head -1)"
C_JAR_HAS_INPROCESS=0
# ⚠️ **不要用 `unzip -l | grep -q`**：`grep -q` 命中即退出 ⇒ `unzip` 收 SIGPIPE
#    ⇒ 本脚本开了 `pipefail` ⇒ 整条管道的退出码变成 141 ⇒ `&& C_JAR_HAS_INPROCESS=1` **不会执行**
#    ⇒ 会把「jar **有** grpc-inprocess」误判成「没有」（实测踩过）。
#    ⇒ 改成：先把清单抓进变量，再做**模式匹配**（不产生管道 ⇒ 无 SIGPIPE）。
C_JAR_LIB_LIST="$(unzip -l "${C_JAR}" 2>/dev/null)"
case "${C_JAR_LIB_LIST}" in
  *BOOT-INF/lib/grpc-inprocess*) C_JAR_HAS_INPROCESS=1 ;;
esac
if [ "${C_JAR_HAS_INPROCESS}" = "1" ]; then
  say "   ✓ C 侧 fat jar **自带** grpc-inprocess（compile scope · pom 已修）⇒ 直接用 -jar 起"
else
  [ -n "${GRPC_INPROCESS_JAR}" ] && [ -f "${GRPC_INPROCESS_JAR}" ] \
    || die "C 侧 fat jar 缺 grpc-inprocess，且本机找不到该 artifact ⇒ 无法兜底（环境问题）"
  say "   ⚠️ **C 侧 fat jar 缺 grpc-inprocess** ⇒ service/like/pom.xml 的 scope 修法**疑似回退**"
  say "      ⇒ 本探针用 PropertiesLauncher + -Dloader.path 兜住（行为面一字未改）⇒ **但这不该再发生**"
fi

# ══════════════════════════ 3. JWT（自签；两侧同一把 secret 验签）════════════════
step "3/13 签发 JWT（userId 必须是**数字**；两侧同一把 secret）"
mint() { python3 "${HERE}/mint-jwt.py" "${JWT_SECRET}" "$1"; }
TOKEN_READ="$(mint "${JWT_READ_USER}")"   || die "签发 JWT 失败"
TOKEN_ACTION="$(mint "${JWT_ACTION_USER}")" || die "签发 JWT 失败"
TOKEN_E60="$(mint "${JWT_E60_USER}")" || die "签发 JWT 失败"
TOKEN_WIRE="$(mint "${JWT_WIRE_USER}")" || die "签发 JWT 失败"
[ -n "${TOKEN_READ}" ] && [ -n "${TOKEN_ACTION}" ] && [ -n "${TOKEN_E60}" ] && [ -n "${TOKEN_WIRE}" ] \
  || die "JWT 签发失败"
say "   ✓ 4 枚 token 已签发（读=${JWT_READ_USER} · 写=${JWT_ACTION_USER} · E60=${JWT_E60_USER} · wire=${JWT_WIRE_USER}）"

# ══════════════════════════ 4. Nacos 分组注册 ═════════════════════════════════
step "4/13 Nacos 分组注册（message-rpc：127.0.0.1 形态 + ephemeral + 保活）"
# ⚠️ **Docker Desktop(macOS) 的宿主不可达容器 IP** ⇒ Sea-message-rpc 自注册的
#    172.19.0.9:8086 对本探针不可用 ⇒ 探针自己注册 **127.0.0.1:8086** 形态的实例。
# ⚠️ **必须两侧各一组**：B 侧的客户端在 E5B 找、C 侧的客户端在 E5C 找
#    ⇒ 共用一个组 ⇒ 两边会互拨（对拍对象就错了）。
nacos_deregister_dead message-rpc "${B_GROUP}"
nacos_deregister_dead message-rpc "${C_GROUP}"
nacos_register message-rpc "${B_GROUP}" 127.0.0.1 "${MESSAGE_RPC_PORT}"
nacos_assert_instance message-rpc "${B_GROUP}" "${MESSAGE_RPC_PORT}" \
  || die "message-rpc 没出现在 Nacos[${B_GROUP}] 实例列表里"
nacos_keepalive message-rpc "${B_GROUP}" 127.0.0.1 "${MESSAGE_RPC_PORT}" &
KEEPALIVE_PIDS+=($!)
disown %% 2>/dev/null   # ⚠️ **必须 disown**：否则收尾 kill 时 bash 会打印 "Terminated" 噪声，污染输出
nacos_register message-rpc "${C_GROUP}" 127.0.0.1 "${MESSAGE_RPC_PORT}"
nacos_assert_instance message-rpc "${C_GROUP}" "${MESSAGE_RPC_PORT}" \
  || die "message-rpc 没出现在 Nacos[${C_GROUP}] 实例列表里"
nacos_keepalive message-rpc "${C_GROUP}" 127.0.0.1 "${MESSAGE_RPC_PORT}" &
KEEPALIVE_PIDS+=($!)
disown %% 2>/dev/null
say "   ✓ message-rpc 已登记进 Nacos[${B_GROUP}] 与 [${C_GROUP}]（127.0.0.1:${MESSAGE_RPC_PORT} · 含保活循环）"

# ══════════════════════════ 5. 起两侧 ═════════════════════════════════════════
step "5/13 起 B 侧 like-rpc / like-api 与 C 侧 like-service"
# ── 5a. B 侧 like-rpc ───────────────────────────────────────────────────────
# ⚠️ **Kafka listener 必须关掉**（`SPRING_KAFKA_LISTENER_AUTO_STARTUP=false`）：
#    两侧都消费 `like_promotion_topic`（宿主 Kafka 是**共用**的 Sea-kafka）
#    ⇒ 不关 ⇒ 一侧消费到另一侧推的消息 ⇒ 两侧的库**串味** ⇒ 假漂移。
#    ⇒ 被排除的是「**MQ 消费路径**」这一段（见 README「覆盖边界」），**两侧对称**。
( cd "${WORK}" && exec env \
  POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${B_PG_PORT}" POSTGRES_DB=first_db \
  POSTGRES_USER=admin POSTGRES_PASSWORD=sea \
  REDIS_HOST=127.0.0.1 REDIS_PORT="${B_REDIS_PORT}" REDIS_PASSWORD= \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${B_GROUP}" \
  LIKE_RPC_PORT="${B_RPC_PORT}" LIKE_RPC_MANAGEMENT_PORT="${B_RPC_MGMT_PORT}" \
  KAFKA_BROKERS="127.0.0.1:${KAFKA_PORT}" \
  SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
  OTEL_EXPORTER_OTLP_ENDPOINT="127.0.0.1:${JAEGER_PORT}" \
  java -jar "${B_RPC_JAR}" >"${WORK}/b-rpc.log" 2>&1 ) &
PIDS+=($!)
disown %% 2>/dev/null
for _ in $(seq 1 90); do host_port_open "${B_RPC_PORT}" && break; sleep 1; done
host_port_open "${B_RPC_PORT}" || die "B 侧 like-rpc 没监听 :${B_RPC_PORT}（见 ${WORK}/b-rpc.log）"
# ⚠️ **B 侧 like-rpc 的注册组是硬编码的 DEFAULT_GROUP**（`NacosRegistration.java:87`）
#    ⇒ B 侧 like-api（yml 无 group 键 ⇒ DEFAULT_GROUP）能解析到它 ⇒ **两侧天然对上**
nacos_assert_instance like-rpc "DEFAULT_GROUP" "${B_RPC_PORT}" \
  || die "B 侧 like-rpc 没出现在 Nacos[DEFAULT_GROUP] 实例列表里（见 ${WORK}/b-rpc.log）"
say "   ✓ B 侧 like-rpc 已监听 :${B_RPC_PORT} 且已自注册进 Nacos[DEFAULT_GROUP]"

# ── 5b. B 侧 like-api ───────────────────────────────────────────────────────
# ⚠️ **必须先起 like-rpc 再起 like-api**：like-api 的 `grpc.client.like-rpc.address`
#    = `discovery:///like-rpc` ⇒ 下游没就绪时首请求会拿到 1015。
#    （B 侧 like-api 的 yml **没有** `immediate-connect-timeout` ⇒ 不会 fail-fast
#      ⇒ 启动期不会挂，但就绪闸门必须等它。）
( cd "${WORK}" && exec env \
  REDIS_HOST=127.0.0.1 REDIS_PORT="${B_REDIS_PORT}" REDIS_PASSWORD= \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" \
  LIKE_API_PORT="${B_API_PORT}" USER_JWT_SECRET="${JWT_SECRET}" \
  OTEL_EXPORTER_OTLP_ENDPOINT="127.0.0.1:${JAEGER_PORT}" \
  java -jar "${B_API_JAR}" >"${WORK}/b-api.log" 2>&1 ) &
PIDS+=($!)
disown %% 2>/dev/null
for _ in $(seq 1 90); do host_port_open "${B_API_PORT}" && break; sleep 1; done
host_port_open "${B_API_PORT}" || die "B 侧 like-api 没监听 :${B_API_PORT}（见 ${WORK}/b-api.log）"
say "   ✓ B 侧 like-api 已监听 :${B_API_PORT}"

# ── 5c. C 侧 like-service（D1 合并进程：HTTP + gRPC）─────────────────────────
C_STARTED=0
for attempt in 1 2 3; do
  if [ "${C_JAR_HAS_INPROCESS}" = "1" ]; then
    ( cd "${WORK}" && exec env \
      POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${C_PG_PORT}" POSTGRES_DB=first_db \
      POSTGRES_USER=admin POSTGRES_PASSWORD=sea \
      REDIS_HOST=127.0.0.1 REDIS_PORT="${C_REDIS_PORT}" REDIS_PASSWORD= \
      NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${C_GROUP}" \
      LIKE_HTTP_PORT="${C_HTTP_PORT}" LIKE_GRPC_PORT="${C_GRPC_PORT}" \
      USER_JWT_SECRET="${JWT_SECRET}" USER_JWT_EXPIRE=31536000 \
      KAFKA_BROKERS="127.0.0.1:${KAFKA_PORT}" \
      SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
      OTEL_ADDR=127.0.0.1 OTLP_PORT="${JAEGER_PORT}" \
      java -jar "${C_JAR}" >"${WORK}/c-service.log" 2>&1 ) &
  else
    ( cd "${WORK}" && exec env \
      POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${C_PG_PORT}" POSTGRES_DB=first_db \
      POSTGRES_USER=admin POSTGRES_PASSWORD=sea \
      REDIS_HOST=127.0.0.1 REDIS_PORT="${C_REDIS_PORT}" REDIS_PASSWORD= \
      NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${C_GROUP}" \
      LIKE_HTTP_PORT="${C_HTTP_PORT}" LIKE_GRPC_PORT="${C_GRPC_PORT}" \
      USER_JWT_SECRET="${JWT_SECRET}" USER_JWT_EXPIRE=31536000 \
      KAFKA_BROKERS="127.0.0.1:${KAFKA_PORT}" \
      SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
      OTEL_ADDR=127.0.0.1 OTLP_PORT="${JAEGER_PORT}" \
      java -Dloader.path="${GRPC_INPROCESS_JAR}" -cp "${C_JAR}" \
        org.springframework.boot.loader.launch.PropertiesLauncher >"${WORK}/c-service.log" 2>&1 ) &
  fi
  c_pid=$!
  C_API_PID="${c_pid}"
  PIDS+=($!)
  disown %% 2>/dev/null
  for _ in $(seq 1 90); do
    host_port_open "${C_HTTP_PORT}" && { C_STARTED=1; break; }
    kill -0 "${c_pid}" 2>/dev/null || break
    sleep 1
  done
  if [ "${C_STARTED}" = "1" ]; then
    say "   ✓ C 侧 like-service 第 ${attempt} 次启动成功（HTTP :${C_HTTP_PORT} · gRPC :${C_GRPC_PORT}）"
    break
  fi
  say "   · C 侧 like-service 第 ${attempt} 次启动没起来（见 ${WORK}/c-service.log）⇒ 重试"
  kill -9 "${c_pid}" 2>/dev/null
  sleep 3
done
[ "${C_STARTED}" = "1" ] || die "C 侧 like-service 三次都没起来（见 ${WORK}/c-service.log）"
# ⚠️ **C 侧必须注册在自己的组里**（`wiring/NacosRegistration` · group = NACOS_GROUP）
nacos_assert_instance like-rpc "${C_GROUP}" "${C_GRPC_PORT}" \
  || die "C 侧 like-rpc 没出现在 Nacos[${C_GROUP}] 实例列表里（见 ${WORK}/c-service.log）"
say "   ✓ C 侧 like-rpc 已注册进 Nacos[${C_GROUP}] ⇒ 与 B 侧（DEFAULT_GROUP）**分组隔离**"

# ══════════════════════════ 6. 两侧播种 ═══════════════════════════════════════
step "6/13 两侧播种同一批数据（Redis 6 个键 + PG 1 行）"
# ⚠️ **必须等 Flyway 建完表**：like_record 是**启动时**建的（两侧都是）
#    ⇒ 播种早于建表 ⇒ 报「relation does not exist」。
wait_table() {   # $1 = 容器名
  local c="$1" t=""
  for _ in $(seq 1 60); do
    t="$(docker exec "${c}" psql -U admin -d first_db -tAc \
         "SELECT (to_regclass('public.like_record') IS NOT NULL)::text" 2>/dev/null | tr -d ' \n\r')"
    [ "${t}" = "true" ] && return 0
    sleep 1
  done
  return 1
}
wait_table "${B_PG_CONTAINER}" || die "B 侧 like_record 表没建出来（Flyway 没跑完？）"
wait_table "${C_PG_CONTAINER}" || die "C 侧 like_record 表没建出来（Flyway 没跑完？）"

seed_redis() {   # $1 = 容器名
  local c="$1"
  docker exec "${c}" redis-cli HSET "like_count:${TARGET_TYPE}"      "${TARGET_ID}" "${SEED_LIKE_COUNT}"    >/dev/null || die "seed HSET like_count 失败"
  docker exec "${c}" redis-cli HSET "dislike_count:${TARGET_TYPE}"   "${TARGET_ID}" "${SEED_DISLIKE_COUNT}" >/dev/null || die "seed HSET dislike_count 失败"
  docker exec "${c}" redis-cli HSET "like_state:${TARGET_TYPE}:${TARGET_ID}" "${SEED_USER}" 1 >/dev/null || die "seed HSET like_state 失败"
  docker exec "${c}" redis-cli ZADD "target_liker_list:${TARGET_TYPE}:${TARGET_ID}" "${SEED_TS}" "${SEED_USER}" >/dev/null || die "seed ZADD target_liker_list 失败"
  docker exec "${c}" redis-cli ZADD "user_like_list:${SEED_USER}:${TARGET_TYPE}"      "${SEED_TS}" "${TARGET_ID}" >/dev/null || die "seed ZADD user_like_list 失败"
  docker exec "${c}" redis-cli SET  "user_total_like:${SEED_USER}" "${SEED_TOTAL_LIKE}" >/dev/null || die "seed SET user_total_like 失败"
}
# ⚠️ **wire 读路径的专属 target 只播 ZSet 一个键**（不碰 `user_like_list`）：
#   `user_like_list` 是**按用户**的键 ⇒ 再播一个成员 ⇒ 冻结锚里的 `getuserlikelist`
#   （**恰好 1 个元素**）就会变成 2 个 ⇒ 与锚不符 ⇒ 假红。
#   而 wire 的 `target_liker_list` 读的是**按 target**的键 ⇒ 播一个专属 target 是安全的。
seed_wire_read_target() {   # $1 = 容器名
  local c="$1"
  docker exec "${c}" redis-cli ZADD "target_liker_list:${TARGET_TYPE}:${WIRE_READ_TARGET_ID}" "${SEED_TS}" "${SEED_USER}" >/dev/null \
    || die "seed ZADD wire target_liker_list 失败"
}
seed_redis "${B_REDIS_CONTAINER}"
seed_redis "${C_REDIS_CONTAINER}"
seed_wire_read_target "${B_REDIS_CONTAINER}"
seed_wire_read_target "${C_REDIS_CONTAINER}"
SEED_SQL="INSERT INTO like_record (user_id,target_type,target_id,author_id,state,last_operation_id,created_at,updated_at) VALUES (${SEED_USER},'${TARGET_TYPE}','${TARGET_ID}',${SEED_AUTHOR},1,0,'2026-01-02 03:04:05+00','2026-01-02 03:04:05+00');"
for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  docker exec "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "${SEED_SQL}" >/dev/null \
    || die "播种 like_record 失败：${c}"
done
say "   ✓ 两侧同一批数据（Redis 6 个键 + PG 1 行）"

# ══════════════════════════ 7. 阶段 A · HTTP 业务面 ═══════════════════════════
step "7/13 阶段 A · HTTP 业务面（C == B == 冻结的 Go 字节 ⇒ 逐字节）"
EXPECTED="${HERE}/expected.txt"
[ -f "${EXPECTED}" ] || die "缺少冻结的期望值：${EXPECTED}"

now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

request_body() {   # $1=端口 $2=method $3=path $4=body $5=token(空串=不带 Authorization) ⇒ 打印 "<http_code>|<hex>"
  local port="$1" method="$2" path="$3" body="$4" token="$5"
  local out code start end
  # ⚠️ **每个请求用独立的 mktemp 文件**（固定 -o 文件 ⇒ 请求失败时读到上一次的残留 body ⇒ 假绿）
  out="$(mktemp "${WORK}/body.XXXXXX")" || return 1
  start="$(now_ms)"
  if [ -n "${token}" ]; then
    code=$(curl -s -o "${out}" -w '%{http_code}' -m 30 -X "${method}" \
             "http://127.0.0.1:${port}${path}" \
             -H "Authorization: Bearer ${token}" \
             -H 'Content-Type: application/json' \
             --data-binary "${body}" 2>/dev/null)
  else
    code=$(curl -s -o "${out}" -w '%{http_code}' -m 30 -X "${method}" \
             "http://127.0.0.1:${port}${path}" \
             -H 'Content-Type: application/json' \
             --data-binary "${body}" 2>/dev/null)
  fi
  end="$(now_ms)"
  # ⚠️ **耗时必须落文件**：本函数在 `$( )` 里跑 ⇒ 全局变量**回不来**（子 shell）⇒ 写文件。
  printf '%s' "$(( end - start ))" > "${WORK}/last-ms.txt"
  printf '%s|%s' "${code}" "$(xxd -p "${out}" | tr -d '\n')"
}
# ⚠️ **业务码提取**（不是 HTTP 状态码）：本域业务失败恒 HTTP 200 + 业务信封。
biz_code_of() {   # $1="httpcode|hex" ⇒ 打印业务码（取不到 ⇒ 空串）
  printf '%s' "${1##*|}" | xxd -r -p 2>/dev/null | grep -o '"code":[0-9]\+' | head -1 | cut -d: -f2
}
body_of() {       # $1="httpcode|hex" ⇒ 打印明文 body
  printf '%s' "${1##*|}" | xxd -r -p 2>/dev/null
}
# ⚠️ **就绪闸门必须等「不是 1015」的响应**：
#    「端口已监听」≠「下游 like-rpc 已就绪」⇒ 首请求会拿到 1015（service busy）
#    ⇒ 那不是「漂移」，是「没准备好」。
wait_ready() {   # $1 = 端口  $2 = 标签
  local p="$1" tag="$2" body
  for _ in $(seq 1 40); do
    body="$(curl -s -m 10 -X POST "http://127.0.0.1:${p}/like/v1/getlikecount" \
             -H "Authorization: Bearer ${TOKEN_READ}" -H 'Content-Type: application/json' \
             --data-binary "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" 2>/dev/null)"
    case "${body}" in
      ''|*'"code":1015'*) sleep 2 ;;
      *) say "   ✓ ${tag} 就绪（首个非 1015 响应已到：$(printf '%s' "${body}" | head -c 120)）"; return 0 ;;
    esac
  done
  say "   ❌ ${tag} 轮询 40 次仍拿不到非 1015 响应 ⇒ 不进请求矩阵"
  return 1
}

FAILED=0        # 真差异（计失败）
KNOWN=0         # 已知现象（不计失败，但必须显式上报）
APPROVED=0      # 已登记批准的偏离（不计失败）
SCEN_WARN=""

# 🧪 测试钩子（只给 mutate-parity.sh 用，正常跑不用）：
#    PROBE_STOP_C_AFTER=<case 名> ⇒ 该用例跑完后**杀掉 C 侧进程**。
PROBE_STOP_C_AFTER="${PROBE_STOP_C_AFTER:-}"
maybe_stop_c() {   # $1 = case 名
  [ -n "${PROBE_STOP_C_AFTER}" ] || return 0
  [ "$1" = "${PROBE_STOP_C_AFTER}" ] || return 0
  [ -n "${C_API_PID}" ] || return 0
  say "   🧪 测试钩子：杀掉 C 侧进程（PROBE_STOP_C_AFTER=${PROBE_STOP_C_AFTER}）"
  kill -9 "${C_API_PID}" 2>/dev/null
}

run_case() {   # $1=name $2=method $3=path $4=body $5=token
  local name="$1" method="$2" path="$3" body="$4" token="$5"
  local c b ch bh eh ccode bcode c_ms b_ms
  c="$(request_body "${C_HTTP_PORT}" "${method}" "${path}" "${body}" "${token}")"
  c_ms="$(cat "${WORK}/last-ms.txt" 2>/dev/null)"
  b="$(request_body "${B_API_PORT}"  "${method}" "${path}" "${body}" "${token}")"
  b_ms="$(cat "${WORK}/last-ms.txt" 2>/dev/null)"
  ch="${c##*|}"; bh="${b##*|}"; ccode="${c%%|*}"; bcode="${b%%|*}"
  if [ "${ccode}" = "000" ] || [ "${bcode}" = "000" ]; then
    FAILED=$((FAILED+1))
    say "   ❌ ${name}：请求失败（C http_code=${ccode} · B http_code=${bcode} · C ${c_ms}ms · B ${b_ms}ms）"
    maybe_stop_c "${name}"
    return
  fi
  # ⚠️ **用状态码字段当「有没有这一条」的标记**：hex 字段**允许为空**
  #    （`no_token` 的 body 本来就是空字节 ⇒ hex = ""）。
  eh="$(awk -F'|' -v n="CASE|${name}" '$1"|"$2==n {print $3"|"$4}' "${EXPECTED}")"
  if [ -z "${eh}" ]; then
    FAILED=$((FAILED+1))
    say "   ❌ ${name}：expected.txt 里没有这一条"
    maybe_stop_c "${name}"
    return
  fi
  if [ "${c}" = "${b}" ] && [ "${c}" = "${eh}" ]; then
    say "   ✅ ${name}  C == B == expected.txt（HTTP ${ccode} · ${#ch} 字节 · C ${c_ms}ms · B ${b_ms}ms · body=$(body_of "${c}")）"
  else
    FAILED=$((FAILED+1))
    say "   ❌ ${name}"
    [ "${ccode}" != "${bcode}" ] && say "      HTTP 状态码不同：C=${ccode} · B=${bcode}"
    if [ "${ch}" != "${bh}" ]; then
      say "      C != B"
      say "      C : $(body_of "${c}")"
      say "      B : $(body_of "${b}")"
    fi
    if [ "${c}" != "${eh}" ]; then
      say "      C != expected.txt（冻结的 Go 字节）"
      say "      C     : $(body_of "${c}")"
      say "      期望值: $(printf '%s' "${eh##*|}" | xxd -r -p 2>/dev/null)"
    fi
  fi
  maybe_stop_c "${name}"
}

wait_ready "${B_API_PORT}"  "B 侧" || die "B 侧没就绪（见上面的日志）"
wait_ready "${C_HTTP_PORT}" "C 侧" || die "C 侧没就绪（见上面的日志）"

# ── 请求矩阵（**顺序敏感**：写路径放最后，且各用不同的 target）──────────────────
run_case "getlikecount"       POST "/like/v1/getlikecount"       "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}"
run_case "getlikestate"       POST "/like/v1/getlikestate"       "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}"
run_case "gettargetlikerlist" POST "/like/v1/gettargetlikerlist" "{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"cursor\":0,\"page_size\":10}" "${TOKEN_READ}"
run_case "getuserlikelist"    POST "/like/v1/getuserlikelist"    "{\"target_type\":\"${TARGET_TYPE}\",\"cursor\":0,\"page_size\":10}" "${TOKEN_READ}"
run_case "getusertotallike"   POST "/like/v1/getusertotallike"   "{}" "${TOKEN_READ}"
# ⚠️ **`author_id` 必须取 "0"**：
#    Go 的 `likeactionlogic.go:151-162` 在 `AuthorId > 0 && UserId != AuthorId` 时会调
#    `notifyArticleLiked` ⇒ 走 **message.rpc** ⇒ 两侧耗时不可比（Go 等 2s、RAG 立刻失败）。
#    ⇒ 本用例取 `author_id="0"` ⇒ 两侧都**不走** notify 分支 ⇒ 只比较可比的字节。
#    （代价：`notifyArticleLiked` 这一段**不在**本探针的比较面内 ⇒ 见 README 的「覆盖边界」。）
run_case "likeaction"         POST "/like/v1/likeaction"         "{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"action_type\":1,\"author_id\":\"0\"}" "${TOKEN_ACTION}"
run_case "no_token"           POST "/like/v1/getlikecount"       "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" ""
run_case "missing_required"   POST "/like/v1/getlikecount"       "{\"target_type\":\"${TARGET_TYPE}\"}" "${TOKEN_READ}"

# ══════════════════════════ 8. 阶段 B · gRPC wire 层 ══════════════════════════
step "8/13 阶段 B · gRPC wire 层原始字节比对（C :${C_GRPC_PORT} ↔ B :${B_RPC_PORT}）"
# ⚠️ **为什么单独做这一层**：HTTP 层的 JSON 字节一致 **推不出** gRPC wire 字节一致
#    （proto 编码、字段号、status description 全是另一套面）。
# ⚠️ **探针不用任何 pb 生成码**（手构 proto3 字节 ＋ byte[] marshaller）⇒ **同一个 jar 跑两侧**
#    ⇒ 比的是纯 wire 字节。⚠️ **没有 Go 锚** ⇒ 读作「**双侧一致**」，**不计入「三方一致」**。
WIRE_SRC="${HERE}/grpc-wire/LikeWireProbe.java"
[ -f "${WIRE_SRC}" ] || die "缺 wire 探针源码：${WIRE_SRC}"
WIRE_OUT="$(mktemp -d "${WORK}/wire.XXXXXX")" || die "建 wire 临时目录失败"
javac -nowarn -cp "$(cat "${WORK}/like-cp.txt")" -d "${WIRE_OUT}" "${WIRE_SRC}" \
  || die "wire 探针编译失败"
WIRE_CP="${WIRE_OUT}:$(cat "${WORK}/like-cp.txt")"
java -cp "${WIRE_CP}" com.rag.like.parity.LikeWireProbe 127.0.0.1 "${B_RPC_PORT}" "${WIRE_OUT}/b.txt" \
  || die "B 侧 wire 探针执行失败"
java -cp "${WIRE_CP}" com.rag.like.parity.LikeWireProbe 127.0.0.1 "${C_GRPC_PORT}" "${WIRE_OUT}/c.txt" \
  || die "C 侧 wire 探针执行失败"
WIRE_COUNT=0
WIRE_MISMATCH=0
while IFS= read -r bline; do
  case "${bline}" in CASE\|*) ;; *) continue ;; esac
  wname="${bline#CASE|}"; wname="${wname%%|*}"
  wbody="${bline##*|}"
  cline="$(grep -F "CASE|${wname}|" "${WIRE_OUT}/c.txt" | head -1)"
  WIRE_COUNT=$((WIRE_COUNT + 1))
  if [ -z "${cline}" ]; then
    WIRE_MISMATCH=1
    FAILED=$((FAILED+1))
    say "   ❌ ${wname}  C 侧 wire 探针**没有这一条** ⇒ 两侧方法集不同"
    continue
  fi
  if [ "${bline}" = "${cline}" ]; then
    say "   ✅ ${wname}  wire 字节一致（C == B · ${#wbody} 字节）"
  else
    WIRE_MISMATCH=1
    FAILED=$((FAILED+1))
    say "   ❌ ${wname}  wire 字节不一致"
    say "      C : ${cline}"
    say "      B : ${bline}"
  fi
done < "${WIRE_OUT}/b.txt"
# ⚠️ **零命中是假信号**：必须确认真的跑到了用例（探针空跑 ⇒ 「一致」毫无意义）
if [ "${WIRE_COUNT}" -lt 6 ]; then
  WIRE_MISMATCH=1
  FAILED=$((FAILED+1))
  say "   ❌ wire 探针只产出 ${WIRE_COUNT} 条（应 ≥ 6）⇒ 探针没真跑"
else
  say "   ✓ wire 层共比对 ${WIRE_COUNT} 条（**双边**判定 · 无 Go 锚）"
fi

# ══════════════════════════ 9. 阶段 C · E60 双失败 ═══════════════════════════
step "9/13 阶段 C · E60 · Redis 与 DB **同时**失败 ⇒ 两侧响应必须与锚逐字节相同"
# ⚠️ **为什么必须注入「Redis 与 DB 同时失败」**（Go
#    `service/like/rpc/internal/logic/likeactionlogic.go:345-364` 的 `currentUserLikeTotal`）：
#    <pre>
#    total, err := ZcardCtx(...)            // err != nil ⇒ Redis 失败
#    if err != nil { total = 0 }
#    dbTotal, dbErr := GetUserActiveLikeCount(...)
#    if dbErr != nil {
#        if err == nil { return int64(total), nil }   // 只有 Redis 成功、DB 失败 ⇒ 不算失败
#        return 0, dbErr                              // **两者都失败** ⇒ error 必须传出去
#    }
#    </pre>
# ⇒ **单边失败都走不到 E60 那条分支**（Redis 单独失败 ⇒ dbErr==nil ⇒ 返回 (max(dbTotal,0), nil)；
#   DB 单独失败 ⇒ err==nil ⇒ 返回 (total, nil)）⇒ **只注入一个故障 ⇒ 探针测不到 E60**。
#
# ⚠️ **怎么让 ZCARD 失败、但 Lua 的 ZADD 照常工作**：
#    `CONFIG SET rename-command` 在 Redis 7 上是 **ERR syntax error**（实测）⇒ 不可用；
#    把 key 换成非 ZSET 类型也不行 —— Lua 的 ZADD 会先失败（那会变成 5012，不是 E60）。
#    ⇒ 用 **ACL**：`ACL SETUSER default -zcard` ⇒ ZCARD 返回
#    "NOPERM User default has no permissions to run the 'zcard' command" ⇒ **确定性失败**；
#    而脚本里的 ZADD / HGET / HINCRBY / INCR / ZREM / GET **不受影响**（Redis 7 实测）
#    ⇒ 点赞主流程照走，只把「读该用户的赞列表」这条打掉 ⇒ 正好落在 E60 的那条分支上。
#
# ⚠️ **怎么让 DB 失败**：把 `like_record` 表**删掉**（两侧同删）。
#    · B 侧：`model.InitDB` 的 AutoMigrate 只在**启动时**跑 ⇒ 启动后删表 ⇒ 运行期查询必失败；
#    · C 侧：Flyway 也只在启动时建表 ⇒ 同理。
#    ⇒ 而 likeaction 的 RPC 路径**不写** PG（写入在 MQ 消费侧）⇒ 删表只影响那两处「读」
#    （`currentArticleLikeTotal` / `currentUserLikeTotal`）⇒ 两侧都走 Go 的同款降级分支。
# ⚠️ **删表前先 dump DDL**：恢复轮要把它**原样建回来**（两侧的 V1 迁移文件逐字节相同）。
for side in b c; do
  if [ "${side}" = "b" ]; then rc="${B_REDIS_CONTAINER}"; pc="${B_PG_CONTAINER}"; else rc="${C_REDIS_CONTAINER}"; pc="${C_PG_CONTAINER}"; fi
  docker exec "${rc}" redis-cli ACL SETUSER default -zcard >/dev/null \
    || die "注入故障失败（${side} 侧 Redis ACL 禁用 ZCARD）"
  docker exec "${pc}" pg_dump -U admin -d first_db -t like_record --schema-only > "${WORK}/like_record_ddl_${side}.sql" 2>/dev/null \
    || die "导出 like_record DDL 失败（${side} 侧）"
  [ -s "${WORK}/like_record_ddl_${side}.sql" ] || die "导出的 DDL 是空的（${side} 侧）"
  docker exec "${pc}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "DROP TABLE IF EXISTS like_record;" >/dev/null \
    || die "注入故障失败（${side} 侧 PG 删除 like_record）"
done
say "   ✓ 两侧已注入故障：ZCARD 被 ACL 禁用 ＋ like_record 表被删除（DDL 已备份用于恢复）"

# ⚠️ **业务错误必须两侧都走到「记业务错误」这条分支**（两侧都是 Java ⇒ 形态相同：
#    SLF4J 的 `message=business_error` 行；**码值 5011 不在 stdout**（key-value 默认不打印）
#    ⇒ 码值由域内进程守门 `LikeUserLikeTotalDoubleFailureGateTest` 钉住，本探针只断言分支被走到）。
B_LOG_MARK="$(wc -l < "${WORK}/b-rpc.log" 2>/dev/null | tr -d '[:space:]')"; B_LOG_MARK="${B_LOG_MARK:-0}"
C_LOG_MARK="$(wc -l < "${WORK}/c-service.log" 2>/dev/null | tr -d '[:space:]')"; C_LOG_MARK="${C_LOG_MARK:-0}"
E60_BODY="{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${TARGET_ID}\",\"action_type\":1,\"author_id\":\"0\"}"
run_case "likeaction_double_failure" POST "/like/v1/likeaction" "${E60_BODY}" "${TOKEN_E60}"
# ⚠️ **日志断言**：只看「本次调用之后」新增的行（用行号切一刀 ⇒ 不会把别的阶段的日志算进证据里）。
B_BIZ="$(tail -n "+$(( B_LOG_MARK + 1 ))" "${WORK}/b-rpc.log" 2>/dev/null | grep -c "business_error")"; B_BIZ="${B_BIZ:-0}"
C_BIZ="$(tail -n "+$(( C_LOG_MARK + 1 ))" "${WORK}/c-service.log" 2>/dev/null | grep -c "business_error")"; C_BIZ="${C_BIZ:-0}"
if [ "${B_BIZ}" -ge 1 ] && [ "${C_BIZ}" -ge 1 ]; then
  say "   ✅ E60 业务错误分支：两侧都记了 business_error（B=${B_BIZ} 行 · C=${C_BIZ} 行）"
else
  FAILED=$((FAILED+1))
  say "   ❌ E60 业务错误分支没在两侧都出现（B=${B_BIZ} 行 · C=${C_BIZ} 行）⇒ 注入没生效 / 打错分支"
fi

# ══════════════════════════ 10. 阶段 D · 恢复 ═════════════════════════════════
step "10/13 阶段 D · 恢复（撤掉注入 ⇒ 两侧都回到正常计数）"
# ⚠️ **恢复判定必须「连续多轮」都成功**（follow 域的教训 #26）：
#    单轮 200 **不代表**已进入稳定态（下游刚回来时系统处于过渡态）。
wait_both_recovered() {   # $1=预算秒数 · $2=需要的连续成功轮数（默认 3）
  local budget="$1" need="${2:-3}" waited=0 streak=0 c_raw b_raw c_code b_code
  while [ "${waited}" -lt "${budget}" ]; do
    c_raw="$(request_body "${C_HTTP_PORT}" POST "/like/v1/getlikecount" \
              "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}")"
    b_raw="$(request_body "${B_API_PORT}"  POST "/like/v1/getlikecount" \
              "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}")"
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
  SCEN_WARN="${SCEN_WARN}「恢复轮：两侧在 ${budget}s 内未进入稳定态（对称）· "
  return 1
}
# ⚠️ **恢复顺序**：先撤销 Redis 的 ACL（ZCARD 可用）⇒ 再把表建回来 ⇒ 最后补种子行。
for side in b c; do
  if [ "${side}" = "b" ]; then rc="${B_REDIS_CONTAINER}"; pc="${B_PG_CONTAINER}"; else rc="${C_REDIS_CONTAINER}"; pc="${C_PG_CONTAINER}"; fi
  docker exec "${rc}" redis-cli ACL SETUSER default +zcard >/dev/null \
    || die "恢复失败（${side} 侧 Redis ACL 撤销 ZCARD 禁用）"
  docker exec -i "${pc}" psql -U admin -d first_db -v ON_ERROR_STOP=1 < "${WORK}/like_record_ddl_${side}.sql" >/dev/null 2>&1 \
    || die "恢复失败（${side} 侧重建 like_record 表）"
  docker exec "${pc}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "${SEED_SQL}" >/dev/null \
    || die "恢复失败（${side} 侧补种子行）"
done
say "   ✓ 注入已撤销（ZCARD 恢复 ＋ like_record 表重建 ＋ 种子行补回）"
wait_both_recovered 60 3 || true
c_raw="$(request_body "${C_HTTP_PORT}" POST "/like/v1/getlikecount" \
          "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}")"
c_rec_ms="$(cat "${WORK}/last-ms.txt" 2>/dev/null)"
b_raw="$(request_body "${B_API_PORT}"  POST "/like/v1/getlikecount" \
          "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}")"
b_rec_ms="$(cat "${WORK}/last-ms.txt" 2>/dev/null)"
if [ "${c_raw}" = "${b_raw}" ] && [ "$(biz_code_of "${c_raw}")" = "200" ]; then
  say "   ✅ 阶段 D 恢复  两侧逐字节一致（业务码 200 · C ${c_rec_ms}ms · B ${b_rec_ms}ms · body=$(body_of "${c_raw}")）"
else
  FAILED=$((FAILED+1))
  say "   ❌ 阶段 D 恢复：两侧不一致或业务码非 200（C=$(biz_code_of "${c_raw}") · B=$(biz_code_of "${b_raw}")）"
fi

# ══════════════════════════ 11. 阶段 E · 并发 ═════════════════════════════════
step "11/13 阶段 E · 并发（两侧各 8 路并发 likeaction ⇒ 最终计数必须一致）"
# ⚠️ **用独立 target**：主 target 的 like_count 是冻结锚的基准 ⇒ 并发轮写它会把锚打乱。
# ⚠️ **判据**：① 两侧的业务码集合相同 ② 两侧的最终计数相同 ③ 两侧的 Redis 终态相同。
codes_of() {   # $1=文件 ⇒ 去重后的业务码集合（逗号分隔）
  while IFS='|' read -r _c _hex; do
    [ -z "${_hex}" ] && continue
    printf '%s' "${_hex}" | xxd -r -p 2>/dev/null | grep -o '"code":[0-9]\+' | head -1 | cut -d: -f2
  done < "$1" | sort -u | tr '\n' ',' | sed 's/,$//'
}
run_concurrent_like() {   # $1=端口 $2=输出文件 $3=target_id
  local port="$1" out="$2" target="$3" i tmp code pids=()
  : > "${out}"
  for i in $(seq 1 8); do
    (
      tmp="$(mktemp "${WORK}/cc.XXXXXX")"
      code="$(curl -s -o "${tmp}" -w '%{http_code}' -m 30 -X POST \
               "http://127.0.0.1:${port}/like/v1/likeaction" \
               -H "Authorization: Bearer ${TOKEN_READ}" -H 'Content-Type: application/json' \
               --data-binary "{\"target_type\":\"${TARGET_TYPE}\",\"target_id\":\"${target}\",\"action_type\":1,\"author_id\":\"0\"}" 2>/dev/null)"
      printf '%s|%s\n' "${code}" "$(xxd -p "${tmp}" | tr -d '\n')" >> "${out}"
    ) &
    pids+=($!)
  done
  # ⚠️ **只等这 8 个**：`wait` 不带参数会等**脚本的所有**后台任务（含 java 进程）⇒ 死等
  for p in "${pids[@]}"; do wait "${p}" 2>/dev/null; done
}
run_concurrent_like "${C_HTTP_PORT}" "${WORK}/cc-c.txt" "${CONC_TARGET_ID}"
run_concurrent_like "${B_API_PORT}"  "${WORK}/cc-b.txt" "${CONC_TARGET_ID}"
c_codes="$(codes_of "${WORK}/cc-c.txt")"; b_codes="$(codes_of "${WORK}/cc-b.txt")"
c_final="$(request_body "${C_HTTP_PORT}" POST "/like/v1/getlikecount" \
           "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${CONC_TARGET_ID}\"]}" "${TOKEN_READ}")"
c_conc_ms="$(cat "${WORK}/last-ms.txt" 2>/dev/null)"
b_final="$(request_body "${B_API_PORT}"  POST "/like/v1/getlikecount" \
           "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${CONC_TARGET_ID}\"]}" "${TOKEN_READ}")"
b_conc_ms="$(cat "${WORK}/last-ms.txt" 2>/dev/null)"
c_redis="$(docker exec "${C_REDIS_CONTAINER}" redis-cli HGET "like_count:${TARGET_TYPE}" "${CONC_TARGET_ID}" | tr -d '\r\n')"
b_redis="$(docker exec "${B_REDIS_CONTAINER}" redis-cli HGET "like_count:${TARGET_TYPE}" "${CONC_TARGET_ID}" | tr -d '\r\n')"
say "      业务码集合：C=[${c_codes}] · B=[${b_codes}]"
say "      最终计数（HTTP 层）：C=$(body_of "${c_final}")（${c_conc_ms}ms） · B=$(body_of "${b_final}")（${b_conc_ms}ms）"
say "      最终计数（Redis 层）：C=${c_redis:-<空>} · B=${b_redis:-<空>}"
if [ "${c_codes}" != "${b_codes}" ]; then
  FAILED=$((FAILED+1))
  say "   ❌ 阶段 E 并发：两侧业务码集合不同（C=[${c_codes}] · B=[${b_codes}]）"
elif [ "${c_final}" != "${b_final}" ]; then
  FAILED=$((FAILED+1))
  say "   ❌ 阶段 E 并发：两侧最终计数响应不同（C=$(body_of "${c_final}") · B=$(body_of "${b_final}")）"
elif [ "${c_redis}" != "${b_redis}" ]; then
  FAILED=$((FAILED+1))
  say "   ❌ 阶段 E 并发：两侧 Redis 终态不同（C=${c_redis} · B=${b_redis}）"
else
  say "   ✅ 阶段 E 并发  两侧业务码集合相同（[${c_codes}]）且最终计数一致（HTTP + Redis 两侧同值）"
fi

# ══════════════════════════ 12. 阶段 F · 超时 ═════════════════════════════════
step "12/13 阶段 F · 超时（两侧的 Redis 容器同时 pause ⇒ 制造真实的「下游无响应」）"
# ⚠️ **判据为什么是「观察项」而不是「字节判据」**（**先定判据，再看结果**）：
#    · B 侧是 **api → rpc 两跳**（B 侧 like-api 的 2s 客户端预算先到）
#      ⇒ 失败形态 = 「2s 被截断」；
#    · C 侧是 **D2 域内直连**（没有那一跳）⇒ 失败形态 = 「Redis 3s 命令超时后走 DB 降级」。
#    ⇒ 两者的**耗时与失败形态天然不同**（**拓扑产物，不是漂移**）
#    ⇒ ⇒ **本阶段不按「形态」判失败**，但**逐条记录**（码值 + 耗时 + 耗时分档）。
#    ⇒ 判据只有两条：① **场景确实生效**（容器真的 paused）② **两侧都没有挂死**（都回了响应）。
#    ⇒ 挂死（curl 000）**是**真差异 ⇒ 计失败。
pause_redis() {   # $1=容器名 ⇒ 打印 pause 前后的状态（供判据核对）
  docker pause "$1" >/dev/null 2>&1 || return 1
  local st
  st="$(docker inspect -f '{{.State.Paused}}' "$1" 2>/dev/null | tr -d '\r\n')"
  [ "${st}" = "true" ] || return 1
  return 0
}
bucket_ms() {   # $1=毫秒 ⇒ 打印耗时分档
  local ms="$1"
  if [ "${ms}" -lt 1000 ]; then echo "<1s"
  elif [ "${ms}" -lt 3000 ]; then echo "1~3s"
  elif [ "${ms}" -lt 10000 ]; then echo "3~10s"
  else echo ">=10s"; fi
}
pause_redis "${B_REDIS_CONTAINER}" || die "B 侧 Redis 容器没能 pause ⇒ 超时场景无效（环境问题）"
pause_redis "${C_REDIS_CONTAINER}" || die "C 侧 Redis 容器没能 pause ⇒ 超时场景无效（环境问题）"
say "   ✓ 两侧 Redis 容器已 pause（docker inspect 确认 Paused=true）⇒ 场景生效"
t0="$(now_ms)"
c_tmo="$(request_body "${C_HTTP_PORT}" POST "/like/v1/getlikecount" \
          "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}")"
t1="$(now_ms)"
b_tmo="$(request_body "${B_API_PORT}"  POST "/like/v1/getlikecount" \
          "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}")"
t2="$(now_ms)"
c_ms=$((t1-t0)); b_ms=$((t2-t1))
c_code="$(biz_code_of "${c_tmo}")"; b_code="$(biz_code_of "${b_tmo}")"
c_http="${c_tmo%%|*}"; b_http="${b_tmo%%|*}"
say "      C 侧：HTTP ${c_http} · 业务码 ${c_code:-<无信封>} · 耗时 ${c_ms}ms（分档 $(bucket_ms ${c_ms})）· body=$(body_of "${c_tmo}")"
say "      B 侧：HTTP ${b_http} · 业务码 ${b_code:-<无信封>} · 耗时 ${b_ms}ms（分档 $(bucket_ms ${b_ms})）· body=$(body_of "${b_tmo}")"
docker unpause "${B_REDIS_CONTAINER}" >/dev/null 2>&1
docker unpause "${C_REDIS_CONTAINER}" >/dev/null 2>&1
say "   ✓ 两侧 Redis 容器已 unpause"
if [ "${c_http}" = "000" ] || [ "${b_http}" = "000" ]; then
  FAILED=$((FAILED+1))
  say "   ❌ 阶段 F 超时：有一侧**挂死**（无响应）⇒ 那是真差异（C http=${c_http} · B http=${b_http}）"
else
  if [ "${c_code}" = "${b_code}" ]; then
    say "   ✅ 阶段 F 超时  两侧失败形态一致（业务码 ${c_code:-<无信封>}）"
  else
    KNOWN=$((KNOWN+1))
    SCEN_WARN="${SCEN_WARN}「超时场景两侧失败形态不同（C=${c_code:-?}·${c_ms}ms / B=${b_code:-?}·${b_ms}ms）——拓扑产物（D2 域内直连 vs api→rpc 两跳）· "
    say "   ⚠️ 阶段 F 超时：两侧**失败形态不同** ⇒ 记为**已知现象**（不计失败）：C=${c_code:-?}（${c_ms}ms）· B=${b_code:-?}（${b_ms}ms）"
    say "      ⇒ 根因：B 侧 **api→rpc 两跳**（2s 客户端预算先到）· C 侧 **D2 域内直连**（Redis 3s 命令超时后走 DB 降级）"
  fi
fi
# ⚠️ **超时后必须确认两侧都恢复**（Redis 挂起期间的连接可能已坏）。
wait_both_recovered 60 3 || true
c_after="$(request_body "${C_HTTP_PORT}" POST "/like/v1/getlikecount" \
           "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}")"
c_after_ms="$(cat "${WORK}/last-ms.txt" 2>/dev/null)"
b_after="$(request_body "${B_API_PORT}"  POST "/like/v1/getlikecount" \
           "{\"target_type\":\"${TARGET_TYPE}\",\"target_ids\":[\"${TARGET_ID}\"]}" "${TOKEN_READ}")"
b_after_ms="$(cat "${WORK}/last-ms.txt" 2>/dev/null)"
if [ "${c_after}" = "${b_after}" ] && [ "$(biz_code_of "${c_after}")" = "200" ]; then
  say "   ✅ 阶段 F 收尾  超时后两侧都恢复（业务码 200 · C ${c_after_ms}ms · B ${b_after_ms}ms · 逐字节一致）"
else
  FAILED=$((FAILED+1))
  say "   ❌ 阶段 F 收尾：超时后两侧没都恢复（C=$(biz_code_of "${c_after}") · B=$(biz_code_of "${b_after}")）"
fi

# ══════════════════════════ 13. 收尾：服务**真正**停掉 + 端口零残留 ═══════════
# ⚠️ **必须先真正收尾（kill + 等），再查残留**（stop_stacks 幂等 ⇒ trap 再调一次无害）。
#    反过来（先查残留后收尾）⇒ 进程还活着 ⇒ **必然**报残留 ⇒ **恒红的假信号**。
step "13/13 收尾复核（服务真停 · 端口零残留）"
stop_stacks
# ⚠️ **必须先删容器、再查端口**：容器发布端口时是 **docker-proxy** 持有那个口
#    ⇒ `docker rm -f` 之后 proxy 还会 linger 几秒 ⇒ 不删容器就查 ⇒ **必然**报残留
#    ⇒ **恒红的假信号**（follow 域探针的同类教训）。
docker rm -f "${B_PG_CONTAINER}" >/dev/null 2>&1
docker rm -f "${B_REDIS_CONTAINER}" >/dev/null 2>&1
docker rm -f "${C_PG_CONTAINER}" >/dev/null 2>&1
docker rm -f "${C_REDIS_CONTAINER}" >/dev/null 2>&1
LEFTOVER=0
for _ in $(seq 1 20); do
  LEFTOVER=0
  for p in "${B_API_PORT}" "${B_RPC_PORT}" "${B_RPC_MGMT_PORT}" "${C_HTTP_PORT}" "${C_GRPC_PORT}" \
           "${B_PG_PORT}" "${C_PG_PORT}" "${B_REDIS_PORT}" "${C_REDIS_PORT}"; do
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
say "   ✓ 收尾复核：本探针的 9 个端口**零残留**"

# ══════════════════════════ 机器摘要 ═════════════════════════════════════════
echo
if [ -n "${SCEN_WARN}" ]; then
  say "⚠️ 场景轮的**已知现象**（不计失败，但如实记录）：${SCEN_WARN}"
fi
if [ "${C_JAR_HAS_INPROCESS}" != "1" ]; then
  say "⚠️ **C 侧 fat jar 缺 grpc-inprocess**（本探针已兜住 · 未改 service/**）："
  say "      service/like/pom.xml 若又把 io.grpc:grpc-inprocess 写回 test scope ⇒ 运行时 classpath 缺 artifact"
  say "      ⇒ 真 jar 直接起会抛 NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder"
  say "      ⇒ 本探针用 PropertiesLauncher + -Dloader.path 兜住（**行为面一字未改**）⇒ **需域内复修 pom**"
fi
say "—— 探针摘要：❌ 真差异 ${FAILED} ｜ ⚠️ 已知现象 ${KNOWN} ——"
if [ "${FAILED}" = "0" ]; then
  say "✅ 三方逐字节一致（C 侧 == B 侧 == 冻结的 Go 字节）"
  exit 0
fi
say "❌ 存在字节差异"
dump_logs
exit 1
