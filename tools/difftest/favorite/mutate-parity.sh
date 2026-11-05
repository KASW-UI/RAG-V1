#!/usr/bin/env bash
# ============================================================================
# favorite 域对拍探针的**变异验证** —— 证明「判据有牙齿」，不是恒绿。
# ----------------------------------------------------------------------------
# 变的是什么：C 侧 `FavoriteFolderMapper` 的 `ORDER BY create_time DESC`
#             ⇒ 改成 `ASC` ⇒ **收藏夹列表的展示顺序反转**（9001 跑到 9002 前面）。
#
# 为什么挑这个点：
#   · 它是 **L2 业务语义**（列表顺序），不是 L1 错误码 ⇒ 错得「很轻」；
#   · 单测**很难**抓到（要同时比对整段 body 的字节顺序）；
#   · 🔴 但对拍探针是**逐字节**比 body ⇒ ⇒ **必然被抓到**（这正是探针的增量价值）。
#
# 判定（两次跑）：
#   ① 变异后跑 ⇒ **必须红**（EXIT=1）且**必须**命中 s01_list_folders 这一条；
#   ② 还原后跑 ⇒ **必须绿**（EXIT=0）。
#   ⇒ ⇒ 「红→绿」证明：① 判据确实在检测那个行为；② 变异确实被还原干净（净改动 = 0）。
#
# ⚠️ 退出前**必还原**（trap + 文件备份）⇒ 净改动 = 0。
#
# 用法：bash tools/difftest/favorite/mutate-parity.sh
# ============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"
TARGET="${C_ROOT}/service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFolderMapper.java"
BACKUP="$(mktemp)"
EXPECT_RED_CASE="s01_list_folders"
# 变异轮的两份运行日志放临时目录 ⇒ 不污染仓内（MUT_LOG 不在仓库里）
MUT_LOG="$(mktemp)"

say() { printf '\033[1;36m[mutate-parity/favorite]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m  ✅ %s\033[0m\n' "$*"; }
bad() { printf '\033[1;31m  ❌ %s\033[0m\n' "$*"; }
die() { bad "$*"; exit 2; }

[ -f "${TARGET}" ] || die "找不到变异目标：${TARGET}"
cp "${TARGET}" "${BACKUP}"
restore() {
  cp "${BACKUP}" "${TARGET}"
  say "已还原 ${TARGET}（净改动 = 0）"
  rm -f "${BACKUP}"
}
trap restore EXIT

# ── ① 变异 ───────────────────────────────────────────────────────────────
say "变异：ORDER BY create_time DESC ⇒ ASC"
# 🔴🔴 **锚点必须唯一**：`ORDER BY create_time DESC` 这串在** javadoc 里也出现一次**
#   （`{@code ORDER BY create_time DESC} 是 L2 语义`）⇒ 只按字符串匹配会命中 **2 次**
#   ⇒ ⇒ 必须连**缩进**一起匹配（SQL 行是 12 空格缩进，javadoc 里那行不是）。
#   ⇒ 命中数 ≠ 1 ⇒ 变异没生效 ⇒ 若继续往下跑，判据会被**误判成恒绿**。
# 🔴🔴 **变异失败必须中止**：上一版这里 python 断言失败后脚本**继续跑对拍**，
#   结果是「没红」被当成「判据恒绿」⇒ **结论方向完全反了**（变异没生效 ≠ 判据没牙）。
python3 - "${TARGET}" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
old = "\n            ORDER BY create_time DESC\n"
new = "\n            ORDER BY create_time ASC\n"
n = s.count(old)
if n != 1:
    sys.stderr.write(f"变异锚点命中 {n} 次（必须恰好 1 次）⇒ 变异未生效 ⇒ 中止\n")
    sys.exit(3)
p.write_text(s.replace(old, new), encoding="utf-8")
print("  变异已应用")
PY
MUT_RC=$?
if [ "${MUT_RC}" -ne 0 ]; then
  die "变异未生效（exit=${MUT_RC}）⇒ **中止**（继续跑会把『没变异』误判成『判据恒绿』）"
fi
# 变异必须真的落到文件里 ⇒ 再回读一次确认（不靠 python 的退出码自证）
if ! grep -q "ORDER BY create_time ASC" "${TARGET}"; then
  die "回读确认失败：目标文件里没有 ASC ⇒ 变异没生效 ⇒ 中止"
fi
ok "变异已落到源码并回读确认"

say "变异后跑对拍（期望：红，且命中 ${EXPECT_RED_CASE}）"
bash "${HERE}/run.sh" > "${MUT_LOG}" 2>&1
RED_RC=$?
if [ "${RED_RC}" -ne 1 ]; then
  bad "变异后对拍 **没有红**（exit=${RED_RC}）⇒ 判据可能恒绿 ⇒ 探针无效"
  tail -30 "${MUT_LOG}"
  exit 1
fi
if ! grep -q "${EXPECT_RED_CASE}" "${MUT_LOG}"; then
  bad "变异后红了，但**没有**命中 ${EXPECT_RED_CASE} ⇒ 红的可能是别的原因 ⇒ 不可信"
  tail -30 "${MUT_LOG}"
  exit 1
fi
ok "变异后：EXIT=1 且命中 ${EXPECT_RED_CASE} ⇒ 判据确实在检测那个行为"

# ── ② 还原 ───────────────────────────────────────────────────────────────
say "还原变异"
cp "${BACKUP}" "${TARGET}"

say "还原后跑对拍（期望：绿）"
bash "${HERE}/run.sh" > "${MUT_LOG}" 2>&1
GREEN_RC=$?
if [ "${GREEN_RC}" -ne 0 ]; then
  bad "还原后对拍仍然红（exit=${GREEN_RC}）⇒ 变异没还原干净"
  tail -30 "${MUT_LOG}"
  exit 1
fi
ok "还原后：EXIT=0 ⇒ 净改动 = 0"

echo
ok "变异验证通过（红 → 绿）⇒ 判据有牙齿"
exit 0
