#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""task 域变异验证（可复跑）—— 每条变异要求「目标用例红 ＋ 对照用例仍绿」＋ 还原后回绿。

纪律（AGENTS §3.3）：变异没变红 = 断言没覆盖到；只跑本仓探针 = 假绿。
用法：python3 tools/difftest/task/mutate-task.py
"""
import pathlib, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1.worktrees/task")
CP = ROOT / "service/task/src/main/java/com/rag/task"
BACKUP = pathlib.Path(tempfile.mkdtemp(prefix="task-mutate-"))

MUTATIONS = [
    {
        "id": "M-1",
        "desc": "打断共享层 JSON 转换器的退让（改 bean 名 ⇒ 共享层 JsonBodyConverter 不再让位）",
        "file": CP / "config/TaskApiJsonBodyConfig.java",
        "old": '\n@Configuration("seaJsonBodyConverterConfigurer")\n',
        "new": '\n@Configuration("taskApiJsonBodyConfigMUTATED")\n',
        "target": "TaskApiWireContractRealTomcatTest#nonJsonContentTypeIsStillParsed",
        "control": "TaskApiWireContractRealTomcatTest#jsonBodyReturnsBareResponse",
    },
    {
        "id": "M-2",
        "desc": "让浮点 id 在第一段就被拒（丢掉「字面量保留 ⇒ ParseInt 失败」这条判据）",
        "file": CP / "dto/task/GetTaskReq.java",
        "old": """        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) {
            return String.valueOf(n.longValue());
        }
        return String.valueOf(n); // BigInteger / BigDecimal / Double / Float""",
        "new": """        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) {
            return String.valueOf(n.longValue());
        }
        throw new BadIdTypeException(ID_MUST_BE_STRING_OR_INTEGER); // MUTATED""",
        "target": "GetTaskReqNormalizationTest#fractionalIdKeepsFractionalText",
        "control": "GetTaskReqNormalizationTest#nonScalarIdIsRejectedAtUnmarshalTime",
    },
    {
        "id": "M-3",
        "desc": "把 GREATEST 改成普通赋值（进度可回落）",
        "file": CP / "mapper/TaskProgressMapper.java",
        "old": "progress = GREATEST(task_progress.progress, EXCLUDED.progress),",
        "new": "progress = EXCLUDED.progress, -- MUTATED",
        "target": "TaskProgressMapperRealPgTest#progressNeverDecreases",
        "control": "TaskProgressMapperRealPgTest#doneStatusIsNeverReverted",
    },
    {
        "id": "M-5",
        "desc": "停掉用户路 sink 的 listener（autoStartup=false）⇒ 消费面必须断",
        "file": CP / "messaging/TaskUserLikeProgressSink.java",
        "old": '    @KafkaListener(id = "task-user-progress-sink",\n',
        "new": '    @KafkaListener(id = "task-user-progress-sink", autoStartup = "false",\n',
        "target": "TaskKafkaConsumeRealTest#userPathIsConsumedAndPersisted",
        "control": "TaskKafkaConsumeRealTest#articlePathIsConsumedAndPersisted",
    },
    {
        "id": "M-4",
        "desc": "去掉 TaskCountStore 的 group 命名空间（用户路与文章路共用 key）",
        "file": CP / "messaging/TaskCountStore.java",
        "old": '''    static String keyOf(String group, String key) {
        return COUNTER_KEY_PREFIX + group + ":" + key;
    }''',
        "new": '''    static String keyOf(String group, String key) {
        return COUNTER_KEY_PREFIX + key; // MUTATED: 去掉 group 命名空间
    }''',
        "target": "TaskCountStoreRealRedisTest#differentGroupsAreIsolated",
        "control": "TaskCountStoreRealRedisTest#sameGroupAndKeyCountsUp",
    },
]


def run(test_filter):
    """跑单个测试方法，返回 (是否红, 失败用例名列表)。"""
    cmd = ["mvn", "-o", "-pl", "service/task", "-am", "test",
           f"-Dtest={test_filter}", "-DfailIfNoTests=false",
           "-Dsurefire.failIfNoSpecifiedTests=false"]
    if test_filter.startswith("TaskKafkaConsumeRealTest"):
        # ⚠️ 该类是 @Tag("external-env") ⇒ 默认回归被排除 ⇒ 必须显式开 profile
        cmd.append("-Pexternal-env")
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=1800)
    log = r.stdout + r.stderr
    failed = [ln.split(" in ")[0].strip() for ln in log.splitlines()
              if ln.startswith("[ERROR]   ") and "Time elapsed" not in ln]
    return (r.returncode != 0), failed, log


def main():
    print("=" * 78)
    print("task 域变异验证 —— 每条：目标用例红 ＋ 对照用例仍绿 ＋ 还原后回绿")
    print("=" * 78)
    results = []
    only = set(sys.argv[1:]) or None
    for m in MUTATIONS:
        if only and m["id"] not in only:
            continue
        print(f"\n### {m['id']} —— {m['desc']}")
        src = m["file"].read_text(encoding="utf-8")
        if src.count(m["old"]) != 1:
            print(f"  ❌ 锚点命中 {src.count(m['old'])} 处（应为 1）⇒ 跳过")
            results.append((m["id"], "SKIP-锚点"))
            continue
        bak = BACKUP / (m["id"] + ".bak")
        shutil.copy2(m["file"], bak)
        try:
            m["file"].write_text(src.replace(m["old"], m["new"]), encoding="utf-8")
            red, failed, log = run(m["target"])
            print(f"  目标用例 [{m['target']}] ⇒ {'🔴 转红（判据有牙齿）' if red else '⚪ 仍绿 ⇒ 断言没覆盖到'}")
            if failed:
                print(f"      失败用例: {failed[:3]}")
            green, _, _ = run(m["control"])
            print(f"  对照用例 [{m['control']}] ⇒ {'⚠️ 也红了（变异面过大）' if green else '✅ 仍绿（变异面精确）'}")
        finally:
            shutil.copy2(bak, m["file"])
        back, _, _ = run(m["target"])
        print(f"  还原后 [{m['target']}] ⇒ {'✅ 回绿' if not back else '🔴 仍红 ⇒ 还原失败'}")
        results.append((m["id"], f"target_red={red} control_red={green} restored_green={not back}"))

    print("\n" + "=" * 78)
    for i, r in results:
        print(f"  {i}: {r}")
    bad = [r for r in results if "target_red=True" not in r[1] or "restored_green=True" not in r[1]]
    print("=" * 78)
    print("变异验证", "EXIT=0（全部有牙齿）" if not bad else f"EXIT=1（未达预期: {bad}）")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
