#!/usr/bin/env bash
# ============================================================================
# favorite 域对拍探针的 **gRPC 面**变异验证 —— 证明「gRPC 面的判据有牙齿」，不是恒绿。
# ----------------------------------------------------------------------------
# 变的是什么：C 侧 `FavoriteFolderMapper` 的 `ORDER BY create_time DESC`
#             ⇒ 改成 `ASC` ⇒ **收藏夹列表的展示顺序反转**（9001 跑到 9002 前面）。
#
# 为什么挑这个点（与 HTTP 面同一个变异点，但**验的是另一套判据**）：
#   · 它是 **L2 业务语义**（列表顺序），不是 L1 错误码 ⇒ 错得「很轻」；
#   · 单测**很难**抓到（要同时比对整段 body 的字节顺序）；
#   · 🔴 但 gRPC 面的探针是**逐字节**比 protobuf body ⇒ ⇒ **必然被抓到**。
#
# 🔴 **为什么必须 SKIP_HTTP=1 单独跑**：
#   同一个变异**也会让 HTTP 面变红** ⇒ HTTP 面在 run.sh 里**先**跑、**先** exit(1)
#   ⇒ ⇒ 不跳过就**永远走不到** gRPC 面 ⇒ ⇒ **gRPC 面的牙齿就永远没被验过**。
#   ⇒ ⇒ 这里用 SKIP_HTTP=1 只跑 gRPC 面 ⇒ ⇒ 红的**必须**来自 gRPC 面的 BC 校验。
#
# 判定（两次跑）：
#   ① 变异后跑（SKIP_HTTP=1）⇒ **必须红**（EXIT=1）且**必须**命中 gRPC 面的
#      `c11_list_folders_ok`（收藏夹列表的响应字节）；
#   ② 还原后跑（SKIP_HTTP=1）⇒ **必须绿**（EXIT=0）。
#   ⇒ ⇒ 「红→绿」证明：① gRPC 面的判据确实在检测那个行为；② 变异确实被还原干净。
#
# ⚠️ 退出前**必还原**（trap + 文件备份）⇒ 净改动 = 0。
#
# 用法：bash tools/difftest/favorite/mutate-grpc-parity.sh
# ============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"
TARGET="${C_ROOT}/service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFolderMapper.java"
BACKUP="$(mktemp)"
# ⚠️ **红必须命中 gRPC 面这一条**（不是 HTTP 面的 s01_list_folders）⇒
#   否则「红」可能来自别的面 ⇒ 结论不可信。
EXPECT_RED_CASE="c11_list_folders_ok"
MUT_LOG="$(mktemp)"

say() { printf '\033[1;36m[mutate-grpc-parity/favorite]\033[0m %s\n' "$*"; }
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
# 🔴🔴 **锚点必须唯一**（含缩进）：`ORDER BY create_time DESC` 这串在 **javadoc 里也出现一次**
#   ⇒ 只按字符串匹配会命中 **2 次** ⇒ ⇒ 变异**没生效**。
#   ⇒ 命中数 ≠ 1 ⇒ 中止 ⇒ **绝不**继续跑（继续跑 = 把「没变异」误判成「判据恒绿」）。
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

say "变异后跑对拍（SKIP_HTTP=1 ⇒ 只跑 gRPC 面；期望：红，且命中 ${EXPECT_RED_CASE}）"
SKIP_HTTP=1 bash "${HERE}/run.sh" > "${MUT_LOG}" 2>&1
RED_RC=$?
if [ "${RED_RC}" -ne 1 ]; then
  bad "变异后 gRPC 面对拍 **没有红**（exit=${RED_RC}）⇒ 判据可能恒绿 ⇒ 探针无效"
  tail -40 "${MUT_LOG}"
  exit 1
fi
if ! grep -q "${EXPECT_RED_CASE}" "${MUT_LOG}"; then
  bad "变异后红了，但**没有**命中 ${EXPECT_RED_CASE} ⇒ 红的可能是别的原因 ⇒ 不可信"
  tail -40 "${MUT_LOG}"
  exit 1
fi
# 🔴 **必须确认红在 gRPC 面的 BC 校验上**，而不是 HTTP 面 / 环境问题上
if ! grep -q "BC 校验" "${MUT_LOG}"; then
  bad "日志里没有 gRPC 面的『BC 校验』字样 ⇒ 红不是 gRPC 面判的 ⇒ 不可信"
  tail -40 "${MUT_LOG}"
  exit 1
fi
ok "变异后：EXIT=1 且命中 ${EXPECT_RED_CASE}（BC 校验段）⇒ gRPC 面判据确实在检测那个行为"

# ── ② 还原 ───────────────────────────────────────────────────────────────
say "还原变异"
cp "${BACKUP}" "${TARGET}"

say "还原后跑对拍（SKIP_HTTP=1 ⇒ 只跑 gRPC 面；期望：绿）"
SKIP_HTTP=1 bash "${HERE}/run.sh" > "${MUT_LOG}" 2>&1
GREEN_RC=$?
if [ "${GREEN_RC}" -ne 0 ]; then
  bad "还原后 gRPC 面对拍仍然红（exit=${GREEN_RC}）⇒ 变异没还原干净"
  tail -40 "${MUT_LOG}"
  exit 1
fi
ok "还原后：EXIT=0 ⇒ 净改动 = 0"

echo
ok "gRPC 面变异验证通过（红 → 绿）⇒ gRPC 面判据有牙齿"
exit 0
