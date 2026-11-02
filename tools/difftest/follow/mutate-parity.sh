#!/usr/bin/env bash
#
# 变异验证：证明 `tools/difftest/follow/run.sh` **不是假绿**（AGENTS.md §3.3.3）。
#
# 这条链有四段，每段都必须能被变异打红，否则探针就只是「自己跟自己比」：
#   ① **C 侧的真实字节**（Jackson 的字段序）
#   ② **冻结的 expected.txt**（= Go 真源字节）
#   ③ **两侧各自的 DB 数据**（否则「两侧都空」分不出「相同」与「都空」）
#   ④ **失败路径**：一侧挂掉时探针必须转红（= FAILING-010 的守卫）
#
# 手法沿用 B 侧 `tools/api-full-parity-probe/follow/mutate-parity.sh`，
# 但**被打的靶子从 B 侧换成 C 侧**（本探针的被测对象是 C 侧 ⇒ 变异必须打在 C 侧，
# 否则证明的是「B 侧有牙齿」，不是「C 侧这条链有牙齿」）。
#
# ⚠️ **还原一律用「文件备份 + 复制回来」** —— 绝不用 `git checkout --`：
#    本 lane 的改动可能还没提交 ⇒ `git checkout` 会把它们一起回滚掉。
#
# 退出码：0 = 全部变异按预期表现 + 还原后转绿；1 = 有变异没按预期表现。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"
PROBE="${HERE}/run.sh"
EXPECTED="${HERE}/expected.txt"
DTO="${C_ROOT}/service/follow/src/main/java/com/rag/follow/dto/recommend/RecommendUser.java"
# ⚠️ M6 的靶子：领域层的「下游超时」分支（= 漂移 I 的反向变异）
EXISTENCE="${C_ROOT}/service/follow/src/main/java/com/rag/follow/service/FollowUserExistenceService.java"

cd "${C_ROOT}"

TMP_OUT="$(mktemp)"
BAK_DIR="$(mktemp -d)"
# ⚠️ **不删临时目录**：`rm -rf` 在本机会被回收站守卫拦下 ⇒ 留着由系统清理。
trap 'rm -f "${TMP_OUT}" >/dev/null 2>&1' EXIT

say() { printf '%s\n' "$*"; }
FAILED=0

run_probe() {   # $1.. = 传给 run.sh 的额外 env
  env "$@" bash "${PROBE}" >"${TMP_OUT}" 2>&1
  echo $?
}

backup()  { cp "$1" "${BAK_DIR}/$2"; }
restore() { cp "${BAK_DIR}/$2" "$1"; }

mutate() {   # mutate <文件> <旧串> <新串>
  python3 - "$1" "$2" "$3" <<'PY'
import sys, io
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(p, encoding="utf-8").read()
assert old in s, f"未命中: {old[:60]}"
io.open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
}

case_is_red() {   # $1 = 用例名 ⇒ 该用例在输出里被判为 ❌
  grep -E "❌ ${1}" "${TMP_OUT}" >/dev/null 2>&1
}

# ⚠️ **探针 `die` 的原因行是「!! …」**（stderr）⇒ 它出现在 dump_logs 之前
#    ⇒ 只看 `tail -25` 会被日志尾巴挤掉 ⇒ ⇒ 失败时**先把它单独捞出来**。
#    （本轮实测：基线轮 exit 2，原因行被挤掉 ⇒ 只能靠重跑探针定位。）
die_reason() {
  grep -E "^!! " "${TMP_OUT}" | head -5 | sed 's/^/   | /'
  grep -E "^== |^   · |^   ✅|^   ❌" "${TMP_OUT}" | tail -12 | sed 's/^/   | /'
}

backup "${DTO}"       recommend-user.java
backup "${EXPECTED}"  expected.txt
backup "${PROBE}"     run.sh
backup "${EXISTENCE}" follow-user-existence-service.java

