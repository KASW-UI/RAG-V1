#!/usr/bin/env bash
# =============================================================================
# mutate-parity.sh —— article 域「B 侧 ↔ C 侧」双侧活体对拍探针的**变异验证**
#
# 【为什么必须有这个脚本】
#   AGENTS.md §3.3.3：探针必须有「能真正触发它」的变异，否则无法排除「假绿」。
#   一个恒绿的探针 = 「两边都没起起来」也报绿 ⇒ 毫无证据价值。
#   ⇒ 本脚本让探针在**人为制造差异**后**精确转红**。
#
# 【三条变异】
#   M1 **数据侧 · 改 C 侧**  ⇒ 探针必须报差异
#       手法：播种之后，把 **C 侧**那篇文章的 title 改掉。
#       为什么：这直接证明「比较面真的读到了两侧各自的数据库」
#             ⇒ 若探针其实在跟**自己**比（两侧连同一个库 / 读同一个缓存），
#                这一改**不会**被发现 ⇒ 转红即证伪。
#   M2 **数据侧 · 改 B 侧**  ⇒ 探针必须报差异
#       手法：同上，但改 **B 侧** ⇒ 证明检测**不是单向的**
#             （不是「C 侧特例」）。
#   M3 **基线侧 · 改冻结基线** ⇒ 探针必须报差异
#       手法：把 `expected.txt` 里一枚 hex 改掉（跑完立刻还原 ＋ sha256 核验）。
#       为什么：这证明「冻结的期望值**确实参与判定**」
#             ⇒ 不是「只比两侧、不看基线」的自欺。
#
# 【为什么不改产品代码】
#   B 侧探针的 M4 改的是**生产码**（`ArticlePublicDomainService.java`）⇒ 会临时动产品代码。
#   ⇒ 本脚本**不碰任何产品代码**：M1/M2 只改**本轮探针自己建的**数据库
#     （每轮 PG 容器都是新建的 ⇒ 跑完即消失 ⇒ **自动还原**），
#     M3 只改**工具面**的 `expected.txt`（自带还原 ＋ sha256 核验）。
#
# 【退出码】
#   0 = 三条变异**全部精确转红**且基线已还原
#   1 = 有变异**没有转红**（探针有假绿嫌疑）⇒ 或还原失败
#   2 = 环境问题（探针自己没跑起来）
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="${HERE}/run.sh"
EXPECTED="${HERE}/expected.txt"
say() { printf '%s\n' "$*"; }

FAILED=0
run_probe() {   # $1 = 标签  $2 = 期望退出码 ⇒ 打印实际退出码
  local tag="$1" want="$2" rc
  bash "${RUN}" >"/tmp/parity-mutate-${tag}.log" 2>&1
  rc=$?
  say "   探针退出码 = ${rc}（期望 ${want}）"
  return 0
}

[ -f "${EXPECTED}" ] || { say "!! 缺少冻结基线 ${EXPECTED} ⇒ 先跑 FREEZE=1"; exit 2; }
EXPECTED_SHA_BEFORE="$(shasum -a 256 "${EXPECTED}" | cut -d' ' -f1)"

# 🔴 **变异轮次不重新构建 C 侧**（`PROBE_SKIP_BUILD=1`）⇒ 三条变异可以快速连跑。
#    理由：变异改的是**数据**，与 jar 无关 ⇒ 复用同一份产物完全等价。
#    ⚠️ 但**必须先确认 jar 存在**（不存在就退回「带构建」跑，别拿空产物去测）。
C_JAR_REL="service/article/target/article-service-1.0-SNAPSHOT.jar"
RAG_V1_ROOT="$(cd "${HERE}/../../.." && pwd)"
SKIP_BUILD=""
[ -f "${RAG_V1_ROOT}/${C_JAR_REL}" ] && SKIP_BUILD="PROBE_SKIP_BUILD=1"
[ -n "${SKIP_BUILD}" ] && say "   · 复用已有 C 侧 jar（${C_JAR_REL}）⇒ 变异轮次不重新构建"

# ══════════════════════════ M1 · 改 C 侧数据 ⇒ 必须报差异 ═════════════════════
say "== M1：改 **C 侧**数据库的 title ⇒ 期望探针 EXIT=1 =="
PROBE_MUTATE_CMD='docker exec "${PROBE_C_PG_CONTAINER}" psql -U admin -d first_db -c "UPDATE article SET title='"'"'变异标题-M1'"'"' WHERE id='"'"'"${AID_LEGACY}"'"'"'"' \
  env ${SKIP_BUILD} bash "${RUN}" >/tmp/parity-mutate-M1.log 2>&1
