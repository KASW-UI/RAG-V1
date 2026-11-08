#!/usr/bin/env bash
# =============================================================================
# JSON 键序运行时探针 —— 「全用 jackson2」决策的**字节级**证据
#
#   bash tools/difftest/jackson-key-order/run.sh
#
# 它做什么：
#   1. 收集全仓各模块的 target/classes（= 编译产物）
#   2. 用**根 pom 里的 <jackson2.version>** 定位 Jackson 2 的三个 jar（不猜版本）
#   3. 编译并运行 JacksonKeyOrderProbe ⇒ 逐类实测：
#        - 「@JsonPropertyOrder 声明序」 vs 「Jackson 2 自然序（压制注解后）」
#        - 同一实例在两个 mapper 下的**序列化字节**是否完全相同
#
# 退出码：0 = 全部一致 · 1 = 有类型不一致 · 2 = 环境未就绪（未编译 / 找不到 jar）
#
# ⚠️ **为什么必须是运行时而不是静态**：Jackson 对 record 的默认序是**框架行为**
#    （`MapperFeature.SORT_CREATOR_PROPERTIES_FIRST` + 构造参数序）⇒ 源码上看不出来
#    ⇒ ⇒ 只有跑真 Jackson 才算证据（AGENTS.md §3.3）。
# ⚠️ **前置**：需先 `mvn -o clean test-compile`（本脚本**不**自动构建，避免误伤正在跑的 IDE）。
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
cd "${REPO_ROOT}"

say() { printf '%s\n' "$*"; }
die() { say "❌ $*"; exit 2; }

# ── 1. 收集编译产物 ────────────────────────────────────────────────────────
# ⚠️ **先校验「该在的模块都在」** —— 否则会踩一个**假绿**陷阱：
#    某次 `mvn compile` 失败（Maven 会先清空该模块的 target/classes 再报错）
#    ⇒ 那个模块的 class 全部消失 ⇒ 探针**照跑不误**，只是类型数变少
#    ⇒ ⇒ 「探针说全部一致」其实只覆盖了一部分类型 ⇒ **结论被悄悄缩小**。
#    ⇒ ⇒ 这里改成：**任何含 src/main/java 的模块缺 target/classes ⇒ 直接退出码 2**。
MISSING=()
while IFS= read -r mod; do
  cls="${mod}/target/classes"
  if [ ! -d "${cls}" ] || [ -z "$(find "${cls}" -name '*.class' -print -quit 2>/dev/null)" ]; then
    MISSING+=("${mod#"${REPO_ROOT}/"}")
  fi
done < <(find "${REPO_ROOT}" -type d -path "*/src/main/java" -not -path "*/target/*" -not -path "*/.git/*" \
           | sed 's:/src/main/java$::' | sort -u)

if [ "${#MISSING[@]}" -gt 0 ]; then
  say "❌ 环境未就绪：以下模块没有可用的 target/classes ⇒ 探针会**静默缩小覆盖面** ⇒ 拒绝运行："
  for m in "${MISSING[@]}"; do say "     - ${m}"; done
  say "   ⇒ 请先跑 \`mvn -o clean test-compile\`（若已跑过仍缺 ⇒ 看构建是否失败）"
  exit 2
fi

CLASSES_DIRS=()
while IFS= read -r d; do
  [ -n "$d" ] && CLASSES_DIRS+=("$d")
done < <(find "${REPO_ROOT}" -type d -name classes -path "*/target/*" 2>/dev/null | sort)

if [ "${#CLASSES_DIRS[@]}" -eq 0 ]; then
  die "环境未就绪：全仓没有任何 target/classes ⇒ 请先跑 \`mvn -o clean test-compile\`"
fi
say "== 编译产物目录 ${#CLASSES_DIRS[@]} 个（已校验：所有含 src/main/java 的模块都有 class）=="

# ── 2. 按根 pom 的 <jackson2.version> 定位 Jackson 2 的 jar ────────────────
JACKSON_VERSION="$(sed -n 's:.*<jackson2\.version>\([^<]*\)</jackson2\.version>.*:\1:p' \
  "${REPO_ROOT}/pom.xml" | head -1)"
[ -n "${JACKSON_VERSION}" ] || die "根 pom 里找不到 <jackson2.version> ⇒ 无法确定 Jackson 版本"

M2="${HOME}/.m2/repository/com/fasterxml/jackson/core"
CP=""
for artifact in jackson-databind jackson-core jackson-annotations; do
  jar="${M2}/${artifact}/${JACKSON_VERSION}/${artifact}-${JACKSON_VERSION}.jar"
  [ -f "${jar}" ] || die "找不到 jar：${jar} ⇒ 先跑 \`mvn -o clean test-compile\` 把依赖拉全"
  CP="${CP}${jar}:"
done
say "== Jackson 版本 ${JACKSON_VERSION}（取自根 pom 的 <jackson2.version>）=="

# ── 3. 编译探针 ───────────────────────────────────────────────────────────
OUT="$(mktemp -d)"
trap 'rm -rf "${OUT}"' EXIT

CP="${CP}$(IFS=:; echo "${CLASSES_DIRS[*]}")"
javac -nowarn -cp "${CP}" -d "${OUT}" "${SCRIPT_DIR}/JacksonKeyOrderProbe.java" \
  || die "探针编译失败"

# ── 4. 跑 ──────────────────────────────────────────────────────────────────
say ""
java -cp "${OUT}:${CP}" JacksonKeyOrderProbe "${CLASSES_DIRS[@]}"
RC=$?
say ""
if [ "${RC}" -eq 0 ]; then
  say "✅ 探针通过（退出码 0）"
else
  say "❌ 探针判红（退出码 ${RC}）⇒ ⇒ 有类型的注解序与 Jackson 2 自然序不一致"
fi
exit "${RC}"
