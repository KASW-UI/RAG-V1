#!/usr/bin/env bash
#
# 变异验证：证明 `LikeKafkaThreeFaceRealTest` **不是假绿**（AGENTS.md §3.3.3）。
#
# 这条链有四段，每段都必须能被变异打红，否则测试就只是「自己跟自己比」：
#   ① **真 Kafka broker**（`127.0.0.1:49992`）—— 不连 broker 时消息根本发不出去
#   ② **真 `@KafkaListener`**（`LikeUpdateKafkaListener`）—— 消费面落库全靠它
#   ③ **真定时中继**（`LikeHotEventOutboxRelay`）—— 推送面推 `article-hot-events` 全靠它
#   ④ **真 PG**（Testcontainers）—— inbox / record / fact / outbox 四处齐全
#
# ⚠️ **判据组合必须互斥**：每条变异打红的用例集合**互不相同**
#    ⇒ ⇒ 三条变异合起来能区分「哪个机制在支撑哪条断言」
#    ⇒ ⇒ 否则「全部一起红」也分不清是谁的锅。
#
#   变异        │ 生产面 │ 消费面 │ 推送面 │ 幂等面
#   ────────────┼────────┼────────┼────────┼────────
#   M1 listener 停 │ 绿   │  红    │  红    │  红
#   M2 relay 停     │ 绿   │  绿    │  红    │  绿
#   M3 断真 broker  │ 红   │  红    │  红    │  红
#
# ⚠️ **还原一律用「文件备份 + 复制回来」** —— 绝不用 `git checkout --`：
#    本 lane 的改动可能还没提交 ⇒ `git checkout` 会把它们一起回滚掉。
# ⚠️ **每个 trap 都还原**：脚本被杀掉（超时 / 手动 / 机器重启）时，
#    变异会留在文件里 ⇒ 下一轮的读数**没有意义** ⇒ 退出前必须全部还原。
#
# 退出码：0 = 全部变异按预期表现 + 还原后转绿；1 = 有变异没按预期表现。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"

# ⚠️ M1 的靶子：消费 listener（`@KafkaListener`）
LISTENER="${C_ROOT}/service/like/src/main/java/com/rag/like/messaging/LikeUpdateKafkaListener.java"
# ⚠️ M2 的靶子：热点事件中继（`@Scheduled`）
RELAY="${C_ROOT}/service/like/src/main/java/com/rag/like/messaging/LikeHotEventOutboxRelay.java"
# ⚠️ M3 的靶子：**测试自身**的 broker 覆盖行（证明测试真的依赖真 broker）
TEST_SRC="${C_ROOT}/service/like/src/test/java/com/rag/like/messaging/LikeKafkaThreeFaceRealTest.java"

REPORT="${C_ROOT}/service/like/target/surefire-reports/TEST-com.rag.like.messaging.LikeKafkaThreeFaceRealTest.xml"

METHODS="producerSendsKafkaLikeMsgToTopic consumerWritesAllFourPlaces relayPushesOutboxEventToHotTopic redeliveryIsIdempotent"

cd "${C_ROOT}"

TMP_OUT="$(mktemp)"
BAK_DIR="$(mktemp -d)"
# ⚠️ **不删临时目录**：`rm -rf` 在本机会被回收站守卫拦下 ⇒ 留着由系统清理。
say() { printf '%s\n' "$*"; }
FAILED=0

restore_all() {
  [ -f "${BAK_DIR}/listener.java" ] && cp "${BAK_DIR}/listener.java" "${LISTENER}"
  [ -f "${BAK_DIR}/relay.java" ] && cp "${BAK_DIR}/relay.java" "${RELAY}"
  [ -f "${BAK_DIR}/test.java" ] && cp "${BAK_DIR}/test.java" "${TEST_SRC}"
  return 0
}
trap 'restore_all; rm -f "${TMP_OUT}" >/dev/null 2>&1' EXIT

backup()  { cp "$1" "${BAK_DIR}/$2"; }
restore() { cp "${BAK_DIR}/$2" "$1"; }

# ⚠️ **唯一性前置断言**：变异片段在目标文件里必须**恰好命中 1 次**
#    ⇒ 否则「改了 N 处 ⇒ 读数不可归因」（红在哪个用例都不知道是哪处改动造成的）。
mutate() {   # mutate <文件> <旧串> <新串>
  python3 - "$1" "$2" "$3" <<'PY'
import sys, io
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(p, encoding="utf-8").read()
n = s.count(old)
if n != 1:
    print(f"唯一性断言失败：片段命中 {n} 次（要求恰好 1 次）: {old[:70]}")
    sys.exit(3)
io.open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
}

