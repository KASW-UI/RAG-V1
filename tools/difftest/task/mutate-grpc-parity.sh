#!/usr/bin/env bash
#
# task 域对拍探针的 **gRPC 面**变异验证 —— 证明「gRPC 面探针的判据不是假绿」。
#
# 🔴 **纪律**（AGENTS §3.3 · 「跑绿 ≠ 本来就绿」）：每条变异都要求**四件事同时成立**：
#   ① **gRPC 面目标断言转红** —— gRPC 探针 EXIT != 0，**且**指定用例在 gRPC 明细里变成 `DIFF`；
#   ② **gRPC 面对照断言仍绿** —— 其它用例仍是 `SAME`（⇒ 红的是**被测的那一处**，不是「全红」）；
#   ③ 🧪 **HTTP 面必须「看不见」这条变异** —— HTTP 明细里的 `DIFF` 条数**不变**
#      （仍是 1 条已登记差异 `err-empty-body`）⇒ ⇒ **证明 gRPC 面抓到了 HTTP 面抓不到的东西**
#      （⇒ 这条面**不是 HTTP 面的重复**，是**新增的覆盖**）；
#   ④ **还原后回绿** —— 把源码还原、重建、重跑 ⇒ 探针 EXIT == 0。
#
# ⚠️ **B 侧只读**：本脚本只改 **C 侧**源码 ⇒ 跑探针时 `SKIP_B_BUILD=1`（B 侧产物不变）。
#   ⇒ 还原后必须把 C 侧源码**逐字节**还原（`cmp` 断言）⇒ 不留残余。
#
# 用法：
#   bash tools/difftest/task/mutate-grpc-parity.sh            # 跑全部
#   bash tools/difftest/task/mutate-grpc-parity.sh M-P4       # 只跑一条
#
# 退出码：0 = 全部变异「目标红 ＋ 对照绿 ＋ HTTP 面看不见 ＋ 还原绿」· 1 = 有变异不满足判据 · 2 = 环境问题。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"
WORK="$(mktemp -d)"
HTTP_REPORT="${WORK}/http-report.txt"
GRPC_REPORT="${WORK}/grpc-report.txt"
BACKUP="${WORK}/backup"
mkdir -p "${BACKUP}"

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
die()  { printf '!! %s\n' "$*" >&2; exit 2; }

cleanup() { local rc=$?; rm -rf "${WORK}" 2>/dev/null; exit "${rc}"; }
trap cleanup EXIT

# ── 变异表 ────────────────────────────────────────────────────────────────────
# 格式：<ID>|<C 侧文件相对路径>|<旧串>|<新串>|<gRPC 面应转红的用例（逗号分隔）>
# ⚠️ 每条旧串都必须**只命中 1 处**（应用时断言 `count(old) == 1`）⇒ 命中多处即失败。
MUTATIONS=(
  # 🔴 只动 **gRPC 出口** ⇒ HTTP 路径（`TaskApiService` → `TaskApiSupport.asGrpcStatus`）
  #    **不经过** `TaskGrpcService` ⇒ ⇒ HTTP 面**必须看不见**这条变异。
  'M-P4|service/task/src/main/java/com/rag/task/grpc/TaskGrpcService.java|            observer.onError(e);|            observer.onError(io.grpc.Status.UNKNOWN\n                    .withDescription(e.getStatus().getDescription())\n                    .asRuntimeException()); // MUTATED|err-zero,err-neg'
)

# ── gRPC 面的对照用例（**必须仍绿**）──────────────────────────────────────────
GRPC_CONTROL_CASES="ok-redis-hit,ok-pg-fallback,ok-empty,ok-malformed-cache,ok-malformed-count"

# ── HTTP 面的基线 `DIFF` 条数（**必须不变**）──────────────────────────────────
# ⇒ 1 = `err-empty-body`（DRIFT-TASK-016，已登记的表达层差异）
HTTP_DIFF_BASELINE=1

WANT=("$@")
if [ "${#WANT[@]}" -eq 0 ]; then
    WANT=()
    for m in "${MUTATIONS[@]}"; do WANT+=("${m%%|*}"); done
fi