# ══════════════════════════════════════════════════════════════════════
# 🔴 启动前「变异残留自检」
#   ⚠️ **为什么必须有这一步**：变异脚本**被杀掉**（超时 / 手动 / 机器重启）时，
#      那一轮的变异**会留在文件里**（备份/还原只在轮次**结束时**跑）。
#      ⇒ 探针目录**未入 git** ⇒ **没有 git 基线可还原** ⇒ 残留会直接污染下一轮。
#      ⇒ ⇒ 实测代价（本轮）：上一轮被杀 ⇒ `run.sh` 里留着 M3 的「C 侧多灌一行」
#         ⇒ 下一轮的基线轮**矩阵红 + 阶段6 主键冲突 die(exit 2)**
#         ⇒ **看起来像「环境问题」，实则是「上一轮的残留」**。
#   ⇒ ⇒ 每次启动前先自检：**发现残留 ⇒ 立刻拒绝启动**（并指出是哪个变异）。
# ══════════════════════════════════════════════════════════════════════
residue_check() {
  local bad=0
  if grep -q "1001,1009,now(),now()" "${PROBE}"; then
    say "❌ **残留**：探针里还留着 M3 的「C 侧多灌一行」⇒ 先还原 run.sh 再跑"
    bad=1
  fi
  if grep -q 'body.FIXED' "${PROBE}" || grep -q 'if false; then' "${PROBE}"; then
    say "❌ **残留**：探针里还留着 M5 的「旧写法」（固定 body 文件 / 去掉 000 守卫）⇒ 先还原 run.sh 再跑"
    bad=1
  fi
  if grep -q 'SELECT 1;' "${PROBE}"; then
    say "❌ **残留**：探针里还留着 M7 的「去掉 CHECK 约束注入」⇒ 先还原 run.sh 再跑"
    bad=1
  fi
  if grep -q '"mutual_score", "target_id"' "${DTO}"; then
    say "❌ **残留**：DTO 的 @JsonPropertyOrder 还是反序（M1 没还原）⇒ 先还原再跑"
    bad=1
  fi
  if grep -q "CASE|follow_list_seeded|200|7b23" "${EXPECTED}"; then
    say "❌ **残留**：expected.txt 还留着 M2 改过的 hex ⇒ 先还原再跑"
    bad=1
  fi
  if grep -q "throw FollowRpcSupport.rpcError(Status.Code.INTERNAL, ErrCodes.ERROR_SERVER_COMMON);" \
       <<<"$(sed -n '/isDeadlineExceeded(sre)/,/^            }/p' "${EXISTENCE}")"; then
    say "❌ **残留**：领域层还留着 M6 的「超时重新折叠」⇒ 先还原再跑"
    bad=1
  fi
  if [ "${bad}" = "1" ]; then
    say "❌ **拒绝启动**：上一轮的变异没有还原干净 ⇒ 跑出来的读数**没有意义**"
    exit 2
  fi
  say "   ✓ 启动前自检：五个变异靶点均为**原始形态**（无残留）"
}
residue_check

say "== 基线（未变异）必须转绿 =="
BASE_RC="$(run_probe)"
if [ "${BASE_RC}" = "0" ]; then
  say "   ✅ 基线绿（exit 0）"
else
  say "❌ 基线就不绿（exit ${BASE_RC}）⇒ 后续变异判定无意义"
  die_reason
  tail -40 "${TMP_OUT}" | sed 's/^/   | /'
  exit 1
fi

# ─────────────────────────────────────────────────────────────
# M1：把 C 侧 RecommendUser 的 @JsonPropertyOrder **改成反序** ⇒ 键序反转 ⇒ 必须转红
#
# ⚠️ **为什么不能照抄 B 侧的 M1（这是本轮实测出来的一条方法学）**：
#   B 侧的 M1 = 「**删掉** @JsonPropertyOrder」⇒ 能转红，是因为
#   B 侧的 `RecommendUser` 是**可变 class** ⇒ Jackson 在「属性全部显式命名」时
#   **默认走字母序** ⇒ 删掉注解就变成 `{"mutual_score":20,"target_id":"4"}` ⇒ 字节变。
#   而 **C 侧是 immutable record** ⇒ Jackson 对 record 默认按**构造参数（声明）序**输出
#   ⇒ **删掉注解字节不变** ⇒ 同样的变异在 C 侧**不可观测**（实测 exit 0，探针看不见）。
#   ⇒ 变异必须**针对被测形态**设计 ⇒ 这里改成「把注解改成反序」⇒ 字节必变。
#   （实测依据：Jackson 2.19.4 · jshell 三组对照 ——
#     注解=声明序 ⇒ {"target_id":"1004","mutual_score":20}
#     注解=反序   ⇒ {"mutual_score":20,"target_id":"1004"}
#     无注解      ⇒ {"target_id":"1004","mutual_score":20}  ← 与注解=声明序同 ⇒ 删注解无效果）
# ─────────────────────────────────────────────────────────────
say ""
say "== M1 把 C 侧 RecommendUser 的 @JsonPropertyOrder 改成反序 ⇒ 必须转红 =="
mutate "${DTO}" '@JsonPropertyOrder({"target_id", "mutual_score"})' \
                '@JsonPropertyOrder({"mutual_score", "target_id"})'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && case_is_red "recommendations_seeded"; then
  say "   ✅ 精确转红（exit 1 · recommendations_seeded 那条被判不等）"
  grep -E "❌ recommendations_seeded|C != B" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**，不是变异没生效）⇒ 探针看不见字段序漂移"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi
restore "${DTO}" recommend-user.java

