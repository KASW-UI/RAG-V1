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
if [ ! -f "${B_API_JAR}" ] || [ ! -f "${B_RPC_JAR}" ]; then
  say "   … B 侧 jar 不存在 ⇒ 在 B 仓跑 mvn package（只写 target/，已被 .gitignore 排除）"
  (cd "${B_REPO}" && mvn -o -q -DskipTests package -pl service/users/user/user-api,service/users/user/user-rpc -am) \
    || die "B 侧构建失败"
fi
C_JAR="${C_ROOT}/service/users/user/target/user-service-1.0-SNAPSHOT.jar"
if [ ! -f "${C_JAR}" ]; then
  say "   … C 侧 jar 不存在 ⇒ 在本仓跑 mvn package"
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

step "HTTP 对拍矩阵（20 例）"
python3 "${HERE}/compare.py" "http://127.0.0.1:${B_API_PORT}" "http://127.0.0.1:${C_HTTP_PORT}" \
  "e4u$$" "1" || FAILED=1

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

printf '\n'
if [ "${FAILED}" = "0" ]; then
  say "✅ user 域双侧活体对拍通过（结构级一致）"
  exit 0
fi
say "❌ user 域双侧活体对拍有差异（见上）"
exit 1
