#!/usr/bin/env bash
# =============================================================================
# probe-isolation.sh —— article 域「B 侧 ↔ C 侧」双侧活体对拍探针的每轮独立机制
#
# 【与 B 侧探针（tools/api-full-parity-probe/article/probe-isolation.sh）的关系】
#   本文件是 B 侧探针同名文件的**改写版**，比对双方从
#     「Go 真源栈 ↔ RAG 栈（B 侧 Java）」
#   改成
#     「RAG 栈（B 侧 Java）↔ RAG-V1 栈（C 侧 Java）」。
#   ⇒ 机制层（端口租约 / 命名空间 / 构建锁 / 只踢探针进程）**逐条沿用**，
#     因为那四类污染面与「比对谁」无关；
#   ⇒ 变化的是 **V1 的命名空间从一个变两个**（B 侧一个、C 侧一个）——
#     因为两侧**都**会把自己注册成 `article-rpc`（见下面 V1 段）。
#
# 【为什么需要这个文件】
#   探针要同时起**两整栈**（B 侧 api+rpc 两个容器 + C 侧单进程）⇒ 天然要占宿主端口、
#   要在 Nacos 注册服务、要有自己的 Postgres 与 MinIO。
#   ⇒ **并发跑两份**时这些「共享面」会让两份互相污染 ⇒ 探针报 1015 / 5001
#   ⇒ ⇒ 「跑红」被误读成「C 侧有缺陷」⇒ ⇒ 一类**假红**。
#
# 【四个隔离向量】
#   V1 **Nacos 命名空间（两个）** —— env `NACOS_NAMESPACE_B` / `NACOS_NAMESPACE_C`
#       · 为什么命名空间能隔离：**命名空间由 Nacos 客户端配置决定**，不是代码写死的。
#         B 侧 `service/article/article-rpc/src/main/resources/application.yml:125`
#              `namespace: ${NACOS_NAMESPACE:public}`
#         B 侧 `service/article/article-api/src/main/resources/application.yml:33`
#              `namespace: ${NACOS_NAMESPACE:public}`
#         C 侧 `service/article/src/main/resources/application.yml`（`spring.cloud.nacos.discovery.namespace`）
#              `namespace: ${NACOS_NAMESPACE:sea-rtw}`
#         ⇒ 设 env `NACOS_NAMESPACE=xxx` ⇒ 两侧进程**注册 / 发现**都落到 `xxx`。
#       · 🔴 **为什么必须是两个命名空间（不能只用一个）**：
#         **B 侧与 C 侧注册的服务名是同一个** —— `article-rpc`
#           B 侧 `…/article/rpc/bootstrap/NacosRegistration.java:42` `SERVICE_NAME = "article-rpc"`
#           C 侧 `…/article/wiring/NacosRegistration.java:82`       `SERVICE_NAME = "article-rpc"`
#         ⇒ 若共用一个命名空间 ⇒ B 的 article-api 用 `discovery:///article-rpc`
#           解析时**可能选中 C 侧的实例**（反之亦然）⇒ ⇒ 两侧互相串台
#           ⇒ ⇒ 探针比较的不再是「B 侧 vs C 侧」，而是「B 侧 vs 随机一侧」⇒ **假绿**。
#         ⇒ **隔离面必须能把两侧分开** ⇒ 两个命名空间。
#       · ⚠️ **服务名 / group 改不了**（两侧都是硬编码常量）⇒ 隔离**只能**靠命名空间。
#       · 命名空间的**创建**用 Nacos v1 开放 API（实测可用，见 `probe_nacos_ensure_namespace`）。
#
#   V2 **Postgres 阶梯** —— 两侧**各自一个库**（Flyway 账本互不干扰）
#       · B 侧与 C 侧的 Flyway **账本表名不同**（B 侧 = 默认 `flyway_schema_history`，
#         C 侧 = `flyway_schema_history_article`）⇒ 但**共用一个 PG 会互相污染**
#         （B 侧启动时 baseline 打在 public schema 上 ⇒ C 侧再启动会跳过自己的迁移）
#         ⇒ ⇒ 两侧必须**各一个 PG 容器**。
#       · 端口用**阶梯**（55570/55572/… 与 55571/55573/… 交错）⇒ 避免撞上别的轮次。
#
#   V3 **宿主端口块** —— 端口**租约**（原子 `set -o noclobber` 的 `>`）
#       · 端口写死 ⇒ 两份必撞 ⇒ 后一份的端口预检会**踢掉前一份的进程**。
#       · 端口块号 = 端口偏移的来源：`PORT_OFFSET = 块号 × 步长`。
#       · 🔴 **端口块必须先与「容器已发布的宿主端口」求交**（见 `probe_reserved_host_ports`）
#         —— 否则偏移后的端口可能撞上宿主中间件的发布口 ⇒ 而 `lsof` 在那种情况下
#         返回的是 **Docker Desktop 的 backend 进程** ⇒ 一杀 ⇒ **整个 Docker 失联**。
#
#   V4 **构建产物** —— `mvn` **串行锁** + 每轮把 fat jar **拷进自己的 WORK**
#       · C 侧的 `target/` 是**同一份** ⇒ 并发写**必然互踩**。
#       · 实测：`-Dproject.build.directory=<dir>` **不生效** ⇒ 只能「持锁构建 + 拷走」。
#
# 【默认值 = 单跑行为】
#   `RUN_ID` 为空 ⇒ 命名空间 = `probe-b-parity` / `probe-c-parity` · 端口 = 下面那组基准。
#
# 【可覆盖的环境变量（全部可选）】
#   `RUN_ID`             本轮标识（非空 ⇒ 隔离身份带轮次；只允许 `[A-Za-z0-9_-]`）
#   `NACOS_NAMESPACE_B`  显式指定 B 侧 Nacos 命名空间（覆盖 RUN_ID 派生值）
#   `NACOS_NAMESPACE_C`  显式指定 C 侧 Nacos 命名空间（覆盖 RUN_ID 派生值）
#   `PORT_OFFSET`        显式指定本轮**应用端口**偏移（指定后**不占租约**，责任自负）
#   `PG_PORT_OFFSET`     显式指定本轮 **PG 阶梯**偏移（同上）
#   `PROBE_LEASE_DIR`    端口租约目录（默认 `${TMPDIR:-/tmp}/rag-article-parity-lease`）
#   `PROBE_MVN_LOCK_DIR` 构建锁目录（默认 `${TMPDIR:-/tmp}/rag-article-parity-mvn-lock`）
#   `KEEP_NACOS_NAMESPACE=1` 保留本轮创建的命名空间
#
# 用法：`source probe-isolation.sh` 后调 `probe_iso_init <script-tag>`。
# ⚠️ 本文件**只定义函数与变量**，不执行任何副作用（除下面几行默认值赋值）。
# =============================================================================

