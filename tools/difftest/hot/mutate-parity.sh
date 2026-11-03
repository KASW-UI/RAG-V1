#!/usr/bin/env bash
#
# 变异验证：证明 `tools/difftest/hot/run.sh`（双侧活体对拍探针）**不是假绿**
# （AGENTS.md §3.3.3「变异没变红 = 没覆盖到」）。
#
# ⚠️ **五条变异分属不同的支撑机制 ⇒ 变红的用例集合必须能区分「哪个机制在支撑哪条断言」**：
#
#   变异                                  │ 变红的面                │ 保持绿的面
#   ──────────────────────────────────────┼─────────────────────────┼──────────────────
#   V1 DEFAULT_PAGE_SIZE 20 → 21         │ HTTP：page_size_zero    │ HTTP：default（缺省值写在
#   （分页默认值）                        │                        │ controller 里）· page_size_over_max
#   │                                    │                        │ wire / redis
#   V2 LIKE_UNIMPLEMENTED_MSG 加 "!"     │ wire：wire_like_article │ HTTP / redis
#   （gRPC status description）           │                        │
#   V3 ROLLING_SCOPE "rolling"→"rolling_v2"│ HTTP：default           │ HTTP：page_not_a_number
#   （HTTP 响应字段）                    │ （scope 字段变了）       │ wire（wire 的 message 里没有 scope）
#   V4 **探针自身**：只给 B 侧多播一个成员  │ HTTP + wire + **redis** │ wire：wire_like_article
#   （判据空转检验）                      │                        │
#   V5 HOT_ARTICLES_KEY 改键名            │ **数据面闸门 die(exit 2)**│ ——（**不许**报「全绿」）
#   （数据面没跑起来）                    │                        │
#
# ⚠️ **V5 是「探针不会在数据面没跑起来时给结论」的证据**：
#   key 改名 ⇒ C 侧读不到热榜 ⇒ total=0 ⇒ 数据面闸门必须 **die**。
#   ⇒ 若探针此时报「全绿」，那就又回到了「两个空壳相等」的**假绿**
#   （**2026-11-04 第一轮 E-4 跑就踩过这个坑**：探针的 `free_probe_ports` 把自己的
#   article-rpc 桩踢掉 ⇒ 两侧补全整片 skip ⇒ HTTP 面全在比空壳 ⇒ 详见台账 §九·9.5）。
#
# ⚠️ **V4 是「M3 判据不是摆设」的证据**：
#   两侧种子相同时，若探针读错了容器 / 读错了 key，读数也会**相等** ⇒ 判据空转。
#   ⇒ 只给一侧加一个成员 ⇒ 读数必须不等 ⇒ 否则 M3 这条断言就是摆设。
#
# ⚠️ **还原一律用「文件备份 + 复制回来」** —— 绝不用 `git checkout --`：
#   本 lane 的改动可能还没提交 ⇒ `git checkout` 会把它们一起回滚掉。
# ⚠️ **每个 trap 都还原**：脚本被杀掉（超时 / 手动 / 机器重启）时，
#   变异会留在文件里 ⇒ 下一轮的读数**没有意义** ⇒ 退出前必须全部还原。
#
# 用法：
#   bash tools/difftest/hot/mutate-parity.sh
#   SKIP_BASELINE=1 bash tools/difftest/hot/mutate-parity.sh   # 跳过最后的基线复跑
#
# 退出码：0 = 全部变异按预期表现 + 还原后转绿；1 = 有变异没按预期表现。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
C_ROOT="$(cd "${HERE}/../../.." && pwd)"
RUN="${HERE}/run.sh"

# ── 变异靶子（C 侧实现）────────────────────────────────────────────────────────
T_SUPPORT="${C_ROOT}/service/hot/src/main/java/com/rag/hot/service/HotApiSupport.java"
T_STUB="${C_ROOT}/service/hot/src/main/java/com/rag/hot/service/InteractionStubService.java"
T_STORE="${C_ROOT}/service/hot/src/main/java/com/rag/hot/messaging/HotRedisSnapshotStore.java"

BAK_DIR="$(mktemp -d)"
say() { printf '%s\n' "$*"; }

FAILED=0

restore_all() {
  [ -f "${BAK_DIR}/support.java" ] && cp "${BAK_DIR}/support.java" "${T_SUPPORT}"
  [ -f "${BAK_DIR}/stub.java" ]    && cp "${BAK_DIR}/stub.java"    "${T_STUB}"
  [ -f "${BAK_DIR}/store.java" ]   && cp "${BAK_DIR}/store.java"   "${T_STORE}"
  return 0
}
trap 'restore_all' EXIT

backup() { cp "$1" "${BAK_DIR}/$2"; }

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

# ── 跑一轮探针 ────────────────────────────────────────────────────────────────
OUT="$(mktemp)"
run_probe() {   # run_probe <额外环境变量...> ⇒ 把输出落盘，打印退出码
  : > "${OUT}"
  ( cd "${C_ROOT}" && env "$@" SKIP_KAFKA=1 bash "${RUN}" ) >"${OUT}" 2>&1
  local rc=$?
  printf '%s' "${rc}"
}

