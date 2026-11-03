#!/usr/bin/env bash
#
# 变异验证：证明 `tools/difftest/like/run.sh` **不是假绿**（AGENTS.md §3.3.3）。
#
# 这条链有四段，每段都必须能被变异打红，否则探针就只是「自己跟自己比」：
#   ① **C 侧的真实字节**（Jackson 的字段名 / 文案）
#   ② **冻结的 expected.txt**（= Go 真源字节）
#   ③ **两侧各自的 Redis / PG 数据**（否则「两侧都空」分不出「相同」与「都空」）
#   ④ **失败路径**：一侧挂掉时探针必须转红（= FAILING-010 的守卫）
#
# 手法沿用 B 侧 `tools/api-full-parity-probe/follow/mutate-parity.sh` 与本仓
# `tools/difftest/follow/mutate-parity.sh`，但**被打的靶子从 B 侧换成 C 侧**
# （本探针的被测对象是 C 侧 ⇒ 变异必须打在 C 侧，否则证明的是「B 侧有牙齿」）。
#
# ⚠️ **还原一律用「文件备份 + 复制回来」** —— 绝不用 `git checkout --`：
#    本 lane 的改动可能还没提交 ⇒ `git checkout` 会把它们一起回滚掉。
# ⚠️ **每个 trap 都还原**：探针被杀掉（超时 / 手动 / 机器重启）时，
#    变异会留在文件里 ⇒ 下一轮的读数**没有意义** ⇒ 脚本退出前必须全部还原。
#
# 退出码：0 = 全部变异按预期表现 + 还原后转绿；1 = 有变异没按预期表现。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"
PROBE="${HERE}/run.sh"
EXPECTED="${HERE}/expected.txt"
# ⚠️ M1 的靶子：域内错误码**文案表**（5001 的文案 ⇒ `missing_required` 的 body 变）
ERRMSG="${C_ROOT}/service/like/src/main/java/com/rag/like/contract/ErrMsg.java"
# ⚠️ M5 的靶子：域内响应 DTO 的 **JSON 字段名**（`counts` 只出现在 `getlikecount` 一条用例上
#    ⇒ 变更**只**影响那一维 ⇒ 其余维必须仍绿）
DTO_COUNT="${C_ROOT}/service/like/src/main/java/com/rag/like/dto/count/GetLikeCountResp.java"

cd "${C_ROOT}"

TMP_OUT="$(mktemp)"
BAK_DIR="$(mktemp -d)"
# ⚠️ **不删临时目录**：`rm -rf` 在本机会被回收站守卫拦下 ⇒ 留着由系统清理。
say() { printf '%s\n' "$*"; }
FAILED=0

restore_all() {
  [ -f "${BAK_DIR}/err-msg.java" ] && cp "${BAK_DIR}/err-msg.java" "${ERRMSG}"
  [ -f "${BAK_DIR}/dto-count.java" ] && cp "${BAK_DIR}/dto-count.java" "${DTO_COUNT}"
  [ -f "${BAK_DIR}/expected.txt" ] && cp "${BAK_DIR}/expected.txt" "${EXPECTED}"
  [ -f "${BAK_DIR}/run.sh" ] && cp "${BAK_DIR}/run.sh" "${PROBE}"
  return 0
}
trap 'restore_all; rm -f "${TMP_OUT}" >/dev/null 2>&1' EXIT

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

# ⚠️ **判红/判绿必须按「用例名 + 后继分隔符」匹配**：
#    `likeaction` 是 `likeaction_double_failure` 的**前缀** ⇒ 裸 grep 会串台。
case_is_red() {   # $1 = 用例名
  grep -qE "❌ ${1}([ :：]|$)" "${TMP_OUT}"
}
case_is_green() { # $1 = 用例名
  grep -qE "✅ ${1}( |（|$)" "${TMP_OUT}" && ! case_is_red "$1"
}

# ⚠️ **探针 `die` 的原因行是「!! …」**（stderr）⇒ 它出现在 dump_logs 之前
#    ⇒ 只看 `tail -25` 会被日志尾巴挤掉 ⇒ ⇒ 失败时**先把它单独捞出来**。
die_reason() {
  grep -E "^!! " "${TMP_OUT}" | head -5 | sed 's/^/   | /'
  grep -E "^== |^   · |^   ✅|^   ❌" "${TMP_OUT}" | tail -12 | sed 's/^/   | /'
}

backup "${ERRMSG}"   err-msg.java
backup "${DTO_COUNT}" dto-count.java
backup "${EXPECTED}"  expected.txt
backup "${PROBE}"     run.sh