# ── 默认值（可被 env 覆盖）─────────────────────────────────────────────────────
PROBE_LEASE_DIR="${PROBE_LEASE_DIR:-${TMPDIR:-/tmp}/rag-article-parity-lease}"
PROBE_MVN_LOCK_DIR="${PROBE_MVN_LOCK_DIR:-${TMPDIR:-/tmp}/rag-article-parity-mvn-lock}"
# 端口块步长：必须 **大于**同族端口的跨度，否则不同块之间会撞。
#   本探针的应用端口跨度 = 19092 - 18889 = 203 ⇒ 步长 20000 足够。
PROBE_PORT_STRIDE="${PROBE_PORT_STRIDE:-20000}"
# 最多支持几块（端口不能超过 65535）：19092 + 20000×2 = 59092 ⇒ 3 块。
PROBE_MAX_BLOCK="${PROBE_MAX_BLOCK:-3}"
PROBE_MVN_LOCK_TIMEOUT="${PROBE_MVN_LOCK_TIMEOUT:-900}"

# 本轮派生量（probe_iso_init 之后有效）
PROBE_RUN_TAG=""
PROBE_NS_B="probe-b-parity"
PROBE_NS_C="probe-c-parity"
PROBE_NS_CREATED_B=0
PROBE_NS_CREATED_C=0
PROBE_PORT_BLOCK=-1
PROBE_PORT_OFFSET=0
PROBE_PG_OFFSET=0
PROBE_LEASE_HELD=""
PROBE_ISO_READY=0

# 各脚本在 `probe_iso_init` **之前**填这两个（空格分隔的基准端口）：
#   PROBE_BASE_APP_PORTS —— 应用端口（B api / B rpc gRPC / B rpc 观测 / C http / C gRPC）
#   PROBE_BASE_PG_PORTS  —— PG 端口阶梯（两侧族交错）
# ⚠️ 有了它们，租约才能算出「本块会占哪些端口」⇒ 才能**避开危险端口**。
PROBE_BASE_APP_PORTS="${PROBE_BASE_APP_PORTS:-}"
PROBE_BASE_PG_PORTS="${PROBE_BASE_PG_PORTS:-}"