RC=$?
say "   探针退出码 = ${RC}（期望 1）"
if [ "${RC}" != "1" ]; then
  say "   ❌ M1 没有转红 ⇒ 探针可能假绿"
  FAILED=1
else
  say "   ✅ M1 精确转红（探针确实读到了 C 侧的数据）"
  say "      —— 报差异的用例："
  grep -E '^   ❌ ' /tmp/parity-mutate-M1.log | sed 's/^/      /' | head -10
fi

# ══════════════════════════ M2 · 改 B 侧数据 ⇒ 必须报差异 ═════════════════════
say "== M2：改 **B 侧**数据库的 title ⇒ 期望探针 EXIT=1 =="
PROBE_MUTATE_CMD='docker exec "${PROBE_B_PG_CONTAINER}" psql -U admin -d first_db -c "UPDATE article SET title='"'"'变异标题-M2'"'"' WHERE id='"'"'"${AID_LEGACY}"'"'"'"' \
  env ${SKIP_BUILD} bash "${RUN}" >/tmp/parity-mutate-M2.log 2>&1
RC=$?
say "   探针退出码 = ${RC}（期望 1）"
if [ "${RC}" != "1" ]; then
  say "   ❌ M2 没有转红 ⇒ 探针可能假绿"
  FAILED=1
else
  say "   ✅ M2 精确转红（检测不是单向的）"
  say "      —— 报差异的用例："
  grep -E '^   ❌ ' /tmp/parity-mutate-M2.log | sed 's/^/      /' | head -10
fi

# ══════════════════════════ M3 · 改冻结基线 ⇒ 必须报差异 ═════════════════════
say "== M3：改冻结基线里一枚 hex ⇒ 期望探针 EXIT=1 =="
cp "${EXPECTED}" "${EXPECTED}.bak"
# 改第一条 CASE 的 hex 的第 1 个字符（0↔1，其余不动）⇒ 一定是个不同的值
python3 - "${EXPECTED}" <<'PY'
import sys
p = sys.argv[1]
lines = open(p, encoding="utf-8").read().split("\n")
for i, ln in enumerate(lines):
    if ln.startswith("CASE|"):
        parts = ln.split("|")
        h = parts[4]
        parts[4] = ("1" if h[0] != "1" else "2") + h[1:]
        lines[i] = "|".join(parts)
        break
open(p, "w", encoding="utf-8").write("\n".join(lines))
PY
env ${SKIP_BUILD} bash "${RUN}" >/tmp/parity-mutate-M3.log 2>&1
RC=$?
say "   探针退出码 = ${RC}（期望 1）"
if [ "${RC}" != "1" ]; then
  say "   ❌ M3 没有转红 ⇒ 冻结基线**没有参与判定**"
  FAILED=1
else
  say "   ✅ M3 精确转红（冻结期望值确实参与判定）"
  say "      —— 报差异的用例："
  grep -E '^   ❌ ' /tmp/parity-mutate-M3.log | sed 's/^/      /' | head -10
fi
# 🔴 **还原基线**（工具面改动 ⇒ 必须还原 ＋ sha256 核验）
cp "${EXPECTED}.bak" "${EXPECTED}"
python3 -c 'import os,sys; os.remove(sys.argv[1])' "${EXPECTED}.bak"
EXPECTED_SHA_AFTER="$(shasum -a 256 "${EXPECTED}" | cut -d' ' -f1)"
if [ "${EXPECTED_SHA_BEFORE}" != "${EXPECTED_SHA_AFTER}" ]; then
  say "   ❌ 基线还原核验失败（sha256 ${EXPECTED_SHA_BEFORE} → ${EXPECTED_SHA_AFTER}）"
  FAILED=1
else
  say "   ✅ 基线已还原（sha256 ${EXPECTED_SHA_AFTER}）"
fi

echo
if [ "${FAILED}" = "0" ]; then
  say "✅ 三条变异全部精确转红 ＋ 基线已还原 ⇒ 探针**不是恒绿**"
  exit 0
fi
say "❌ 变异验证失败（探针存在假绿嫌疑）"
exit 1
