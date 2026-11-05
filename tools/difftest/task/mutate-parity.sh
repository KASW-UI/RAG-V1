#!/usr/bin/env bash
#
# task 域对拍探针的**变异验证** —— 证明「探针的判据不是假绿」。
#
# 🔴 **纪律**（AGENTS §3.3 · 「跑绿 ≠ 本来就绿」）：每条变异都要求**三件事同时成立**：
#   ① **目标断言转红** —— 探针 EXIT != 0，**且**指定的那几条用例在明细里变成 `DIFF`；
#   ② **对照断言仍绿** —— 其它用例仍是 `SAME`（⇒ 证明红的是**被测的那一处**，不是「全红」）；
#   ③ **还原后回绿** —— 把源码还原、重建、重跑 ⇒ 探针 EXIT == 0。
#   ⇒ ⚠️ **「变异后变红」本身不足以证明判据有牙齿** —— 若是**编译失败**导致全红，
#      那也是红的 ⇒ 没有信息量 ⇒ 所以必须「目标红 ＋ 对照绿」同时成立。
#
# ⚠️ **B 侧只读**：本脚本只改 **C 侧**源码 ⇒ 跑探针时 `SKIP_B_BUILD=1`（B 侧产物不变）。
#   ⇒ 还原后必须把 C 侧源码**逐字节**还原（`cmp` 断言）⇒ 不留残余。
#
# 用法：
#   bash tools/difftest/task/mutate-parity.sh            # 跑全部三条
#   bash tools/difftest/task/mutate-parity.sh M-P1       # 只跑一条
#
# 退出码：0 = 全部变异「目标红 ＋ 对照绿 ＋ 还原绿」· 1 = 有变异不满足判据 · 2 = 环境问题。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"
WORK="$(mktemp -d)"
REPORT="${WORK}/report.txt"
BACKUP="${WORK}/backup"
mkdir -p "${BACKUP}"

say()  { printf '%s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
die()  { printf '!! %s\n' "$*" >&2; exit 2; }

cleanup() { local rc=$?; rm -rf "${WORK}" 2>/dev/null; exit "${rc}"; }
trap cleanup EXIT

# ── 变异表 ────────────────────────────────────────────────────────────────────
# 格式：<ID>|<C 侧文件相对路径>|<旧串>|<新串>|<应转红的用例（逗号分隔）>
# ⚠️ 每条旧串都必须**只命中 1 处**（应用时断言 `count(old) == 1`）⇒ 命中多处即失败。
MUTATIONS=(
  'M-P1|service/task/src/main/java/com/rag/task/dto/task/GetTaskReq.java|public static final String USER_ID_INVALID = "user_id invalid";|public static final String USER_ID_INVALID = "user_id invalid!";|err-missing-id,err-empty-id,err-zero,err-neg,err-null,err-fraction,err-float-zero'
  'M-P2|service/task/src/main/java/com/rag/task/service/TaskDefs.java|new TaskDef(190L, "文章获赞任务", "有一篇文章点赞大于3", 3L),|new TaskDef(190L, "文章获赞任务", "有一篇文章点赞大于3", 4L),|ok-redis-hit,ok-pg-fallback,ok-empty,ok-numeric-id,ok-text-plain,ok-no-content-type,ok-trim,ok-unknown-field,ok-malformed-cache,ok-malformed-count'
  'M-P3|service/task/src/main/java/com/rag/task/service/TaskRedisKeys.java|return "task:progress:" + uid;|return "task:progress-mutated:" + uid;|ok-redis-hit,ok-text-plain,ok-no-content-type,ok-trim,ok-unknown-field,ok-malformed-cache,ok-malformed-count'
)

# ── 对照用例（**必须仍绿**）：挑那些与变异无关的 ────────────────────────────────
CONTROL_CASES="route-404,route-405"

WANT=("$@")
if [ "${#WANT[@]}" -eq 0 ]; then
    WANT=()
    for m in "${MUTATIONS[@]}"; do WANT+=("${m%%|*}"); done
fi

apply_mutation() {   # $1=文件绝对路径 $2=旧串 $3=新串
    python3 - "$1" "$2" "$3" <<'PY'
import sys, pathlib
path, old, new = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
text = path.read_text(encoding="utf-8")
count = text.count(old)
if count != 1:
    sys.stderr.write(f"!! 锚点命中 {count} 处（要求恰好 1 处）⇒ 拒绝变异\n")
    raise SystemExit(1)
path.write_text(text.replace(old, new), encoding="utf-8")
sys.stdout.write(f"   · 已变异（锚点命中 1 处）\n")
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
    SKIP_B_BUILD=1 REPORT_OUT="${REPORT}" bash "${HERE}/run.sh" >"${WORK}/probe-${label}.log" 2>&1
    rc=$?
    echo "${rc}"
}

FAILED=0
for m in "${MUTATIONS[@]}"; do
    IFS='|' read -r id rel old new expect_diff <<< "${m}"
    if [[ " ${WANT[*]} " != *" ${id} "* ]]; then
        continue
    fi
    step "${id}：${rel##*/} · 应转红：${expect_diff}"
    file="${C_ROOT}/${rel}"
    [ -f "${file}" ] || die "文件不存在：${file}"
    cp "${file}" "${BACKUP}/${id}.bak"

    # ── ① 变异 + 重建 + 跑探针 ⇒ 目标必须转红 ───────────────────────────────
    apply_mutation "${file}" "${old}" "${new}" || { FAILED=1; continue; }
    build_c
    rc="$(run_probe "mutated")"
    say "   · 变异态探针 EXIT=${rc}"
    if [ "${rc}" = "0" ]; then
        say "   !! 变异后探针仍绿 ⇒ **判据没被触发**（这条变异对探针无感）"
        FAILED=1
    else
        say "   ✓ 变异后探针转红"
        python3 "${HERE}/judge_report.py" "${REPORT}" \
            --expect-diff "${expect_diff}" --expect-same "${CONTROL_CASES}" --min-same 3 \
            || FAILED=1
    fi

    # ── ② 还原 + 重建 + 重跑 ⇒ 必须回绿 ─────────────────────────────────────
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
    say "   ✅ 全部变异满足「目标红 ＋ 对照绿 ＋ 还原绿」⇒ 探针判据有牙齿"
    exit 0
fi
say "   ❌ 有变异未满足判据"
exit 1