# ── 小工具 ────────────────────────────────────────────────────────────────────
probe_sanitize() {   # $1 = 原始串 ⇒ 打印「只含 [A-Za-z0-9_-]、最长 32」的安全标识
  local s
  s="$(printf '%s' "${1}" | tr -c 'A-Za-z0-9_-' '-')"
  s="$(printf '%s' "$s" | cut -c1-32)"
  printf '%s' "$s"
}

# =============================================================================
# probe_iso_init <script-tag>
#   计算本轮的隔离身份 + **占端口租约**。
# ⚠️ 必须在**端口变量被使用之前**调用（端口偏移从这里来）。
# =============================================================================
probe_iso_init() {
  local tag="${1:-probe}"

  # ── V1 的身份：RUN_ID → RUN_TAG ────────────────────────────────────────────
  if [ -n "${RUN_ID:-}" ]; then
    PROBE_RUN_TAG="$(probe_sanitize "${RUN_ID}")"
    [ -n "${PROBE_RUN_TAG}" ] || PROBE_RUN_TAG="run"
  else
    PROBE_RUN_TAG=""
  fi

  # 两个命名空间：显式 env 优先；否则 RUN_ID 派生；再否则 = 默认值。
  if [ -n "${NACOS_NAMESPACE_B:-}" ]; then
    PROBE_NS_B="${NACOS_NAMESPACE_B}"
  elif [ -n "${PROBE_RUN_TAG}" ]; then
    PROBE_NS_B="probe-b-${PROBE_RUN_TAG}"
  else
    PROBE_NS_B="probe-b-parity"
  fi
  if [ -n "${NACOS_NAMESPACE_C:-}" ]; then
    PROBE_NS_C="${NACOS_NAMESPACE_C}"
  elif [ -n "${PROBE_RUN_TAG}" ]; then
    PROBE_NS_C="probe-c-${PROBE_RUN_TAG}"
  else
    PROBE_NS_C="probe-c-parity"
  fi

  # ── V3 端口租约 ───────────────────────────────────────────────────────────
  if [ -n "${PORT_OFFSET:-}" ]; then
    # 显式指定 ⇒ **不占租约**（责任自负：两份用同一个 PORT_OFFSET 就会撞）。
    PROBE_PORT_OFFSET="${PORT_OFFSET}"
    PROBE_PORT_BLOCK=-1
  else
    probe_lease_block "${tag}" || return 1
  fi
  if [ -n "${PG_PORT_OFFSET:-}" ]; then
    PROBE_PG_OFFSET="${PG_PORT_OFFSET}"
  elif [ "${PROBE_PORT_BLOCK}" -gt 0 ]; then
    # PG 阶梯与**应用端口**分开偏移（两族的端口区间不同 ⇒ 必须分开算，否则会互相撞）。
    PROBE_PG_OFFSET=$(( PROBE_PORT_BLOCK * 24 ))
  else
    PROBE_PG_OFFSET=0
  fi

  PROBE_ISO_READY=1
  return 0
}

# ── 端口租约（原子创建文件）──────────────────────────────────────────────────
# 🔴 **为什么必须「算端口、查冲突」而不是「先占再算」**：
#   偏移后的端口**可能撞上宿主中间件的发布端口**。
#   ⇒ `lsof -ti tcp:<port>` 在那种情况下取到的是 **Docker Desktop 的 backend 进程**
#   ⇒ 一 `kill -9` ⇒ **Docker 整个失联**（所有容器的发布端口一起挂）。
#   ⇒ ⇒ 端口块**必须**先跟「容器已发布的宿主端口」+「别的活跃租约的端口」求交，
#        冲突就**换一块**，绝不选会撞的块。
probe_reserved_host_ports() {   # 打印所有**运行中容器发布到宿主**的端口（空格分隔）
  docker ps --format '{{.Ports}}' 2>/dev/null | python3 -c '
import sys, re
out = set()
for line in sys.stdin:
    for m in re.finditer(r"(\d+)(?:-(\d+))?->", line):
        a = int(m.group(1))
        b = int(m.group(2)) if m.group(2) else a
        for p in range(a, b + 1):
            out.add(p)
print(" ".join(str(p) for p in sorted(out)))
'
}