apply_mutation() {   # $1=文件绝对路径 $2=旧串 $3=新串
    # 🔴 **新串里的 `\n` 是「换行占位符」** —— 变异表是 `|` 分隔的单行文本，
    #    真换行会把它拆成多行 ⇒ 这里统一还原成**真换行**。
    #    ⚠️ **不还原的后果**：Java 文件里会留下字面量 `\n` ⇒ **编译失败**
    #    ⇒ 「红」的原因是编译不过 ⇒ **假红**（AGENTS §3.3 · M-4 那个坑）。
    python3 - "$1" "$2" "$3" <<'PY'
import sys, pathlib
path, old, new = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
new = new.replace("\\n", "\n")
text = path.read_text(encoding="utf-8")
count = text.count(old)
if count != 1:
    sys.stderr.write(f"!! 锚点命中 {count} 处（要求恰好 1 处）⇒ 拒绝变异\n")
    raise SystemExit(1)
path.write_text(text.replace(old, new), encoding="utf-8")
sys.stdout.write("   · 已变异（锚点命中 1 处）\n")
PY
}

restore_mutation() {   # $1=文件绝对路径 $2=备份文件
    cp "${2}" "${1}"
    cmp -s "${1}" "${2}" || { echo "!! 还原后与备份不一致" >&2; return 1; }
    echo "   · 已还原（cmp 逐字节一致）"
}

build_c() {
    ( cd "${C_ROOT}" && mvn -o -q -pl service/task -am package -DskipTests ) \
        >"${WORK}/build.log" 2>&1 || { tail -30 "${WORK}/build.log" >&2; die "C 侧构建失败"; }
}

run_probe() {   # $1=标签 ⇒ 跑一次探针，输出 EXIT 码
    local label="$1" rc
    SKIP_B_BUILD=1 REPORT_OUT="${HTTP_REPORT}" GRPC_REPORT_OUT="${GRPC_REPORT}" \
        bash "${HERE}/run.sh" >"${WORK}/probe-${label}.log" 2>&1
    rc=$?
    echo "${rc}"
}

FAILED=0
for m in "${MUTATIONS[@]}"; do
    IFS='|' read -r id rel old new expect_diff <<< "${m}"
    if [[ " ${WANT[*]} " != *" ${id} "* ]]; then
        continue
    fi
    step "${id}：${rel##*/} · gRPC 面应转红：${expect_diff}"
    file="${C_ROOT}/${rel}"
    [ -f "${file}" ] || die "文件不存在：${file}"
    cp "${file}" "${BACKUP}/${id}.bak"

    # ── ① 变异 + 重建 + 跑探针 ⇒ gRPC 面必须转红 ────────────────────────────
    apply_mutation "${file}" "${old}" "${new}" || { FAILED=1; continue; }
    build_c
    rc="$(run_probe "mutated")"
    say "   · 变异态探针 EXIT=${rc}"
    if [ "${rc}" = "0" ]; then
        say "   !! 变异后探针仍绿 ⇒ **gRPC 面判据没被触发**（这条变异对 gRPC 面无感）"
        FAILED=1
    else
        say "   ✓ 变异后 gRPC 面转红"
        python3 "${HERE}/judge_report.py" "${GRPC_REPORT}" \
            --expect-diff "${expect_diff}" --expect-same "${GRPC_CONTROL_CASES}" --min-same 5 \
            || FAILED=1
    fi

    # ── ② 🧪 HTTP 面必须「看不见」这条变异（gRPC 面不是 HTTP 面的重复）──────
    http_diff_n="$(grep -c '=> DIFF' "${HTTP_REPORT}" 2>/dev/null || echo 0)"
    say "   · HTTP 面 DIFF 条数 = ${http_diff_n}（基线 ${HTTP_DIFF_BASELINE}）"
    if [ "${http_diff_n}" != "${HTTP_DIFF_BASELINE}" ]; then
        say "   !! HTTP 面 DIFF 条数变了 ⇒ 这条变异**不该**影响 HTTP 面 ⇒ 变异选错了锚点"
        FAILED=1
    else
        say "   ✓ HTTP 面看不见这条变异 ⇒ **gRPC 面抓到了 HTTP 面抓不到的东西**"
    fi

    # ── ③ 还原 + 重建 + 重跑 ⇒ 必须回绿 ─────────────────────────────────────
    restore_mutation "${file}" "${BACKUP}/${id}.bak" || { FAILED=1; continue; }
    build_c
    rc="$(run_probe "restored")"
    say "   · 还原态探针 EXIT=${rc}"
    if [ "${rc}" != "0" ]; then
        say "   !! 还原后探针仍红 ⇒ 变异**没被还原干净**或另有问题"
        FAILED=1
    else
        say "   ✓ 还原后回绿"
    fi
done

step "结论"
if [ "${FAILED}" -eq 0 ]; then
    say "   ✅ 全部变异满足「目标红 ＋ 对照绿 ＋ HTTP 面看不见 ＋ 还原绿」⇒ gRPC 面探针判据有牙齿"
    exit 0
fi
say "   ❌ 有变异未满足判据"
exit 1