run_tests() {
  MAVEN_OPTS=-Xmx768m mvn -o -pl service/like -am test \
    -Dtest='LikeKafkaThreeFaceRealTest' \
    -Dsurefire.failIfNoSpecifiedTests=false >"${TMP_OUT}" 2>&1
  echo $?
}

# ⚠️ **判红/判绿从 surefire 的 XML 报告读**（不用 grep 日志）：
#    日志里的 `FAILURE!` 行会被别的框架噪声淹没；XML 里每个 `<testcase>` 自带
#    `<failure>` / `<error>` 子元素 ⇒ 判据与「哪个用例红了」一一对应。
status_of() {   # $1 = 方法名 ⇒ 打印 red / green / missing-case / missing-report
  python3 - "${REPORT}" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
rep, name = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(rep).getroot()
except Exception:
    print("missing-report"); sys.exit(0)
for tc in root.iter("testcase"):
    if tc.get("name") == name:
        bad = tc.find("failure") is not None or tc.find("error") is not None
        print("red" if bad else "green")
        sys.exit(0)
print("missing-case")
PY
}

expect() {   # expect <方法> <red|green> <说明>
  local got; got="$(status_of "$1")"
  if [ "${got}" = "$2" ]; then
    say "   ✅ $1 ⇒ $2（$3）"
    return 0
  fi
  say "   ❌ $1 ⇒ 实得 **${got}**、期望 **$2**（$3）"
  FAILED=1
  return 1
}

backup "${LISTENER}"  listener.java
backup "${RELAY}"      relay.java
backup "${TEST_SRC}"   test.java

# ══════════════════════════════════════════════════════════════════════
# 🔴 启动前「变异残留自检」
#   ⚠️ 变异脚本被杀掉时，变异会留在文件里 ⇒ 残留会污染下一轮的读数
#   ⇒ ⇒ 每次启动前先自检：**发现残留 ⇒ 立刻拒绝启动**。
# ══════════════════════════════════════════════════════════════════════
residue_check() {
  local bad=0
  if grep -q 'autoStartup = "false"' "${LISTENER}"; then
    say "❌ **残留**：listener 里还留着 M1 的 autoStartup=false ⇒ 先还原再跑"; bad=1
  fi
  if grep -q 'fixedRateString = "3600000"' "${RELAY}"; then
    say "❌ **残留**：relay 里还留着 M2 的 3600000 ⇒ 先还原再跑"; bad=1
  fi
  if ! grep -q 'spring.kafka.bootstrap-servers=127.0.0.1:49992' "${TEST_SRC}"; then
    say "❌ **残留**：测试里 M3 删掉的 broker 覆盖还没加回来 ⇒ 先还原再跑"; bad=1
  fi
  if [ "${bad}" = "1" ]; then
    say "❌ **拒绝启动**：上一轮的变异没有还原干净 ⇒ 跑出来的读数**没有意义**"
    exit 2
  fi
  say "   ✓ 启动前自检：三个变异靶点均为**原始形态**（无残留）"
}
residue_check

say "== 基线（未变异）必须四个全绿 =="
RC="$(run_tests)"
if [ "${RC}" = "0" ]; then
  say "   ✅ 基线绿（exit 0 · Tests run: 4, Failures: 0）"
else
  say "❌ 基线就不绿（exit ${RC}）⇒ 后续变异判定无意义"
  tail -40 "${TMP_OUT}" | sed 's/^/   | /'
  exit 1
fi
for m in ${METHODS}; do expect "${m}" green "基线全绿"; done

# ─────────────────────────────────────────────────────────────
# M1：让 `@KafkaListener` **停掉** ⇒ 消费面 / 推送面 / 幂等面 必须转红
#      ⇒ 生产面必须**仍绿**（它只验证「发得出去」，不依赖 listener）
#      ⇒ ⇒ 证明「消费面那几处落库」真的由 listener 造成，不是别的路径写的。
# ─────────────────────────────────────────────────────────────
say ""
say "== M1 listener 停（autoStartup=false）⇒ 消费面/推送面/幂等面转红，生产面仍绿 =="
mutate "${LISTENER}" '@KafkaListener(id = "like-update-consumer",' \
                '@KafkaListener(id = "like-update-consumer", autoStartup = "false",'