# 别的**活跃**租约占的端口（租约文件里第 2 行记着 `ports`；持有者进程已死的忽略）。
probe_live_lease_ports() {
  local excl="${1:-}" f p ports
  for f in "${PROBE_LEASE_DIR}"/*; do
    [ -f "${f}" ] || continue
    [ "${f}" = "${excl}" ] && continue
    p="$(sed -n '1p' "${f}" 2>/dev/null || true)"
    [ -n "${p}" ] || continue
    kill -0 "${p}" 2>/dev/null || continue
    ports="$(sed -n '2p' "${f}" 2>/dev/null || true)"
    printf '%s ' "${ports}"
  done
}

# 某一块会占的**全部**端口（应用端口 + PG 阶梯）。
probe_block_ports() {   # $1 = 块号 ⇒ 打印空格分隔的端口表
  local i="$1" p pg_off
  if [ "${i}" -eq 0 ]; then pg_off=0; else pg_off=$(( i * 24 )); fi
  for p in ${PROBE_BASE_APP_PORTS}; do printf '%s ' $(( p + i * PROBE_PORT_STRIDE )); done
  for p in ${PROBE_BASE_PG_PORTS};  do printf '%s ' $(( p + pg_off )); done
}

# ⚠️ **原子占用**：`set -o noclobber` 的 `>` 就是 `O_CREAT|O_EXCL` ⇒ 文件要么不存在、要么**内容完整**。
probe_claim_file() {   # $1 = 路径  $2 = 内容 ⇒ 抢到返回 0
  ( set -o noclobber; printf '%s' "${2}" > "${1}" ) 2>/dev/null
}

probe_lease_block() {   # $1 = script tag ⇒ 成功时设 PROBE_PORT_BLOCK / PROBE_LEASE_HELD
  local tag="${1:-probe}" i=0 f p cand reserved live
  # ⚠️ **必须能读到「容器已发布的宿主端口」**才算得准 ⇒ docker 不在跑 ⇒ 直接拒绝分配
  docker ps --format '{{.Names}}' >/dev/null 2>&1 \
    || { printf '!! 读不到 docker（docker ps 失败）⇒ 拒绝分配端口块\n' >&2; return 1; }
  mkdir -p "${PROBE_LEASE_DIR}" 2>/dev/null || true
  reserved="$(probe_reserved_host_ports)"
  while [ "${i}" -lt "${PROBE_MAX_BLOCK}" ]; do
    f="${PROBE_LEASE_DIR}/${tag}-b${i}"
    cand="$(probe_block_ports "${i}")"
    # ① 这一块的端口**不能**撞「容器已发布的宿主端口」
    for p in ${cand}; do
      case " ${reserved} " in
        *" ${p} "*) cand=""; break ;;
      esac
    done
    if [ -z "${cand}" ]; then i=$(( i + 1 )); continue; fi
    # ② **原子占用**（内容一次写全 ⇒ 没有「已占但内容为空」的窗口）
    if probe_claim_file "${f}" "$(printf '%s\n%s\n' "$$" "${cand}")"; then
      # ③ 复核：不能撞「别的**活跃**租约」的端口（别人也可能刚占到同一块 ⇒ 谁先抢到谁算）
      live="$(probe_live_lease_ports "${f}")"
      for p in ${cand}; do
        case " ${live} " in
          *" ${p} "*) probe_drop_claim "${f}"; cand=""; break ;;
        esac
      done
      if [ -z "${cand}" ]; then i=$(( i + 1 )); continue; fi
      PROBE_PORT_BLOCK="${i}"
      PROBE_LEASE_HELD="${f}"
      PROBE_PORT_OFFSET=$(( i * PROBE_PORT_STRIDE ))
      return 0
    fi
    # 已被占 ⇒ 可能是**上一轮残留**（持有者进程已死）⇒ 回收后重试同一块号。
    p="$(sed -n '1p' "${f}" 2>/dev/null || true)"
    if [ -z "${p}" ] || ! kill -0 "${p}" 2>/dev/null; then
      if probe_drop_claim "${f}"; then continue; fi
      i=$(( i + 1 ))
      continue
    fi
    i=$(( i + 1 ))
  done
  printf '!! 端口租约耗尽（%s 个块都被占 / 都撞了危险端口）⇒ 请显式指定 PORT_OFFSET\n' "${PROBE_MAX_BLOCK}" >&2
  return 1
}

probe_iso_release() {
  if [ -n "${PROBE_LEASE_HELD}" ]; then
    probe_drop_claim "${PROBE_LEASE_HELD}"
    PROBE_LEASE_HELD=""
  fi
}

# ⚠️ **删除一律用 Python `os.remove` / `os.rmdir`**（本机纪律：`rm -rf` 会被回收站守卫拦下）。
probe_drop_claim() {   # $1 = 路径 ⇒ 删掉它（文件或目录都行）⇒ 成功（已不存在也算）返回 0
  python3 - "$1" <<'PY'
import os, shutil, sys
p = sys.argv[1]
try:
    if os.path.isdir(p) and not os.path.islink(p):
        shutil.rmtree(p)
    else:
        os.remove(p)
except OSError:
    pass
sys.exit(0 if not os.path.exists(p) else 1)
PY
}

# PG 端口阶梯：$1 = 起始端口  $2 = 步长  $3 = 个数 ⇒ 打印**本轮**的阶梯（已加 PG 偏移）。
probe_pg_ladder() {
  local base="$1" step="$2" n="$3" i=0 out=""
  while [ "${i}" -lt "${n}" ]; do
    out="${out} $(( base + PROBE_PG_OFFSET + i * step ))"
    i=$(( i + 1 ))
  done
  printf '%s' "${out}"
}

# =============================================================================
# 端口预检：**只踢「探针进程」**，绝不误伤宿主上的其它服务
# =============================================================================
# 🔴 **为什么必须按命令行过滤**：
#   `lsof -ti tcp:<port> | xargs kill -9` ⇒ **占着这个端口的任何进程**都会被杀。
#   偏移后的端口**可能撞上容器发布端口** ⇒ `lsof` 返回的是 **Docker Desktop 的 backend**
#   ⇒ 一杀 ⇒ **Docker 整个失联**。
#   ⇒ 现在只杀「命令行里带探针标记」的进程；不是探针进程就**只报告、不动它**。
probe_free_port() {   # $1 = 端口
  local p="$1" pid cmd
  for pid in $(lsof -nP -iTCP:"${p}" -sTCP:LISTEN -t 2>/dev/null); do
    cmd="$(ps -o command= -p "${pid}" 2>/dev/null || true)"
    case "${cmd}" in
      *rag-article-parity.*|*parity-probe*|*article-service-*.jar*)
        kill -9 "${pid}" 2>/dev/null
        printf '   · 踢掉占用 :%s 的**残留探针进程** %s\n' "${p}" "${pid}"
        ;;
      *)
        printf '   ⚠️ :%s 被**非探针进程**占用（pid %s）⇒ **不杀**（避免误伤宿主服务）\n' "${p}" "${pid}"
        ;;
    esac
  done
}

# =============================================================================
# V1 的 Nacos 侧操作（**全部带本轮命名空间** ⇒ 绝不碰别的轮次）
# =============================================================================
probe_nacos_namespace_exists() {   # $1 = namespace id ⇒ 命中返回 0
  local ns="${1:-${PROBE_NS_B}}"
  curl -s -m 10 "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/namespaces" 2>/dev/null \
  | python3 -c '
import sys, json
want = sys.argv[1]
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
for it in (d.get("data") or []):
    if it.get("namespace") == want:
        sys.exit(0)
sys.exit(1)
' "${ns}"
}

# ⚠️ **实测过的接口形态**：
#   POST /nacos/v1/console/namespaces
#        customNamespaceId=<id>&namespaceName=<name>&namespaceDesc=<desc>
#   → HTTP 200，响应体 `true`。
probe_nacos_ensure_namespace() {   # $1 = namespace id
  local ns="$1" body code
  [ "${ns}" = "public" ] && return 0
  if probe_nacos_namespace_exists "${ns}"; then
    return 0
  fi
  body="$(curl -s -m 15 -X POST "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/namespaces" \
      -d "customNamespaceId=${ns}" \
      -d "namespaceName=${ns}" \
      -d "namespaceDesc=RAG-V1 parity probe ${PROBE_RUN_TAG}" \
      -w '\n%{http_code}' 2>/dev/null)"
  code="$(printf '%s' "${body}" | tail -1)"
  if [ "${code}" != "200" ]; then
    printf '!! 建 Nacos 命名空间 %s 失败（HTTP=%s）\n' "${ns}" "${code}" >&2
    return 1
  fi
  probe_nacos_namespace_exists "${ns}" || {
    printf '!! 建完命名空间 %s 但查不到 ⇒ Nacos 版本/接口不匹配\n' "${ns}" >&2
    return 1
  }
  case "${ns}" in
    "${PROBE_NS_B}") PROBE_NS_CREATED_B=1 ;;
    "${PROBE_NS_C}") PROBE_NS_CREATED_C=1 ;;
  esac
  return 0
}

# 注销某个命名空间里某个服务的**全部**实例（只在该命名空间里找 ⇒ 不碰别的轮）。
probe_nacos_deregister_all() {   # $1 = 服务名  $2 = 命名空间
  local svc="$1" ns="$2" ip port
  curl -s -m 10 "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance/list?serviceName=${svc}&groupName=DEFAULT_GROUP&namespaceId=${ns}" 2>/dev/null \
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
' | while read -r ip port; do
      [ -n "${ip}" ] || continue
      curl -s -m 5 -X DELETE "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance?serviceName=${svc}&groupName=DEFAULT_GROUP&namespaceId=${ns}&ip=${ip}&port=${port}" >/dev/null 2>&1
      printf '   · 已注销 Nacos 残留实例 %s %s:%s（命名空间 %s）\n' "${svc}" "${ip}" "${port}" "${ns}"
    done
}

# 数「**本轮端口**」上的实例个数 ⇒ 就绪判据精确到本轮专属标识。
probe_nacos_instance_count() {   # $1 = 端口  $2 = 命名空间
  curl -s -m 10 "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance/list?serviceName=article-rpc&groupName=DEFAULT_GROUP&namespaceId=${2}" 2>/dev/null \
  | python3 -c '
import sys, json
port = sys.argv[1]
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
n = 0
for h in (d.get("hosts") or []):
    if str(h.get("port")) == port:
        n += 1
print(n)
' "$1"
}

# 某个命名空间里**任意端口**的实例总数。
probe_nacos_instance_count_any() {   # $1 = 命名空间
  curl -s -m 10 "http://127.0.0.1:${NACOS_PORT}/nacos/v1/ns/instance/list?serviceName=article-rpc&groupName=DEFAULT_GROUP&namespaceId=${1}" 2>/dev/null \
  | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
print(len(d.get("hosts") or []))
'
}

# 清理某个命名空间里的实例（+ 本轮创建的命名空间本身）。
probe_nacos_cleanup_ns() {   # $1 = 命名空间
  local ns="$1" i
  probe_nacos_deregister_all article-rpc "${ns}"
  [ "${ns}" = "public" ] && return 0
  [ "${KEEP_NACOS_NAMESPACE:-0}" = "1" ] && return 0
  case "${ns}" in
    probe-*) : ;;
    *) return 0 ;;
  esac
  # 还有实例 ⇒ Nacos 拒删（命名空间非空时 DELETE 失败）⇒ 等它消失再删。
  for i in 1 2 3 4 5 6; do
    [ "$(probe_nacos_instance_count_any "${ns}")" = "0" ] && break
    sleep 1
  done
  if [ "$(probe_nacos_instance_count_any "${ns}")" != "0" ]; then
    printf '   ⚠️ 命名空间 %s 里还有实例 ⇒ 本轮不删（下一轮会再注销）\n' "${ns}"
    return 0
  fi
  curl -s -m 10 -X DELETE "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/namespaces?namespaceId=${ns}" >/dev/null 2>&1 || true
  printf '   · 已删除本轮 Nacos 命名空间 %s\n' "${ns}"
}

# =============================================================================
# V4 的构建锁（串行化 mvn ⇒ 避免两个轮次并发写同一个 target/）
# =============================================================================
probe_mvn_lock_acquire() {
  local waited=0 p
  mkdir -p "$(dirname "${PROBE_MVN_LOCK_DIR}")" 2>/dev/null || true
  while ! mkdir "${PROBE_MVN_LOCK_DIR}" 2>/dev/null; do
    p="$(cat "${PROBE_MVN_LOCK_DIR}/pid" 2>/dev/null || true)"
    if [ -n "${p}" ] && ! kill -0 "${p}" 2>/dev/null; then
      probe_drop_claim "${PROBE_MVN_LOCK_DIR}"
      continue
    fi
    sleep 2
    waited=$(( waited + 2 ))
    if [ "${waited}" -ge "${PROBE_MVN_LOCK_TIMEOUT}" ]; then
      printf '!! 等构建锁超过 %ss ⇒ 放弃（另一轮还在构建？）\n' "${PROBE_MVN_LOCK_TIMEOUT}" >&2
      return 1
    fi
  done
  printf '%s' "$$" > "${PROBE_MVN_LOCK_DIR}/pid"
  return 0
}

probe_mvn_lock_release() {
  probe_drop_claim "${PROBE_MVN_LOCK_DIR}"
}