# ─────────────────────────────────────────────────────────────
# M2：改冻结的 expected.txt 一枚 hex ⇒ C 侧与冻结值不同 ⇒ 必须转红
#      ⇒ 证明 expected.txt 不是「谁都能写出来」的摆设，它**参与判定**
# ─────────────────────────────────────────────────────────────
say ""
say "== M2 冻结的 expected.txt 被改 ⇒ 必须转红 =="
mutate "${EXPECTED}" 'CASE|follow_list_seeded|200|7b22' 'CASE|follow_list_seeded|200|7b23'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && case_is_red "follow_list_seeded"; then
  say "   ✅ 精确转红（exit 1 · follow_list_seeded 与冻结值不符）"
  grep -E "❌ follow_list_seeded|C != expected" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**，不是变异没生效）⇒ 探针没在比冻结值"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi
restore "${EXPECTED}" expected.txt

# ─────────────────────────────────────────────────────────────
# M3：让 **C 侧**多灌一行 ⇒ 两侧 DB 内容不同 ⇒ 数据用例必须转红
#      ⇒ 证明探针真的在读**两侧各自的库**，而不是「两边都空」
# ─────────────────────────────────────────────────────────────
say ""
say "== M3 C 侧多灌一行（两侧库不同）⇒ 必须转红 =="
mutate "${PROBE}" 'for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  docker exec "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "${SEED_FOLLOW}" >/dev/null || die "灌 follow_relation 失败：${c}"' \
'for c in "${B_PG_CONTAINER}" "${C_PG_CONTAINER}"; do
  docker exec "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "${SEED_FOLLOW}" >/dev/null || die "灌 follow_relation 失败：${c}"
  if [ "${c}" = "${C_PG_CONTAINER}" ]; then
    docker exec "${c}" psql -U admin -d first_db -v ON_ERROR_STOP=1 -c "INSERT INTO follow_relation (user_id,target_id,create_time,update_time) VALUES (1001,1009,now(),now());" >/dev/null
  fi'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && case_is_red "follow_list_seeded"; then
  say "   ✅ 精确转红（exit 1 · 两侧库不同被抓到）"
  grep -E "❌ follow_list_seeded|C != B" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**，不是变异没生效）⇒ 探针没在读两侧各自的库"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi
restore "${PROBE}" run.sh

# ─────────────────────────────────────────────────────────────
# M4：C 侧**中途挂掉** ⇒ 后续用例必须转红
#      ⇒ 证明「请求失败」不会被当成「两侧一致」（= FAILING-010 的守卫）
# ─────────────────────────────────────────────────────────────
say ""
say "== M4 C 侧在矩阵中途挂掉 ⇒ 必须转红（不能沿用上一次的残留 body） =="
RC="$(run_probe PROBE_STOP_C_AFTER=follow_list_limit_zero)"
if [ "${RC}" = "1" ] && case_is_red "follow_list_limit_max"; then
  say "   ✅ 精确转红（exit 1 · 请求失败被当成失败，而不是读残留 body）"
  grep -E "❌ follow_list_limit_max|请求失败" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**，不是守卫失效）⇒ 探针会把失败请求当成通过"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi

# ─────────────────────────────────────────────────────────────
# M5：**复现 FAILING-010** —— 把 run.sh 改回旧写法
#      （固定 -o 文件 + 去掉 http_code==000 守卫 + 只比 body）
#      ⇒ 同样的挂掉场景下探针会把**下一次**请求读成上一次的残留 body
#      ⇒ `follow_list_limit_max`（与上一条 body 相同）会被**误判成 ✅（假绿）**
#      ⇒ 证明 M4 的红确实是守卫带来的，不是碰巧
# ─────────────────────────────────────────────────────────────
say ""
say "== M5 复现 FAILING-010（固定 -o 文件 + 去掉 000 守卫 + 只比 body）⇒ 必须出现假绿 =="
# ⚠️ **三处一起改，才等于「旧探针」**（否则复现不出来）：
#   ① 固定 `-o` 文件（**FAILING-010 本体**）
#   ② 去掉 `http_code == 000` 守卫（请求失败不再被当成失败）
#   ③ **只比 body**（旧探针就是只比 body）
#      ⚠️ 本探针比旧探针**多比一个 HTTP 状态码** ⇒ 若只改 ①②，
#      `c`("200|hex") 与 `b`("000|hex") 仍不等 ⇒ 假绿复现不出来。
mutate "${PROBE}" '  out="$(mktemp "${WORK}/body.XXXXXX")" || return 1' \
                    '  out="${WORK}/body.FIXED"'
mutate "${PROBE}" '  if [ "${ccode}" = "000" ] || [ "${bcode}" = "000" ]; then
    FAILED=1
    say "   ❌ ${name}：请求失败（C http_code=${ccode} · B http_code=${bcode}）"
    return
  fi' \
'  if false; then
    FAILED=1
    return
  fi'
