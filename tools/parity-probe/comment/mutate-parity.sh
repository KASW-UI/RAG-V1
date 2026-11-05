#!/usr/bin/env bash
# =============================================================================
# mutate-parity.sh —— comment 域「B 侧 ↔ C 侧」双侧活体对拍探针的**变异验证**
#
# 【为什么必须有这个脚本】
#   AGENTS.md §3.3.3：探针必须有「能真正触发它」的变异，否则无法排除「假绿」。
#   一个恒绿的探针 = 「两边都没起起来」也报绿 ⇒ 毫无证据价值。
#   ⇒ 本脚本让探针在**人为制造差异**后**精确转红**。
#
# 【四条变异】
#   M1 **数据侧 · 改 C 侧**  ⇒ 探针必须报差异
#       手法：播种之后，把 **C 侧**那条评论的 content 改掉。
#       为什么：这直接证明「比较面真的读到了两侧各自的数据库」
#             ⇒ 若探针其实在跟**自己**比（两侧连同一个库 / 读同一个缓存），
#                这一改**不会**被发现 ⇒ 转红即证伪。
#   M2 **数据侧 · 改 B 侧**  ⇒ 探针必须报差异
#       手法：同上，但改 **B 侧** ⇒ 证明检测**不是单向的**（不是「C 侧特例」）。
#   M3 **基线侧 · 改冻结基线** ⇒ 探针必须报差异
#       手法：把 `expected.txt` 里一枚 hex 改掉（跑完立刻还原 ＋ sha256 核验）。
#       为什么：这证明「冻结的期望值**确实参与判定**」
#             ⇒ 不是「只比两侧、不看基线」的自欺。
#   M4 **已知差异侧 · 改 known-diff.txt 里的 C 侧字节** ⇒ 探针必须报差异
#       手法：把 `known-diff.txt` 某条 DIFF 的 **C 侧 hex** 改掉。
#       为什么：这证明「已知差异白名单是**更严的断言**，不是**免责清单**」
#             ⇒ 白名单冻结的是**两侧各自的字节** ⇒ 任一侧字节变了都判红。
#
# 【为什么不改产品代码】
#   M1/M2 只改**本轮探针自己建的**数据库（每轮 PG 容器都是新建的 ⇒ 跑完即消失 ⇒ 自动还原），
#   M3/M4 只改**工具面**的两个冻结文件（自带还原 ＋ sha256 核验）。
#   ⇒ 本脚本**不碰任何产品代码**。
#
# 【退出码】
#   0 = 四条变异**全部精确转红**且两个冻结文件已还原
#   1 = 有变异**没有转红**（探针有假绿嫌疑）⇒ 或还原失败
#   2 = 环境问题（探针自己没跑起来）
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="${HERE}/run.sh"
EXPECTED="${HERE}/expected.txt"
KNOWN_DIFF="${HERE}/known-diff.txt"
say() { printf '%s\n' "$*"; }

FAILED=0
[ -f "${EXPECTED}" ] || { say "!! 缺少冻结基线 ${EXPECTED} ⇒ 先跑 FREEZE=1"; exit 2; }
[ -f "${KNOWN_DIFF}" ] || { say "!! 缺少已知差异清单 ${KNOWN_DIFF} ⇒ 先跑 FREEZE=1"; exit 2; }

# 🔴 **改动前先留副本** ⇒ 跑完能原样还原 ＋ sha256 核验。
ORIG_EXPECTED="/tmp/parity-comment-expected.orig.txt"
ORIG_KNOWN="/tmp/parity-comment-known-diff.orig.txt"
python3 - "${EXPECTED}" "${ORIG_EXPECTED}" <<'PY'
import shutil, sys
shutil.copyfile(sys.argv[1], sys.argv[2])
PY
python3 - "${KNOWN_DIFF}" "${ORIG_KNOWN}" <<'PY'
import shutil, sys
shutil.copyfile(sys.argv[1], sys.argv[2])
PY
EXPECTED_SHA_BEFORE="$(shasum -a 256 "${ORIG_EXPECTED}" | cut -d' ' -f1)"
KNOWN_SHA_BEFORE="$(shasum -a 256 "${ORIG_KNOWN}" | cut -d' ' -f1)"