RC="$(run_tests)"
if [ "${RC}" != "0" ]; then
  say "   ✅ 整体转红（exit ${RC}）"
  expect "consumerWritesAllFourPlaces"      red   "listener 停 ⇒ inbox 无行"
  expect "relayPushesOutboxEventToHotTopic"  red   "listener 停 ⇒ outbox 无行 ⇒ 中继无从推"
  expect "redeliveryIsIdempotent"           red   "listener 停 ⇒ inbox 无行"
  expect "producerSendsKafkaLikeMsgToTopic"  green "生产面不依赖 listener ⇒ 仍绿（证明变异精确）"
else
  say "   ❌ 没按预期转红（exit 0）⇒ 消费面**没有**真的靠 listener 写库（假绿！）"
  FAILED=1
fi
restore "${LISTENER}" listener.java

# ─────────────────────────────────────────────────────────────
# M2：让中继**停掉**（间隔改 1 小时）⇒ 只有推送面 必须转红
#      ⇒ 消费面 / 幂等面 / 生产面 必须**仍绿**
#      ⇒ ⇒ 证明「推送面」与「消费面」是**两个独立机制**，且各自的断言都咬在对应机制上。
# ─────────────────────────────────────────────────────────────
say ""
say "== M2 中继停（relay 间隔改 1 小时）⇒ 只有推送面转红，其余仍绿 =="
mutate "${RELAY}" '@Scheduled(fixedRateString = "${like.outbox-relay-interval:1000}")' \
                '@Scheduled(fixedRateString = "3600000")'
RC="$(run_tests)"
if [ "${RC}" != "0" ]; then
  say "   ✅ 整体转红（exit ${RC}）"
  expect "relayPushesOutboxEventToHotTopic"  red   "中继停 ⇒ article-hot-events 无新消息"
  expect "consumerWritesAllFourPlaces"      green "消费面不依赖中继 ⇒ 仍绿（证明变异精确）"
  expect "redeliveryIsIdempotent"           green "幂等面不依赖中继 ⇒ 仍绿"
  expect "producerSendsKafkaLikeMsgToTopic"  green "生产面不依赖中继 ⇒ 仍绿"
else
  say "   ❌ 没按预期转红（exit 0）⇒ 推送面**没有**真的靠中继推（假绿！）"
  FAILED=1
fi
restore "${RELAY}" relay.java

# ─────────────────────────────────────────────────────────────
# M3：断掉**真 broker**（撤掉测试里的 bootstrap-servers 覆盖 ⇒ 回落 127.0.0.1:1）
#      ⇒ 四个用例必须**全部**转红
#      ⇒ ⇒ 证明这四个面真的在连**真 Kafka**，不是跑在「无 broker 的降级路径」上。
# ─────────────────────────────────────────────────────────────
say ""
say "== M3 断掉真 broker（撤掉 bootstrap 覆盖）⇒ 四个用例全部转红 =="
mutate "${TEST_SRC}" '                "spring.kafka.bootstrap-servers=127.0.0.1:49992",
' ''
RC="$(run_tests)"
if [ "${RC}" != "0" ]; then
  say "   ✅ 整体转红（exit ${RC}）"
  expect "producerSendsKafkaLikeMsgToTopic"  red   "无 broker ⇒ 发不出去"
  expect "consumerWritesAllFourPlaces"      red   "无 broker ⇒ 消费侧无消息"
  expect "relayPushesOutboxEventToHotTopic"  red   "无 broker ⇒ 推不出去"
  expect "redeliveryIsIdempotent"           red   "无 broker ⇒ 重投也到不了"
else
  say "   ❌ 没按预期转红（exit 0）⇒ 这四个面**没有**真的依赖真 broker（假绿！）"
  FAILED=1
fi
restore "${TEST_SRC}" test.java

# ─────────────────────────────────────────────────────────────
# 还原后必须复跑为绿（只红不够 ⇒ 要证明「红是这次变异造成的」）
# ─────────────────────────────────────────────────────────────
say ""
say "== 还原后复跑必须转绿 =="
RC="$(run_tests)"
if [ "${RC}" = "0" ]; then
  say "   ✅ 还原后绿（exit 0）⇒ 之前的红确实是**变异**造成的"
else
  say "   ❌ 还原后仍红（exit ${RC}）⇒ 有变异没还原干净，或基线本来就不绿"
  tail -40 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi

say ""
if [ "${FAILED}" = "0" ]; then
  say "—— 变异验证通过：三条变异的判据组合互斥 ⇒ 四个面各自真的咬在对应机制上 ——"
  exit 0
fi
say "—— 变异验证**未通过**：有变异没按预期表现（或还原不干净）——"
exit 1
