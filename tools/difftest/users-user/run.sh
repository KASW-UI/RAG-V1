#!/usr/bin/env bash
#
# user 域 **双侧活体对拍**探针 —— **B 侧（RAG / Boot 4）↔ C 侧（RAG-V1 / Boot 3.5）**
#
# 被测两侧（**同时起、同时活、同请求、同字节**）：
#   · B 侧 = $B_REPO/service/users/user/{user-api,user-rpc}   （Boot 4.0.8 · **双进程**）
#   · C 侧 = 本仓 service/users/user/                          （Boot 3.5.11 · **D1 合并单进程**）
#
# 判据（**两档**，口径写在 compare.py 里）：
#   · 结构级 = 归一化「雪花 uid / history_id / 自签 JWT / 时间戳 / 密码哈希」+「JSON 键序」后逐字节相等
#   · 字节级 = 只归一化「雪花 uid / history_id / 自签 JWT / 时间戳 / 密码哈希」（**键序参与比较**）
# ⇒ 两档分开报 ⇒ 「键序差异」不会被混进「行为差异」，也不会被藏起来。
#
# ⚠️ **为什么必须两档**：user 域响应里带**两侧必然不同**的量（雪花 uid、各自自签的 JWT）
#    ⇒ 必须归一化；而 **JSON 键序**是**表达层**差异（RFC 8259：对象是无序集合）
#    ⇒ 单列一档，**不混进行为判定、也不隐藏**。
#
# ⚠️ **B 侧只读**：本探针只在 B 仓跑 `mvn package`（产物落 `target/`，已被 .gitignore 排除）
#    ⇒ **不碰 B 侧任何源码 / 配置**（收尾会断言 B 仓 `git status` 干净）。
#
# 用法：
#   bash tools/difftest/users-user/run.sh
#   B_REPO=/path/to/RAG bash tools/difftest/users-user/run.sh
#   KEEP_WORK=1 bash tools/difftest/users-user/run.sh
#
# 退出码：0 = 结构级一致 · 1 = 有结构级不一致 · 2 = 环境问题 / 依赖缺失。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"

B_REPO="${B_REPO:-/Users/pr/Documents/java&golang/RAG}"

# ── 宿主中间件端口（**只走发布口**：Docker Desktop(macOS) 的宿主不可达容器 IP）──────────
NACOS_PORT="${NACOS_PORT:-49848}"    # Sea-nacos   49848→8848
REDIS_PORT="${REDIS_PORT:-49380}"    # Sea-redis   49380→6379

# ── 端口（**本探针专用段 18301-18309**）────────────────────────────────────────────
B_API_PORT="${B_API_PORT:-18301}"          # B 侧 user-api（HTTP）
B_RPC_PORT="${B_RPC_PORT:-18302}"          # B 侧 user-rpc（gRPC）
B_RPC_MGMT_PORT="${B_RPC_MGMT_PORT:-18303}"  # B 侧 user-rpc 的 Actuator 载体（默认 9094 ⇒ 必须覆盖）
C_HTTP_PORT="${C_HTTP_PORT:-18304}"        # C 侧 user-service（HTTP · D1 合并进程）
C_GRPC_PORT="${C_GRPC_PORT:-18305}"        # C 侧 user-service（gRPC）
B_PG_PORT="${B_PG_PORT:-18306}"            # B 侧 Postgres
C_PG_PORT="${C_PG_PORT:-18307}"            # C 侧 Postgres

# ── Nacos 分组隔离（🔴 关键）：两侧的 `user-rpc` **同名** ⇒ 必须不同组，
#    否则 B 侧 user-api 的 discovery 可能拨到 C 侧的 user-rpc ⇒ 对拍对象就错了。
B_GROUP="${B_GROUP:-E4UB}"   # B 侧两进程（user-api / user-rpc）
C_GROUP="${C_GROUP:-E4UC}"   # C 侧 user-service

# ── 两侧各自的 Postgres（容器名带 $$ ⇒ 别的 lane 的 cleanup 绝不可能删掉本轮的库）──
B_PG_CONTAINER="${B_PG_CONTAINER:-e4user-b-pg-$$}"
C_PG_CONTAINER="${C_PG_CONTAINER:-e4user-c-pg-$$}"
PG_IMAGE="${PG_IMAGE:-postgres:16-alpine}"
PG_USER="${PG_USER:-sea}"
PG_PASSWORD="${PG_PASSWORD:-e4user_parity_pw}"
PG_DB_B="${PG_DB_B:-diff_b}"
PG_DB_C="${PG_DB_C:-diff_c}"