mutate "${PROBE}" '  if [ "${c}" = "${b}" ] && [ "${c}" = "${eh}" ]; then' \
                  '  if [ "${ch}" = "${bh}" ] && [ "${ch}" = "${eh##*|}" ]; then'
run_probe PROBE_STOP_C_AFTER=follow_list_limit_zero >/dev/null
if grep -qE "✅ follow_list_limit_max" "${TMP_OUT}"; then
  say "   ✅ 假绿被复现（follow_list_limit_max 被误判成 ✅ ⇒ 读到了上一次的残留 body）"
  grep -E "✅ follow_list_limit_max" "${TMP_OUT}" | head -2 | sed 's/^/   | /'
else
  say "   ❌ 没能复现假绿 ⇒ M4 的红可能不是守卫带来的"
  grep -E "follow_list_limit_max" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
  FAILED=1
fi
restore "${PROBE}" run.sh

# ─────────────────────────────────────────────────────────────
# M6：C 侧领域层把 **DEADLINE_EXCEEDED 重新折叠成 INTERNAL**（= 漂移 I 的反向）
#      ⇒ 场景轮的「超时」阶段必须转红
#      ⇒ 证明「超时 ⇒ 两侧都是 1015」这条**新判据有牙齿**（不是恒绿）
# ⚠️ **为什么这条必须新加**：M1~M5 都打在 **HTTP 矩阵**上 ⇒ 矩阵绿 ≠ 场景轮绿。
#      场景轮是本轮**新增**的判据（超时 1015 / 回滚 5008 / 并发 {200}）⇒ 必须各自有变异证据。
# ─────────────────────────────────────────────────────────────
say ""
say "== M6 C 侧把 DEADLINE_EXCEEDED 重新折叠成 INTERNAL ⇒ 场景轮「超时」阶段必须转红 =="
mutate "${EXISTENCE}" '            if (UserRpcClient.isDeadlineExceeded(sre)) {
                throw sre;
            }' \
'            if (UserRpcClient.isDeadlineExceeded(sre)) {
                throw FollowRpcSupport.rpcError(Status.Code.INTERNAL, ErrCodes.ERROR_SERVER_COMMON);
            }'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && grep -qE "❌ 第 [0-9]+ 轮：两侧" "${TMP_OUT}"; then
  say "   ✅ 精确转红（exit 1 · 超时场景两侧不再一致 ⇒ C 侧回到 5001）"
  grep -E "❌ 第 [0-9]+ 轮：两侧|3 轮分布" "${TMP_OUT}" | head -6 | sed 's/^/   | /'
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**）⇒ 「超时 ⇒ 1015」这条判据**没有牙齿**"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi
restore "${EXISTENCE}" follow-user-existence-service.java

# ─────────────────────────────────────────────────────────────
# M7：探针的**事务回滚注入**被去掉（不加 CHECK 约束）
#      ⇒ block 写入会成功 ⇒ 业务码是 200 而不是 5008 ⇒ 阶段6 必须转红
#      ⇒ 证明「回滚判据（5008 + follow 行仍在）」**不是空洞断言**
#      ⇒ ⚠️ 这条防的是「两侧一致 + 行数对」这种**空洞判据**：
#         若只断言「两侧一致」，去掉注入后两侧**依然一致**（都是 200）⇒ 绿灯 ⇒ 假绿。
# ─────────────────────────────────────────────────────────────
say ""
say "== M7 探针去掉 CHECK 约束注入 ⇒ 事务回滚判据必须转红（证明它不是空洞断言） =="
mutate "${PROBE}" '    -c "ALTER TABLE block_relation ADD CONSTRAINT e4_force_rollback CHECK (target_id <> 1009);" >/dev/null \' \
                '    -c "SELECT 1;" >/dev/null \'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && case_is_red "scn_tx_rollback_block"; then
  say "   ✅ 精确转红（exit 1 · 业务码不是 5008 ⇒ 注入没生效也被抓到）"
  grep -E "❌ scn_tx_rollback_block" "${TMP_OUT}" | head -3 | sed 's/^/   | /'
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**）⇒ 「事务回滚」这条判据是**空洞的**"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi
restore "${PROBE}" run.sh

say ""
say "== 还原后必须重新转绿 =="
RC="$(run_probe)"
if [ "${RC}" = "0" ]; then
  say "   ✅ 还原后绿（exit 0）"
else
  say "   ❌ 还原后仍红（exit ${RC}）⇒ 还原没生效"
  tail -30 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi

say ""
if [ "${FAILED}" = "0" ]; then
  say "✅ 全部变异按预期表现 + 还原后转绿 ⇒ 探针非假绿"
  exit 0
fi
say "❌ 有变异没按预期表现 ⇒ 探针是假绿"
exit 1
