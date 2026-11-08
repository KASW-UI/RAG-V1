#!/usr/bin/env bash
# =============================================================================
# P-27 根治脚本 —— 把 **IDE（jdtls）的输出目录与 Maven 的 `target/` 隔开**
#
#   bash tools/ide/isolate-ide-output-dir.sh            # 施加（幂等）
#   bash tools/ide/isolate-ide-output-dir.sh --verify   # 只体检，不改
#   bash tools/ide/isolate-ide-output-dir.sh --revert   # 还原回 target/classes
#
# ── 为什么需要它 ───────────────────────────────────────────────────────────
# 本机跑着 **Eclipse JDT 语言服务器**（Neovim 的 `jdtls` 客户端）。
# 它的 `.classpath` 把输出目录写成 `target/classes` ⇒ ⇒ **IDE 与 Maven 抢同一个目录**。
# ⇒ Eclipse 增量构建器对 `target/classes` 做的是「**先整体擦除、再整体重写**」
# ⇒ ⇒ 三张脸（详见 `docs/Pitfalls/_cross/P-27-…md`）：
#     ① P-26：IDE 编出的 class **不跑 Lombok** ⇒ 覆盖掉 Maven 编好的 class
#     ② jar 被**静默打残**（打包发生在「擦除」与「重写」之间 ⇒ 零报错）
#     ③ `protobuf-maven-plugin` 报「Unable to clean up temporary proto file directory」
# ⇒ ⇒ 三张脸**都指向无辜的地方**（共享层 / 下游域 / proto 契约模块）。
#
# ── 本脚本做什么 ───────────────────────────────────────────────────────────
# 把 13 份 `.classpath` 里的输出目录从 `target/…` 改到 **`.jdtls-build/…`**
# ⇒ ⇒ 两套编译器**各写各的目录** ⇒ ⇒ 互不覆盖、互不擦除。
#
# ── ⚠️ 三条纪律 ────────────────────────────────────────────────────────────
# ① `.classpath` / `.project` / `.settings/` **不进版本控制**（见 `.gitignore`）
#    ⇒ ⇒ 这是**本机环境动作**，不是仓库动作 ⇒ **本仓不为它提交任何文件**
#    ⇒ ⇒ 但**必须把它登记下来**（否则下一个人会重复踩）⇒ 见 P-27 台账。
# ② **m2e 可能在「Update Maven Project」后重写 `.classpath`** ⇒ ⇒ 本脚本必须**可重跑**
#    （幂等）⇒ 重跑一次即可恢复。
# ③ **不要**把输出目录改到 `target/` **里面**（如 `target/eclipse-classes`）
#    ⇒ ⇒ `mvn clean` 照样把它擦掉 ⇒ **不满足「与 target/ 隔开」** ⇒ 白改。
#
# ── 残留（已知 · 不修）──────────────────────────────────────────────────────
# `org.eclipse.jdt.apt.core.prefs` 里的 `genSrcDir=target/generated-sources/annotations`
# 与 `.classpath` 里指向 `target/generated-sources/**` 的**源目录条目**仍在 `target/` 内
# ⇒ ⇒ `mvn clean` 会删掉它们 ⇒ jdtls 会重新生成（APT 自己管）⇒
# ⇒ **只影响 IDE 的瞬时状态，不影响 Maven 产物** ⇒ 不在本脚本范围内。
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

MODE="${1:-apply}"

# IDE 输出根（相对各模块目录）
IDE_OUT=".jdtls-build/classes"
IDE_TEST_OUT=".jdtls-build/test-classes"

say() { printf '%s\n' "$*"; }

CLASSPATH_FILES=()
while IFS= read -r f; do
  CLASSPATH_FILES+=("$f")
done < <(find "${REPO_ROOT}" -name ".classpath" -not -path "*/target/*" -not -path "*/.git/*" | sort)