# ── 验签密钥（**探针本地值**，不碰仓里任何历史明文密钥）────────────────────────────
JWT_SECRET="${JWT_SECRET:-e4-user-parity-probe-secret-0001}"

WORK="$(mktemp -d)"
PIDS=()
FAILED=0
say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
die()  { printf '!! %s\n' "$*" >&2; exit 2; }

cleanup() {
  local p
  for p in "${PIDS[@]:-}"; do [ -n "${p}" ] && kill -9 "${p}" 2>/dev/null; done
  # ⚠️ **先恢复两侧 PG**（场景轮可能把容器 pause/stop 了）⇒ 再删 ⇒
  #    顺序反了的话，容器带着「paused」状态被删也无所谓，但**若 rm 失败**，
  #    下一个 lane 会拿到一个挂着的容器 ⇒ ⇒ 先 unpause/start 再删。
  docker unpause "${B_PG_CONTAINER}" "${C_PG_CONTAINER}" >/dev/null 2>&1
  docker start   "${B_PG_CONTAINER}" "${C_PG_CONTAINER}" >/dev/null 2>&1
  [ "${KEEP_WORK:-0}" = "1" ] || docker rm -f "${B_PG_CONTAINER}" "${C_PG_CONTAINER}" >/dev/null 2>&1
  return 0
}
trap cleanup EXIT

for cmd in docker mvn java curl python3 lsof nc; do
  command -v "${cmd}" >/dev/null 2>&1 || die "缺命令：${cmd}"
done
[ -d "${B_REPO}" ] || die "B 仓不存在：${B_REPO}"

# ── 端口占用检查（不抢别人的端口）───────────────────────────────────────────────
port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
for p in "${B_API_PORT}" "${B_RPC_PORT}" "${B_RPC_MGMT_PORT}" "${C_HTTP_PORT}" "${C_GRPC_PORT}" "${B_PG_PORT}" "${C_PG_PORT}"; do
  port_busy "${p}" && die "端口 ${p} 被占用 ⇒ 换端口（env 可覆盖）"
done

wait_ready() {   # $1=端口 $2=标签
  local i
  for i in $(seq 1 60); do
    if nc -z -G 2 127.0.0.1 "$1" >/dev/null 2>&1; then say "   ✅ ${2} 就绪（:${1}）"; return 0; fi
    sleep 1
  done
  say "   ❌ ${2} 60s 内未就绪（:${1}）"
  return 1
}

# ⚠️ **按端口把监听进程记进 PIDS** ⇒ cleanup 能按 PID 精确杀掉**本轮起的**进程。
#    为什么要多这一手：`$!` 只在「子 shell 里 exec」时才等于 java 的 PID；
#    一旦任务被**外部取消**（SIGKILL）⇒ trap 不执行 ⇒ 进程活下来 ⇒ **端口残留**
#    ⇒ 下一轮探针直接「端口被占用」退出（2026-11-03 实测踩过）。
#    ⇒ 这里按端口**事后**取 PID ⇒ 即使 trap 没跑，残留也能被下一轮清掉。
track_listener() {   # $1=端口
  local pid
  pid="$(lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1)"
  [ -n "${pid}" ] && PIDS+=("${pid}") && return 0
  return 1
}