# ⚠️ **红名单从 run.sh 的机器摘要行读**（不 grep 日志噪声）：
#    摘要行格式 = `❌ 存在字节差异（用例：<空格分隔的用例名>）`
red_list() {
  python3 - "${OUT}" <<'PY'
import sys, re, io
s = io.open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r"存在字节差异（用例：(.*?)）", s)
print((m.group(1) if m else "").strip())
PY
}

expect_red() {   # expect_red <用例名> <说明>
  local got; got="$(red_list)"
  case " ${got} " in
    *" $1 "*) say "   ✅ $1 ⇒ 红（$2）"; return 0 ;;
  esac
  say "   ❌ $1 ⇒ 实得 **绿**、期望 **红**（$2）"
  say "      红名单 = [${got}]"
  FAILED=1
  return 1
}
expect_green() { # expect_green <用例名> <说明>
  local got; got="$(red_list)"
  case " ${got} " in
    *" $1 "*) say "   ❌ $1 ⇒ 实得 **红**、期望 **绿**（$2）"; say "      红名单 = [${got}]"
             FAILED=1; return 1 ;;
  esac
  say "   ✅ $1 ⇒ 绿（$2）"
  return 0
}

expect_exit() {  # expect_exit <实际 rc> <期望 rc> <说明>
  if [ "$1" = "$2" ]; then
    say "   ✅ 退出码 = $2（$3）"
    return 0
  fi
  say "   ❌ 退出码 = $1、期望 $2（$3）"
  say "      ---- 探针输出末尾 ----"
  tail -25 "${OUT}" | sed 's/^/      | /'
  FAILED=1
  return 1
}

backup "${T_SUPPORT}" support.java
backup "${T_STUB}"    stub.java
backup "${T_STORE}"   store.java

# ══════════════════════════════════════════════════════════════════════
# 🔴 **启动前「变异残留自检」**：目标文件里不许已经有变异留下的痕迹
#    （否则本轮读数不可归因 —— 上一轮变异没还原干净）。
# ══════════════════════════════════════════════════════════════════════
say "== 变异残留自检 =="
if grep -q 'DEFAULT_PAGE_SIZE = 21' "${T_SUPPORT}"; then
  say "   ❌ ${T_SUPPORT} 里已有 V1 的残留 ⇒ 先还原再跑"
  exit 1
fi
if grep -q 'LikeArticle not implemented yet!' "${T_STUB}"; then
  say "   ❌ ${T_STUB} 里已有 V2 的残留 ⇒ 先还原再跑"
  exit 1
fi
if grep -q 'ROLLING_SCOPE = "rolling_v2"' "${T_SUPPORT}"; then
  say "   ❌ ${T_SUPPORT} 里已有 V3 的残留 ⇒ 先还原再跑"
  exit 1
fi
if grep -q '"hot:articles-x"' "${T_STORE}"; then
  say "   ❌ ${T_STORE} 里已有 V5 的残留 ⇒ 先还原再跑"
  exit 1
fi
say "   ✓ 无残留"

# ══════════════════════════════════════════════════════════════════════
# V1 · 分页默认值 20 → 21
# ⚠️ **只有 `page_size_zero` 会红**：HTTP 的**缺省**值写在 **controller 里**（字面量 20），
#    不是 `DEFAULT_PAGE_SIZE` ⇒ `default`（不带参数）那条**不受影响**。
#    ⇒ 这条本身就是「读代码 ≠ 读行为」的一个例子（与 V3 的对照）。
# ══════════════════════════════════════════════════════════════════════
say ""
say "== V1 · HotApiSupport.DEFAULT_PAGE_SIZE 20 → 21 =="
mutate "${T_SUPPORT}" 'DEFAULT_PAGE_SIZE = 20' 'DEFAULT_PAGE_SIZE = 21' || exit 1
rc="$(run_probe)"
expect_exit "${rc}" "1" "V1 生效 ⇒ 探针必须报红"
expect_red "page_size_zero" "page_size<=0 归一到默认值 ⇒ 跟着变"
expect_green "default" "缺省值写在 controller 里（字面量 20）⇒ 不受影响"
expect_green "page_size_over_max" "显式给的 page_size 走 MAX_PAGE_SIZE 上限 ⇒ 不受影响"
expect_green "wire:wire_like_article" "wire 面与分页无关"
restore_all
say "   · V1 已还原"

# ══════════════════════════════════════════════════════════════════════
# V2 · gRPC status description 文案加 "!"
# ══════════════════════════════════════════════════════════════════════
say ""
say "== V2 · InteractionStubService.LIKE_UNIMPLEMENTED_MSG 加 '!' =="
mutate "${T_STUB}" 'LIKE_UNIMPLEMENTED_MSG = "LikeArticle not implemented yet"' 'LIKE_UNIMPLEMENTED_MSG = "LikeArticle not implemented yet!"' || exit 1
rc="$(run_probe)"
expect_exit "${rc}" "1" "V2 生效 ⇒ 探针必须报红"
expect_red "wire:wire_like_article" "status description 的字节变了"
expect_green "default"          "HTTP 面不调 likeArticle ⇒ 不受影响"
expect_green "wire:wire_comment_article" "只改了 like 的文案 ⇒ comment 不受影响"
restore_all
say "   · V2 已还原"

