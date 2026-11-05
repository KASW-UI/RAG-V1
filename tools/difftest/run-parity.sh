#!/usr/bin/env bash
#
# **双侧语义探针入口（B 侧 ↔ RAG-V1）** —— `tools/README.md` 里登记的入口。
#
# 每个域的探针住在自己的子目录里（域隔离：AGENTS.md §五.3f「共享判据 = 依赖度数，
# 不是目录位置」⇒ 域私有探针不属共享层），本文件只做**派发**。
#
# 用法：
#   bash tools/difftest/run-parity.sh                 # 跑全部域的探针
#   bash tools/difftest/run-parity.sh follow          # 只跑 follow 域
#   bash tools/difftest/run-parity.sh follow --mutate # 跑 follow 域的变异验证
#
# 退出码：0 = 全部探针通过；非 0 = 有探针失败（逐个域的退出码见输出）。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ⚠️ **域 → 探针目录登记表**（新增域的探针在此登记一行）。
#    形态统一：<域>/run.sh（主探针）· <域>/mutate-parity.sh（变异验证）
DOMAINS="follow users-user like favorite"

usage() {
  cat <<'USAGE'
用法：bash tools/difftest/run-parity.sh [域...] [--mutate]
  不带参数 = 跑全部已登记域
  --mutate  = 跑该域的变异验证（证明探针不是假绿）
已登记的域：follow · users-user · like · favorite
USAGE
}

WITH_MUTATE=0
TARGETS=()
for arg in "$@"; do
  case "${arg}" in
    --mutate) WITH_MUTATE=1 ;;
    -h|--help) usage; exit 0 ;;
    *) TARGETS+=("${arg}") ;;
  esac
done
[ "${#TARGETS[@]}" -gt 0 ] || TARGETS=(${DOMAINS})

FAILED=0
for domain in "${TARGETS[@]}"; do
  dir="${HERE}/${domain}"
  if [ ! -d "${dir}" ]; then
    printf '!! 域 %s 没有登记探针目录（%s 不存在）\n' "${domain}" "${dir}" >&2
    FAILED=1
    continue
  fi
  if [ "${WITH_MUTATE}" = "1" ]; then
    script="${dir}/mutate-parity.sh"
  else
    script="${dir}/run.sh"
  fi
  [ -f "${script}" ] || { printf '!! 找不到 %s\n' "${script}" >&2; FAILED=1; continue; }
  printf '\n========== %s · %s ==========\n' "${domain}" "$(basename "${script}")"
  bash "${script}"
  rc=$?
  if [ "${rc}" != "0" ]; then
    printf '❌ %s 的 %s 退出码 %s\n' "${domain}" "$(basename "${script}")" "${rc}"
    FAILED=1
  else
    printf '✅ %s 的 %s 通过\n' "${domain}" "$(basename "${script}")"
  fi
done

printf '\n'
if [ "${FAILED}" = "0" ]; then
  printf '✅ 全部探针通过\n'
  exit 0
fi
printf '❌ 有探针失败\n'
exit 1