# 🔴 **变异轮次不重新构建 C 侧**（`PROBE_SKIP_BUILD=1`）⇒ 四条变异可以快速连跑。
#    理由：变异改的是**数据 / 冻结文件**，与 jar 无关 ⇒ 复用同一份产物完全等价。
# ⚠️ 但**必须先确认 jar 存在**（不存在就退回「带构建」跑，别拿空产物去测）。
C_JAR_REL="service/comment/target/comment-service-1.0-SNAPSHOT.jar"
RAG_V1_ROOT="$(cd "${HERE}/../../.." && pwd)"
SKIP_BUILD=""
[ -f "${RAG_V1_ROOT}/${C_JAR_REL}" ] && SKIP_BUILD="PROBE_SKIP_BUILD=1"
[ -n "${SKIP_BUILD}" ] && say "   · 复用已有 C 侧 jar（${C_JAR_REL}）⇒ 变异轮次不重新构建"

expect_red() {   # $1 = 标签 ⇒ 跑一轮探针 ⇒ 必须 EXIT=1
  local tag="$1" rc
  env ${SKIP_BUILD} bash "${RUN}" >"/tmp/parity-mutate-${tag}.log" 2>&1
  rc=$?
  say "   探针退出码 = ${rc}（期望 1）"
  if [ "${rc}" != "1" ]; then
    say "   ❌ ${tag} 没有转红 ⇒ 探针可能假绿"
    FAILED=1
    return 1
  fi
  say "   ✅ ${tag} 精确转红"
  say "      —— 报差异的用例："
  grep -E '^   ❌ ' "/tmp/parity-mutate-${tag}.log" | sed 's/^/      /' | head -10
  return 0
}

restore_files() {
  python3 - "${ORIG_EXPECTED}" "${EXPECTED}" <<'PY'
import shutil, sys
shutil.copyfile(sys.argv[1], sys.argv[2])
PY
  python3 - "${ORIG_KNOWN}" "${KNOWN_DIFF}" <<'PY'
import shutil, sys
shutil.copyfile(sys.argv[1], sys.argv[2])
PY
  local a b
  a="$(shasum -a 256 "${EXPECTED}" | cut -d' ' -f1)"
  b="$(shasum -a 256 "${KNOWN_DIFF}" | cut -d' ' -f1)"
  if [ "${a}" != "${EXPECTED_SHA_BEFORE}" ]; then
    say "   ❌ expected.txt 还原核验失败（${EXPECTED_SHA_BEFORE} → ${a}）"
    FAILED=1
  else
    say "   ✅ expected.txt 已还原（sha256 ${a}）"
  fi
  if [ "${b}" != "${KNOWN_SHA_BEFORE}" ]; then
    say "   ❌ known-diff.txt 还原核验失败（${KNOWN_SHA_BEFORE} → ${b}）"
    FAILED=1
  else
    say "   ✅ known-diff.txt 已还原（sha256 ${b}）"
  fi
}

# ══════════════════════════ M1 · 改 C 侧数据 ⇒ 必须报差异 ═════════════════════
say "== M1：改 **C 侧**数据库的评论正文 ⇒ 期望探针 EXIT=1 =="
PROBE_MUTATE_CMD='docker exec "${PROBE_C_PG_CONTAINER}" psql -U admin -d first_db -c "UPDATE comment_content SET content='"'"'变异正文-M1'"'"' WHERE comment_id='"'"'"${ROOT_CID}"'"'"'"' \
  expect_red M1

# ══════════════════════════ M2 · 改 B 侧数据 ⇒ 必须报差异 ═════════════════════
say "== M2：改 **B 侧**数据库的评论正文 ⇒ 期望探针 EXIT=1 =="
PROBE_MUTATE_CMD='docker exec "${PROBE_B_PG_CONTAINER}" psql -U admin -d first_db -c "UPDATE comment_content SET content='"'"'变异正文-M2'"'"' WHERE comment_id='"'"'"${ROOT_CID}"'"'"'"' \
  expect_red M2

