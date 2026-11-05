package com.rag.task.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.rag.task.support.TaskRealInfraTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * <p><b>{@code TaskCountStore} 的 key 命名空间（真 Redis）</b> —— 钉住 Go 侧
 * 「<b>按 group 分目录</b>的本地 KV 表」在 Redis 上的等价物。</p>
 *
 * <h2>🔴 为什么这条只能靠真 Redis 验</h2>
 * <p>B 侧 Go 的 {@code goka.Persist}（RocksDB 本地表）<b>没有生态等价物</b>
 * ⇒ B 侧已用 <b>Redis {@code INCR} + group 命名空间</b>落地（<b>选型由 B 侧定案</b>）。
 * <p>⇒ <b>group 必须出现在 key 里</b> —— 否则「用户路（group {@code task.rpc.consumer61}）」
 * 与「文章路（group {@code task.rpc.consumer71}）」的同一条 Kafka 消息 key
 * 会<b>共用一个计数器</b> ⇒ 计数串台 ⇒ 位点判断错误。</p>
 *
 * <h2>🔴 为什么本类必须<b>调用被测类</b>（而不是自己拼 key）</h2>
 * <p>⚠️ **本轮实测踩到的坑**：原先这条判据写在 {@code service/TaskRedisPipelineRealRedisTest} 里，
 * 而它<b>自己手拼了 key</b>（{@code "ragv1:count:" + group + ":" + key}）
 * ⇒ ⇒ <b>根本没调用</b> {@code TaskCountStore} ⇒ ⇒ 去掉 {@code TaskCountStore} 里的 group 命名空间
 * <b>这条用例仍然绿</b>（<b>假覆盖</b>）⇒ ⇒ 变异验证 M-4 第一轮「目标仍绿 ⇒ 断言没覆盖到」。
 * <p>⇒ 修法（本类）：<b>直接用生产 bean</b>（{@link TaskCountStore#increment}），
 * 并把 key 的<b>字面形态</b>用 {@link TaskCountStore#keyOf} 钉住
 * ⇒ ⇒ 这样「去掉 group」必然让 ② 转红。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> —— 存储后端由 Go 的 RocksDB 换成 Redis
 *       （<b>该选型是 B 侧定的</b>：B 侧 {@code TaskCountStore} 本身就是 Redis {@code INCR}），
 *       本类<b>不引入</b>任何新的计数语义；</li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— group 命名空间位置、前缀字面量、INCR 语义均照抄；</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/TaskCountStore.java:122-137（COUNTER_KEY_PREFIX）
 * B 出处：…/mq/TaskCountStore.java:149-158（increment = Go ctx.Value() + cur++ + ctx.SetValue）
 * B 出处：…/mq/TaskCountStore.java:174-176（keyOf = 前缀 + group + ":" + key）
 *       </pre></li>
 * </ol>
 */
class TaskCountStoreRealRedisTest extends TaskRealInfraTestBase {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TaskCountStore store;

    @Test
    @DisplayName("① 同一 (group, key) 连续 INCR ⇒ 1, 2, 3（真 Redis）")
    void sameGroupAndKeyCountsUp() {
        String key = "880100021";
        store.increment("group-user", key);
        store.increment("group-user", key);
        long third = store.increment("group-user", key);

        assertThat(third).as("同一 (group,key) 的第三次 INCR 必须是 3").isEqualTo(3L);
        assertThat(store.current("group-user", key)).isEqualTo(3L);
    }

    @Test
    @DisplayName("② 🔴 同一条 key、不同 group ⇒ 计数各自独立（group 必须进 key 里）")
    void differentGroupsAreIsolated() {
        String key = "880100022";
        store.increment("group-user", key);
        store.increment("group-user", key);

        // ⚠️ **同一个 key**、另一个 group ⇒ 必须是 1（而不是 3）
        long articleCount = store.increment("group-article", key);

        assertThat(articleCount)
                .as("去掉 group 命名空间 ⇒ 这里会变成 3 ⇒ 这条用例就是「命名空间隔离」的判据")
                .isEqualTo(1L);
        assertThat(store.current("group-user", key)).isEqualTo(2L);
        assertThat(store.current("group-article", key)).isEqualTo(1L);
    }

    @Test
    @DisplayName("③ 🔴 key 的字面形态 = task:goka:count:{group}:{key}（前缀 ＋ group 位置都钉住）")
    void keyShapeIsPinned() {
        String key = "880100023";

        assertThat(TaskCountStore.keyOf("group-user", key))
                .as("key 的字面形态是 L1 契约（Go 侧「按 group 分目录」）")
                .isEqualTo("task:goka:count:group-user:" + key);
        assertThat(TaskCountStore.COUNTER_KEY_PREFIX)
                .as("计数器前缀刻意区别于 TaskRedisKeys 的 task:progress / task:init / task:done")
                .isEqualTo("task:goka:count:");

        store.increment("group-user", key);
        assertThat(redis.hasKey("task:goka:count:group-user:" + key))
                .as("真 Redis 里必须真的落在该 key 上")
                .isTrue();
        assertThat(redis.hasKey("task:goka:count:group-article:" + key))
                .as("另一个 group 必须是另一个 key")
                .isFalse();
    }
}