# ── Nacos 实例断言（🔴 启动顺序纪律）────────────────────────────────────────────
# ⚠️ **为什么必须等**：B 侧 user-api 用 devh 的 gRPC client 连 `user-rpc`
#    ⇒ 那个 client **启动即连**（`connectOnStartup`）⇒ ⇒ user-rpc 虽已监听端口，
#    但**还没注册进 Nacos**时，user-api 会以
#    `IllegalStateException: Can't connect to channel user-rpc` **启动失败**
#    ⇒ ⇒ 必须**等注册落进实例列表**再起 user-api（2026-11-03 实测踩过：偶发起不来）。
nacos_list_instances() {   # $1=服务名 $2=组 ⇒ 逐行打印 "ip port"
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
nacos_assert_instance() {   # $1=服务名 $2=组 $3=端口
  local i
  for i in $(seq 1 30); do
    nacos_list_instances "$1" "$2" | grep -q " ${3}$" && return 0
    sleep 1
  done
  return 1
}

start_pg() {   # $1=容器名 $2=宿主端口 $3=库名
  docker rm -f "$1" >/dev/null 2>&1
  docker run -d --name "$1" -e POSTGRES_USER="${PG_USER}" -e POSTGRES_PASSWORD="${PG_PASSWORD}" \
    -e POSTGRES_DB="$3" -p "$2:5432" "${PG_IMAGE}" >/dev/null || return 1
  local i
  for i in $(seq 1 60); do
    if docker exec "$1" pg_isready -U "${PG_USER}" -d "$3" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

step "准备两侧 Postgres"
start_pg "${B_PG_CONTAINER}" "${B_PG_PORT}" "${PG_DB_B}" || die "B 侧 Postgres 起不来"
say "   ✅ ${B_PG_CONTAINER} :${B_PG_PORT} / ${PG_DB_B}"
start_pg "${C_PG_CONTAINER}" "${C_PG_PORT}" "${PG_DB_C}" || die "C 侧 Postgres 起不来"
say "   ✅ ${C_PG_CONTAINER} :${C_PG_PORT} / ${PG_DB_C}"

step "准备两侧产物"
B_API_JAR="${B_REPO}/service/users/user/user-api/target/user-api-0.0.1-SNAPSHOT.jar"
B_RPC_JAR="${B_REPO}/service/users/user/user-rpc/target/user-rpc-0.0.1-SNAPSHOT.jar"
C_JAR="${C_ROOT}/service/users/user/target/user-service-1.0-SNAPSHOT.jar"
# 🔴 **陈旧产物检测**（2026-11-03 实测踩过）：「jar 存在 ⇒ 跳过构建」是**危险的**
#    ⇒ jar 存在但**比源码旧**时 ⇒ 探针量到的是**上一次的代码** ⇒ 读数是假的
#    ⇒ （实测：共享层的 timeout 修复已进 main，但 jar 还是旧的 ⇒ 量出了「假的 503」）
newer_than_jar() {   # $1=jar  $2...=源码目录 ⇒ 有任一源文件比 jar 新 ⇒ 打印一行
  find "$@" -type f -newer "$1" 2>/dev/null | head -1
}
if [ ! -f "${B_API_JAR}" ] || [ ! -f "${B_RPC_JAR}" ]; then
  say "   … B 侧 jar 不存在 ⇒ 在 B 仓跑 mvn package（只写 target/，已被 .gitignore 排除）"
  (cd "${B_REPO}" && mvn -o -q -DskipTests package -pl service/users/user/user-api,service/users/user/user-rpc -am) \
    || die "B 侧构建失败"
elif [ -n "$(newer_than_jar "${B_API_JAR}" "${B_REPO}/service/users/user/user-api/src/main" "${B_REPO}/service/users/user/user-rpc/src/main")" ]; then
  say "   … B 侧 jar 比源码旧 ⇒ 重建（否则探针量到陈旧产物）"
  (cd "${B_REPO}" && mvn -o -q -DskipTests package -pl service/users/user/user-api,service/users/user/user-rpc -am) \
    || die "B 侧构建失败"
fi
if [ ! -f "${C_JAR}" ]; then
  say "   … C 侧 jar 不存在 ⇒ 在本仓跑 mvn package"
  (cd "${C_ROOT}" && mvn -o -q -DskipTests package -pl service/users/user -am) || die "C 侧构建失败"
elif [ -n "$(newer_than_jar "${C_JAR}" "${C_ROOT}/service/users/user/src/main" "${C_ROOT}/service/users/common/src/main" "${C_ROOT}/common/src/main" "${C_ROOT}/proto/src/main")" ]; then
  say "   … C 侧 jar 比源码旧 ⇒ 重建（🔴 否则探针量到的是**陈旧产物** ⇒ 读数是假的）"
  (cd "${C_ROOT}" && mvn -o -q -DskipTests package -pl service/users/user -am) || die "C 侧构建失败"
fi
# 🔴 **C 侧 jar 必须含 grpc-inprocess**（devh 的 GrpcClientAutoConfiguration 运行期要它
#    ⇒ 缺了 ⇒ fat jar 启动即挂 ⇒ 2026-11-03 对拍实测到，见 README「探针自检」）
python3 - "${C_JAR}" <<'PY' || die "C 侧 jar 缺 grpc-inprocess ⇒ fat jar 起不来（pom 的 scope 问题）"
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    if not any('grpc-inprocess' in n for n in z.namelist()):
        sys.exit(1)
PY
say "   ✅ 两侧 jar 就绪"

step "启动 B 侧（user-rpc → user-api · 双进程）"
( cd "${B_REPO}" && POSTGRES_ADDR=127.0.0.1 POSTGRES_PORT="${B_PG_PORT}" POSTGRES_DB="${PG_DB_B}" \
  POSTGRES_USER="${PG_USER}" POSTGRES_PASSWORD="${PG_PASSWORD}" \
  REDIS_ADDR=127.0.0.1 REDIS_PORT="${REDIS_PORT}" REDIS_PASSWORD= \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${B_GROUP}" \
  NACOS_USERNAME= NACOS_PASSWORD= \
  USER_JWT_SECRET="${JWT_SECRET}" USER_JWT_EXPIRE=31536000 \
  USER_RPC_PORT="${B_RPC_PORT}" USER_RPC_MANAGEMENT_PORT="${B_RPC_MGMT_PORT}" \
  exec java -jar "${B_RPC_JAR}" ) > "${WORK}/b-user-rpc.log" 2>&1 &
PIDS+=($!); disown 2>/dev/null || true
wait_ready "${B_RPC_PORT}" "B user-rpc(gRPC)" || { tail -30 "${WORK}/b-user-rpc.log"; die "B user-rpc 起不来"; }
track_listener "${B_RPC_PORT}"
# 🔴 **必须等 Nacos 注册落地**再起 user-api（它的 gRPC client **启动即连** `user-rpc`）
nacos_assert_instance user-rpc "${B_GROUP}" "${B_RPC_PORT}" \
  || { tail -30 "${WORK}/b-user-rpc.log"; die "B 侧 user-rpc 没出现在 Nacos[${B_GROUP}] 实例列表里"; }
say "   ✅ B 侧 user-rpc 已自注册进 Nacos[${B_GROUP}]（:${B_RPC_PORT}）"

( cd "${B_REPO}" && POSTGRES_ADDR=127.0.0.1 POSTGRES_PORT="${B_PG_PORT}" POSTGRES_DB="${PG_DB_B}" \
  POSTGRES_USER="${PG_USER}" POSTGRES_PASSWORD="${PG_PASSWORD}" \
  REDIS_ADDR=127.0.0.1 REDIS_PORT="${REDIS_PORT}" REDIS_PASSWORD= \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${B_GROUP}" \
  NACOS_USERNAME= NACOS_PASSWORD= \
  USER_JWT_SECRET="${JWT_SECRET}" USER_JWT_EXPIRE=31536000 \
  USER_API_PORT="${B_API_PORT}" \
  exec java -jar "${B_API_JAR}" ) > "${WORK}/b-user-api.log" 2>&1 &
PIDS+=($!); disown 2>/dev/null || true
wait_ready "${B_API_PORT}" "B user-api(HTTP)" || { tail -30 "${WORK}/b-user-api.log"; die "B user-api 起不来"; }
track_listener "${B_API_PORT}"

step "启动 C 侧（user-service · D1 合并单进程）"
( cd "${C_ROOT}" && POSTGRES_HOST=127.0.0.1 POSTGRES_PORT="${C_PG_PORT}" POSTGRES_DB="${PG_DB_C}" \
  POSTGRES_USER="${PG_USER}" POSTGRES_PASSWORD="${PG_PASSWORD}" \
  REDIS_ADDR=127.0.0.1 REDIS_PORT="${REDIS_PORT}" REDIS_PASSWORD= \
  NACOS_ADDR=127.0.0.1 NACOS_PORT="${NACOS_PORT}" NACOS_NAMESPACE=public NACOS_GROUP="${C_GROUP}" \
  NACOS_USERNAME= NACOS_PASSWORD= \
  USER_JWT_SECRET="${JWT_SECRET}" USER_JWT_EXPIRE=31536000 \
  USER_HTTP_PORT="${C_HTTP_PORT}" USER_GRPC_PORT="${C_GRPC_PORT}" \
  exec java -jar "${C_JAR}" ) > "${WORK}/c-service.log" 2>&1 &
PIDS+=($!); disown 2>/dev/null || true
wait_ready "${C_HTTP_PORT}" "C user-service(HTTP)" || { tail -40 "${WORK}/c-service.log"; die "C user-service 起不来"; }
track_listener "${C_HTTP_PORT}"

step "HTTP 对拍矩阵（20 例）"
python3 "${HERE}/compare.py" "http://127.0.0.1:${B_API_PORT}" "http://127.0.0.1:${C_HTTP_PORT}" \
  "e4u$$" "1" || FAILED=1

step "编译 gRPC wire 探针（同一份 class 打两侧 ⇒ 比的是纯粹的 wire 字节）"
M2="${HOME}/.m2/repository"
pick_jar() { find "${M2}/$1" -name "$2-*.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort -V | tail -1; }
GRPC_CP=""
for a in "io/grpc grpc-api" "io/grpc grpc-stub" "io/grpc grpc-core" \
         "io/grpc grpc-netty-shaded" "io/grpc grpc-context" \
         "com/google/guava guava" "com/google/guava failureaccess" \
         "com/google/code/gson gson" "io/perfmark perfmark-api" \
         "com/google/errorprone error_prone_annotations" \
         "org/codehaus/mojo animal-sniffer-annotations" \
         "javax/annotation javax.annotation-api"; do
  g="${a% *}"; ar="${a##* }"
  j="$(pick_jar "${g}" "${ar}")"
  [ -n "${j}" ] || die "本地仓缺 jar：${g}/${ar}（gRPC 探针需要它才能编译）"
  GRPC_CP="${GRPC_CP:+${GRPC_CP}:}${j}"
done
PROBE_DIR="${WORK}/probe"
mkdir -p "${PROBE_DIR}"
javac -encoding UTF-8 -cp "${GRPC_CP}" -d "${PROBE_DIR}" "${HERE}/grpc-wire/UserWireProbe.java" \
  || die "gRPC wire 探针编译失败"
say "   ✅ 探针编译完成（grpc-api $(basename "$(pick_jar io/grpc grpc-api)")）"

step "gRPC wire 对拍矩阵（18 例 · 9 个方法全覆盖 + 错误路径 + 成功路径）"
# ⚠️ 用户名两侧必须**相同**（同输入），但**每轮唯一** ⇒ 避免上一轮崩在中途留下的残user 撞 ALREADY_EXISTS。
WIRE_USER="e4ug$(date +%s)$$"
java -cp "${GRPC_CP}:${PROBE_DIR}" com.rag.user.parity.UserWireProbe \
  127.0.0.1 "${B_RPC_PORT}" "${WORK}/wire-b.txt" "${WIRE_USER}" > "${WORK}/wire-b.log" 2>&1
B_WIRE_RC=$?
java -cp "${GRPC_CP}:${PROBE_DIR}" com.rag.user.parity.UserWireProbe \
  127.0.0.1 "${C_GRPC_PORT}" "${WORK}/wire-c.txt" "${WIRE_USER}" > "${WORK}/wire-c.log" 2>&1
C_WIRE_RC=$?
if [ "${B_WIRE_RC}" != "0" ] || [ "${C_WIRE_RC}" != "0" ]; then
  FAILED=1
  say "   ❌ wire 探针异常退出：B=${B_WIRE_RC} · C=${C_WIRE_RC}"
  say "   --- B 侧日志 ---"; tail -20 "${WORK}/wire-b.log" | sed 's/^/      | /'
  say "   --- C 侧日志 ---"; tail -20 "${WORK}/wire-c.log" | sed 's/^/      | /'
else
  python3 "${HERE}/wire_compare.py" "${WORK}/wire-b.txt" "${WORK}/wire-c.txt" || FAILED=1
fi

step "DB 最终态对拍（§6.4 状态和副作用）"
DB_ROWS_B="$(docker exec "${B_PG_CONTAINER}" psql -U "${PG_USER}" -d "${PG_DB_B}" -tAc \
  "SELECT username||'|'||status||'|'||coalesce(score::text,'-')||'|'||coalesce(extra_info,'-')||'|'||(password IS NOT NULL)::text||'|'||coalesce(email,'-') FROM users_user ORDER BY username")"
DB_ROWS_C="$(docker exec "${C_PG_CONTAINER}" psql -U "${PG_USER}" -d "${PG_DB_C}" -tAc \
  "SELECT username||'|'||status||'|'||coalesce(score::text,'-')||'|'||coalesce(extra_info,'-')||'|'||(password IS NOT NULL)::text||'|'||coalesce(email,'-') FROM users_user ORDER BY username")"
if [ "${DB_ROWS_B}" = "${DB_ROWS_C}" ]; then
  say "   ✅ users_user 最终态两侧一致（$(printf '%s' "${DB_ROWS_B}" | grep -c . ) 行）"
  printf '%s\n' "${DB_ROWS_B}" | sed 's/^/      | /'
else
  FAILED=1
  say "   ❌ users_user 最终态不一致"
  say "      B:"; printf '%s\n' "${DB_ROWS_B}" | sed 's/^/      | /'
  say "      C:"; printf '%s\n' "${DB_ROWS_C}" | sed 's/^/      | /'
fi
# ⚠️ 头像历史表：gRPC 维度上传了 2 个头像 ⇒ 比「行数 / 当前头像数 / 内容序列」。
#    ⚠️ **不比对 history_id**（服务端雪花 ⇒ 两侧必然不同）；也**不比对 create_time**（写入时刻）。
#    ⇒ 若两侧的「删用户是否级联删头像」行为不同 ⇒ 行数不同 ⇒ 这里会红。
AV_B="$(docker exec "${B_PG_CONTAINER}" psql -U "${PG_USER}" -d "${PG_DB_B}" -tAc \
  "SELECT count(*)||'/'||count(*) FILTER (WHERE is_current)||'/'||coalesce(string_agg(content_type||':'||size_bytes::text,',' ORDER BY id),'-') FROM users_user_avatar_history")"
AV_C="$(docker exec "${C_PG_CONTAINER}" psql -U "${PG_USER}" -d "${PG_DB_C}" -tAc \
  "SELECT count(*)||'/'||count(*) FILTER (WHERE is_current)||'/'||coalesce(string_agg(content_type||':'||size_bytes::text,',' ORDER BY id),'-') FROM users_user_avatar_history")"
if [ "${AV_B}" = "${AV_C}" ]; then
  say "   ✅ users_user_avatar_history 最终态一致（${AV_B}）"
else
  FAILED=1
  say "   ❌ users_user_avatar_history 最终态不一致：B=${AV_B} · C=${AV_C}"
fi
TBL_B="$(docker exec "${B_PG_CONTAINER}" psql -U "${PG_USER}" -d "${PG_DB_B}" -tAc \
  "SELECT string_agg(tablename,',' ORDER BY tablename) FROM pg_tables WHERE schemaname='public'")"
TBL_C="$(docker exec "${C_PG_CONTAINER}" psql -U "${PG_USER}" -d "${PG_DB_C}" -tAc \
  "SELECT string_agg(tablename,',' ORDER BY tablename) FROM pg_tables WHERE schemaname='public'")"
if [ "${TBL_B}" = "${TBL_C}" ]; then
  say "   ✅ 表清单一致：${TBL_B}"
else
  FAILED=1
  say "   ❌ 表清单不一致：B=${TBL_B} · C=${TBL_C}"
fi

step "B 侧只读校验"
if ( cd "${B_REPO}" && git status --short -- service/ | grep -q . ); then
  FAILED=1
  say "   ❌ B 仓 service/ 有改动 ⇒ 探针越界了！"
  ( cd "${B_REPO}" && git status --short -- service/ | sed 's/^/      | /' )
else
  say "   ✅ B 仓 service/ 干净"
fi

# ⚠️ **场景轮必须排在最后**：它会 **pause / stop 两侧的 PG** 并**改库**
#    ⇒ ⇒ 前面的 HTTP 矩阵、wire 矩阵、DB 最终态对拍都必须在**未注入故障**时跑完。
step "场景轮（双侧对称）：超时 / gRPC deadline / 取消传播 / 恢复 / 外部依赖失败 / 恢复 / 并发"
python3 "${HERE}/scenarios.py" \
  --b-http "http://127.0.0.1:${B_API_PORT}" --c-http "http://127.0.0.1:${C_HTTP_PORT}" \
  --b-grpc "${B_RPC_PORT}" --c-grpc "${C_GRPC_PORT}" \
  --b-pg "${B_PG_CONTAINER}" --c-pg "${C_PG_CONTAINER}" \
  --pg-user "${PG_USER}" --pg-db "${PG_DB_B}" --c-pg-db "${PG_DB_C}" \
  --probe-cp "${GRPC_CP}" --probe-classes "${PROBE_DIR}" \
  --username "e4us$$" --work "${WORK}" || FAILED=1

printf '\n'
if [ "${FAILED}" = "0" ]; then
  say "✅ user 域双侧活体对拍通过（结构级一致）"
  exit 0
fi
say "❌ user 域双侧活体对拍有差异（见上）"
exit 1