# ══════════════════════════ M3 · 改冻结基线 ⇒ 必须报差异 ═════════════════════
say "== M3：改冻结基线 expected.txt 里一枚 hex ⇒ 期望探针 EXIT=1 =="
# ⚠️ **必须挑「body 非空」的 CASE 行**：401 空 body 那条的 hex 是**空串**
#    ⇒ 对它做 `h[0]` 会 IndexError ⇒ 变异脚本自己崩掉 ⇒ **文件没被改** ⇒
#    ⇒ 探针照常绿 ⇒ **看起来像「探针假绿」，其实是变异脚本的 bug**（本轮真踩过一轮）。
# ⇒ 故：跳过空 hex 的行，**并且断言文件真的变了**（否则直接退出码 2，不算「变异没转红」）。
python3 - "${EXPECTED}" <<'PY'
import sys
p = sys.argv[1]
orig = open(p, encoding="utf-8").read()
lines = orig.split("\n")
changed = False
for i, ln in enumerate(lines):
    if ln.startswith("CASE|"):
        parts = ln.split("|")
        h = parts[4]
        if not h:
            continue                      # 空 body（401）⇒ 跳过
        parts[4] = ("1" if h[0] != "1" else "2") + h[1:]
        lines[i] = "|".join(parts)
        changed = True
        break
if not changed:
    raise SystemExit("!! expected.txt 里没有可变的 CASE 行（非空 hex）⇒ 变异脚本自身有问题")
open(p, "w", encoding="utf-8").write("\n".join(lines))
if open(p, encoding="utf-8").read() == orig:
    raise SystemExit("!! 变异没有生效（文件内容未变）⇒ 变异脚本自身有问题")
print("   · M3 变异已施加（expected.txt 的第一条非空 hex 行）")
PY
[ $? -eq 0 ] || { say "!! M3 变异脚本自身失败 ⇒ 退出码 2（不是「探针假绿」）"; restore_files; exit 2; }
expect_red M3

# ══════════════════════════ M4 · 改已知差异的 C 侧字节 ⇒ 必须报差异 ═════════════
say "== M4：改 known-diff.txt 里某条 DIFF 的 **C 侧 hex** ⇒ 期望探针 EXIT=1 =="
python3 - "${KNOWN_DIFF}" <<'PY'
import sys
p = sys.argv[1]
orig = open(p, encoding="utf-8").read()
lines = orig.split("\n")
changed = False
for i, ln in enumerate(lines):
    if ln.startswith("DIFF|"):
        parts = ln.split("|")
        h = parts[7]                     # C 侧 hex
        if not h:
            continue
        parts[7] = ("1" if h[0] != "1" else "2") + h[1:]
        lines[i] = "|".join(parts)
        changed = True
        break
if not changed:
    raise SystemExit("!! known-diff.txt 里没有可变的 DIFF 行 ⇒ 变异脚本自身有问题")
open(p, "w", encoding="utf-8").write("\n".join(lines))
if open(p, encoding="utf-8").read() == orig:
    raise SystemExit("!! 变异没有生效（文件内容未变）⇒ 变异脚本自身有问题")
print("   · M4 变异已施加（known-diff.txt 的第一条 DIFF 的 C 侧 hex）")
PY
[ $? -eq 0 ] || { say "!! M4 变异脚本自身失败 ⇒ 退出码 2（不是「探针假绿」）"; restore_files; exit 2; }
expect_red M4

# ══════════════════════════ 还原两个冻结文件 ══════════════════════════════════
say "== 还原两个冻结文件 ＋ sha256 核验 =="
restore_files

echo
if [ "${FAILED}" = "0" ]; then
  say "✅ 四条变异全部精确转红 ＋ 冻结文件已还原 ⇒ 探针**不是恒绿**"
  exit 0
fi
say "❌ 变异验证失败（探针存在假绿嫌疑）"
exit 1