if [ "${#CLASSPATH_FILES[@]}" -eq 0 ]; then
  say "ℹ️ 本机没有 `.classpath`（IDE 未导入过本仓）⇒ 无需处理 ⇒ 退出 0"
  exit 0
fi

patched=0; already=0; failed=0
for f in "${CLASSPATH_FILES[@]}"; do
  rel="${f#"${REPO_ROOT}/"}"
  if [ "${MODE}" = "--revert" ]; then
    before="$(cat "${f}")"
    printf '%s' "${before}" \
      | sed -e "s#${IDE_OUT}#target/classes#g" -e "s#${IDE_TEST_OUT}#target/test-classes#g" \
      > "${f}.tmp" && mv "${f}.tmp" "${f}"
    say "  ↩︎ 还原 ${rel}"
    continue
  fi
  if [ "${MODE}" = "--verify" ]; then
    if grep -q 'path="target/classes"\|output="target/classes"\|output="target/test-classes"' "${f}"; then
      say "  ❌ ${rel} ⇒ 输出目录**仍在 target/ 内**（P-27 未隔离）"
      failed=$((failed + 1))
    else
      say "  ✅ ${rel} ⇒ 输出目录已隔离"
    fi
    continue
  fi
  # apply（幂等）
  if grep -q 'path="target/classes"\|output="target/classes"\|output="target/test-classes"' "${f}"; then
    # ⚠️ **不用 `sed -i`** —— BSD sed（macOS）把 `-e` 当**备份文件后缀**
    #    ⇒ `sed -i -e ...` 会报「No such file or directory」而**文件根本没被改**
    #    ⇒ ⇒ **脚本却会说「已处理」** ⇒ 假绿。⇒ 一律走「临时文件 + mv」。
    tmp="${f}.tmp"
    sed \
      -e "s#kind=\"output\" path=\"target/classes\"#kind=\"output\" path=\"${IDE_OUT}\"#g" \
      -e "s#kind=\"output\" path=\"target/test-classes\"#kind=\"output\" path=\"${IDE_TEST_OUT}\"#g" \
      -e "s#output=\"target/classes\"#output=\"${IDE_OUT}\"#g" \
      -e "s#output=\"target/test-classes\"#output=\"${IDE_TEST_OUT}\"#g" \
      "${f}" > "${tmp}" && mv "${tmp}" "${f}"
    # ⚠️ **事后自检**：确认真的改了（否则「报成功但没改」= 假绿）
    if grep -q 'path="target/classes"\|output="target/classes"\|output="target/test-classes"' "${f}"; then
      say "  ❌ ${rel} ⇒ 改后**仍**指向 target/ ⇒ 施加失败"
      failed=$((failed + 1))
    else
      say "  ✏️  ${rel} ⇒ target/ → ${IDE_OUT}"
      patched=$((patched + 1))
    fi
  else
    say "  ➖ ${rel} ⇒ 已是隔离态，跳过"
    already=$((already + 1))
  fi
done

case "${MODE}" in
  --verify)
    say ""
    if [ "${failed}" -gt 0 ]; then
      say "❌ ${failed}/${#CLASSPATH_FILES[@]} 份 .classpath 仍与 target/ 共用输出目录 ⇒ P-27 未根治"
      exit 1
    fi
    say "✅ ${#CLASSPATH_FILES[@]}/${#CLASSPATH_FILES[@]} 份 .classpath 的输出目录均已与 target/ 隔开"
    exit 0
    ;;
  --revert)
    say ""
    say "↩︎ 已还原 ${#CLASSPATH_FILES[@]} 份 .classpath（回到 target/classes）"
    exit 0
    ;;
  *)
    say ""
    say "✅ 处理 ${#CLASSPATH_FILES[@]} 份 .classpath：新改 ${patched} · 已隔离 ${already}"
    say "   ⇒ 下一步：让 jdtls 重新导入（它会监听 .classpath 变化）⇒ 之后 IDE 产物落在 ${IDE_OUT}"
    exit 0
    ;;
esac