# ══════════════════════════════════════════════════════════════════════
# V3 · `scope` 常量 "rolling" → "rolling_v2"（HTTP 面的**响应字段**）
# ╠ **只影响 HTTP 面**：wire 的 `HotArticleItem` 里**没有** scope 字段。
# ══════════════════════════════════════════════════════════════════════
say ""
say "== V3 · HotApiSupport.ROLLING_SCOPE \"rolling\" → \"rolling_v2\" =="
mutate "${T_SUPPORT}" 'ROLLING_SCOPE = "rolling"' 'ROLLING_SCOPE = "rolling_v2"' || exit 1
rc="$(run_probe)"
expect_exit "${rc}" "1" "V3 生效 ⇒ 探针必须报红"
expect_red "default"                "响应里的 scope 字段变了"
expect_green "page_not_a_number"     "5001 信封里没有 scope ⇒ 不受影响"
expect_green "wire:wire_topk_20"     "wire 的 message 里没有 scope ⇒ 不受影响"
expect_green "wire:wire_like_article" "wire 面与 scope 无关"
restore_all
say "   · V3 已还原"

# ══════════════════════════════════════════════════════════════════════
# V4 · **探针自身变异**：只给 B 侧多播一个成员 ⇒ M3 判据不许空转
# ══════════════════════════════════════════════════════════════════════
say ""
say "== V4 · 探针钩子 PROBE_SEED_ASYMMETRIC=1（只给 B 侧多播一个成员）=="
rc="$(run_probe PROBE_SEED_ASYMMETRIC=1)"
expect_exit "${rc}" "1" "V4 生效 ⇒ 探针必须报红"
expect_red "redis:hot:articles" "两侧种子不同 ⇒ Redis 状态面必须不等"
expect_red "default"             "两侧 items 不同 ⇒ HTTP 面必须不等"
expect_red "wire:wire_topk_20"   "wire 面读的就是 hot:articles ⇒ 也必须不等"
expect_green "wire:wire_like_article" "wire 的互动桩不看种子 ⇒ 不受影响"
say "   · V4 用的是探针钩子 ⇒ 无需还原文件"

# ══════════════════════════════════════════════════════════════════════
# V5 · 热榜 key 改名 ⇒ **数据面闸门必须拦下**（不是「报红」，是「拒绝出结论」）
# ⚠️ **这一条验的不是「探针能发现差异」，而是「探针不会在数据面没跑起来时给结论」**：
#    key 改名 ⇒ C 侧读不到热榜 ⇒ total=0 ⇒ 数据面闸门必须 **die（exit 2）**
#    ⇒ 若探针此时报「全绿」，那就又回到了「两个空壳相等」的**假绿**。
# ══════════════════════════════════════════════════════════════════════
say ""
say "== V5 · HotRedisSnapshotStore.HOT_ARTICLES_KEY 改键名（数据面闸门必须拦下）=="
mutate "${T_STORE}" 'HOT_ARTICLES_KEY = "hot:articles"' 'HOT_ARTICLES_KEY = "hot:articles-x"' || exit 1
rc="$(run_probe)"
expect_exit "${rc}" "2" "V5 生效 ⇒ 数据面闸门必须 die（exit 2），**不许**报「全绿」"
if grep -q "数据面没跑起来" "${OUT}"; then
  say "   ✅ 数据面闸门确实拦下了（输出含「数据面没跑起来」）"
else
  say "   ❌ 数据面闸门**没有**拦下 ⇒ 探针在数据面没跑起来时仍会给结论 ⇒ 假绿风险"
  FAILED=1
fi
restore_all
say "   · V5 已还原"

# ══════════════════════════════════════════════════════════════════════
# 基线复跑：全部还原后必须转绿（否则「红」是环境问题，不是变异）
# ══════════════════════════════════════════════════════════════════════
if [ "${SKIP_BASELINE:-0}" != "1" ]; then
  say ""
  say "== 基线复跑（全部还原 ⇒ 必须转绿）=="
  restore_all
  rc="$(run_probe)"
  expect_exit "${rc}" "0" "还原后必须全绿"
  if [ -n "$(red_list)" ]; then
    say "   ❌ 还原后仍有红用例：$(red_list)"
    FAILED=1
  else
    say "   ✅ 还原后红名单为空"
  fi
fi

echo
if [ "${FAILED}" = "0" ]; then
  say "✅ 五条变异全部按预期表现（探针有牙齿）"
  exit 0
fi
say "❌ 有变异没按预期表现 ⇒ 探针存在判据空洞"
exit 1