# ══════════════════════════════════════════════════════════════════════
# 🔴 启动前「变异残留自检」
#   ⚠️ **为什么必须有这一步**：变异脚本**被杀掉**（超时 / 手动 / 机器重启）时，
#      那一轮的变异**会留在文件里**（备份/还原只在轮次**结束时**跑）。
#      ⇒ 探针目录**未入 git** ⇒ **没有 git 基线可还原** ⇒ 残留会直接污染下一轮。
#   ⇒ ⇒ 每次启动前先自检：**发现残留 ⇒ 立刻拒绝启动**（并指出是哪个变异）。
# ══════════════════════════════════════════════════════════════════════
residue_check() {
  local bad=0
  if grep -q '"系统内部错误（变异残留）"' "${ERRMSG}"; then
    say "❌ **残留**：ErrMsg 的 5001 文案还留着 M1 的变异 ⇒ 先还原再跑"
    bad=1
  fi
  if grep -q '@JsonProperty("counts_x")' "${DTO_COUNT}"; then
    say "❌ **残留**：GetLikeCountResp 的字段名还留着 M5 的变异 ⇒ 先还原再跑"
    bad=1
  fi
  if grep -q "CASE|getlikecount|200|7b23" "${EXPECTED}"; then
    say "❌ **残留**：expected.txt 还留着 M2 改过的 hex ⇒ 先还原再跑"
    bad=1
  fi
  if grep -q 'seed_wire_read_target "${C_REDIS_CONTAINER}"$' "${PROBE}" && \
     grep -q 'redis-cli ZADD "target_liker_list:${TARGET_TYPE}:${TARGET_ID}" "${SEED_TS}" "9999"' "${PROBE}"; then
    say "❌ **残留**：探针里还留着 M3 的「C 侧多播一个成员」⇒ 先还原 run.sh 再跑"
    bad=1
  fi
  if grep -q 'redis-cli ACL LIST' "${PROBE}"; then
    say "❌ **残留**：探针里还留着 M6 的「去掉 ACL 注入」⇒ 先还原 run.sh 再跑"
    bad=1
  fi
  if [ "${bad}" = "1" ]; then
    say "❌ **拒绝启动**：上一轮的变异没有还原干净 ⇒ 跑出来的读数**没有意义**"
    exit 2
  fi
  say "   ✓ 启动前自检：四个变异靶点均为**原始形态**（无残留）"
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
# M1：改 **C 侧**错误码表的 5001 文案 ⇒ `missing_required` 的 body 变
#      ⇒ 只有那一条变红，其余 7 条仍绿 ⇒ 退出码非 0
# ─────────────────────────────────────────────────────────────
say ""
say "== M1 C 侧 5001 文案被改 ⇒ 只有 missing_required 变红（其余维仍绿） =="
mutate "${ERRMSG}" 'map.put(ErrCodes.ERROR_SERVER_COMMON, "系统内部错误");' \
                'map.put(ErrCodes.ERROR_SERVER_COMMON, "系统内部错误（变异残留）");'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && case_is_red "missing_required"; then
  say "   ✅ 精确转红（exit 1 · missing_required 与 B 侧/冻结值不符）"
  grep -E "❌ missing_required|C != B|C != expected" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
  if case_is_green "getlikecount" && case_is_green "likeaction" && case_is_green "no_token"; then
    say "   ✅ 其余维**仍绿**（getlikecount / likeaction / no_token）⇒ 变异**只**影响那一维"
  else
    say "   ❌ 其余维变红了 ⇒ 变异影响面**超出预期**（不是精确变异）"
    FAILED=1
  fi
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**，不是变异没生效）⇒ 探针看不见文案漂移"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi
restore "${ERRMSG}" err-msg.java

# ─────────────────────────────────────────────────────────────
# M2：改冻结的 expected.txt 一枚 hex ⇒ C 侧与冻结值不同 ⇒ 必须转红
#      ⇒ 证明 expected.txt 不是「谁都能写出来」的摆设，它**参与判定**
# ─────────────────────────────────────────────────────────────
say ""
say "== M2 冻结的 expected.txt 被改 ⇒ 必须转红 =="
mutate "${EXPECTED}" 'CASE|getlikecount|200|7b22' 'CASE|getlikecount|200|7b23'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && case_is_red "getlikecount"; then
  say "   ✅ 精确转红（exit 1 · getlikecount 与冻结值不符）"
  grep -E "❌ getlikecount|C != expected" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
  if case_is_green "getlikestate" && case_is_green "missing_required"; then
    say "   ✅ 其余维**仍绿**（getlikestate / missing_required）⇒ 期望值确实在参与判定"
  else
    say "   ❌ 其余维变红了 ⇒ 判定面**超出预期**"
    FAILED=1
  fi
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**）⇒ 探针没在比冻结值"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi
restore "${EXPECTED}" expected.txt

# ─────────────────────────────────────────────────────────────
# M3：让 **C 侧**的 ZSet 多一个成员 ⇒ 两侧数据不同 ⇒ 数据用例必须转红
#      ⇒ 证明探针真的在读**两侧各自的库**，而不是「两边都空」
# ─────────────────────────────────────────────────────────────
say ""
say "== M3 C 侧多播一个 ZSet 成员（两侧数据不同）⇒ 必须转红 =="
mutate "${PROBE}" 'seed_redis "${B_REDIS_CONTAINER}"
seed_redis "${C_REDIS_CONTAINER}"' \
'seed_redis "${B_REDIS_CONTAINER}"
seed_redis "${C_REDIS_CONTAINER}"
docker exec "${C_REDIS_CONTAINER}" redis-cli ZADD "target_liker_list:${TARGET_TYPE}:${TARGET_ID}" "${SEED_TS}" "9999" >/dev/null'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && case_is_red "gettargetlikerlist"; then
  say "   ✅ 精确转红（exit 1 · 两侧数据不同被抓到）"
  grep -E "❌ gettargetlikerlist|C != B" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
  if case_is_green "getlikecount" && case_is_green "getusertotallike"; then
    say "   ✅ 其余维**仍绿**（getlikecount / getusertotallike）⇒ 探针真在读两侧各自的库"
  else
    say "   ❌ 其余维变红了 ⇒ 判定面**超出预期**"
    FAILED=1
  fi
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**）⇒ 探针没在读两侧各自的库"
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
RC="$(run_probe PROBE_STOP_C_AFTER=getusertotallike)"
if [ "${RC}" = "1" ] && case_is_red "likeaction"; then
  say "   ✅ 精确转红（exit 1 · 请求失败被当成失败，而不是读残留 body）"
  grep -E "❌ likeaction|请求失败" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
  if case_is_green "getlikecount" && case_is_green "gettargetlikerlist"; then
    say "   ✅ 挂掉**之前**的用例仍绿 ⇒ 判据精确落在挂掉之后"
  else
    say "   ❌ 挂掉之前的用例也变红了 ⇒ 判定面**超出预期**"
    FAILED=1
  fi
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**，不是守卫失效）⇒ 探针会把失败请求当成通过"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi

# ─────────────────────────────────────────────────────────────
# M5：改 **C 侧**响应 DTO 的 **JSON 字段名** ⇒ 只有用到该字段的用例变红
#      ⇒ 证明探针看的是**真实字节**（字段名漂移会被抓到），不是「只看业务码」
# ─────────────────────────────────────────────────────────────
say ""
say "== M5 C 侧 getlikecount 的 JSON 字段名被改 ⇒ 只有那一条变红 =="
mutate "${DTO_COUNT}" '@JsonProperty("counts") Map<String, LikeCountItem> counts)' \
                    '@JsonProperty("counts_x") Map<String, LikeCountItem> counts)'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && case_is_red "getlikecount"; then
  say "   ✅ 精确转红（exit 1 · 字段名漂移被抓到）"
  grep -E "❌ getlikecount|C != B|C != expected" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
  if case_is_green "getlikestate" && case_is_green "getusertotallike" && case_is_green "missing_required"; then
    say "   ✅ 其余维**仍绿**（getlikestate / getusertotallike / missing_required）⇒ 变异**只**影响那一维"
  else
    say "   ❌ 其余维变红了 ⇒ 变异影响面**超出预期**"
    FAILED=1
  fi
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**）⇒ 探针看不见字段名漂移"
  tail -25 "${TMP_OUT}" | sed 's/^/   | /'
  FAILED=1
fi
restore "${DTO_COUNT}" dto-count.java

# ─────────────────────────────────────────────────────────────
# M6：把探针的 **E60 注入**去掉（ZCARD 不再被 ACL 禁用）
#      ⇒ E60 的**双失败分支**走不到 ⇒ 两侧**都不再记 business_error**
#      ⇒ 探针的「E60 业务错误分支」断言必须转红 ⇒ 证明这条判据**不是空洞断言**
# ⚠️ **注意：E60 的「响应字节」在注入前后是相同的**（likeaction 照常 +1 ⇒ 计数照常 +1）
#    ⇒ **敏感的是「业务错误分支」这条断言**，不是响应字节
#    ⇒ 若把判据押在响应字节上 ⇒ 这条变异**永远不会红**（⇒ 空洞判据的教科书形态）。
# ─────────────────────────────────────────────────────────────
say ""
say "== M6 探针去掉 E60 的 ACL 注入 ⇒ E60 的「业务错误分支」判据必须转红（证明它不是空洞断言） =="
mutate "${PROBE}" 'docker exec "${rc}" redis-cli ACL SETUSER default -zcard >/dev/null \' \
                'docker exec "${rc}" redis-cli ACL LIST >/dev/null \'
RC="$(run_probe)"
if [ "${RC}" = "1" ] && grep -qE "❌ E60 业务错误分支" "${TMP_OUT}"; then
  say "   ✅ 精确转红（exit 1 · 双失败分支没走到 ⇒ 两侧都没记 business_error）"
  grep -E "❌ E60 业务错误分支|likeaction_double_failure" "${TMP_OUT}" | head -4 | sed 's/^/   | /'
  if case_is_green "getlikecount" && case_is_green "likeaction" && case_is_green "likeaction_double_failure"; then
    say "   ✅ 其余维**仍绿**（getlikecount / likeaction / likeaction_double_failure）⇒ 变异**只**影响 E60 那一维"
  else
    say "   ❌ 其余维变红了 ⇒ 变异影响面**超出预期**"
    FAILED=1
  fi
else
  say "   ❌ 没按预期转红（exit ${RC}；exit 2 = **环境问题**）⇒ 「E60 双失败」这条判据是**空洞的**"
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
