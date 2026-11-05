package com.rag.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rag.task.support.TaskRealInfraTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * <p><b>Redis pipeline 的结果形状（真 Redis）</b> —— 钉住
 * {@code RedisPipelines#toBooleans} 依赖的那条<b>前提</b>。</p>
 *
 * <h2>🔴 为什么这条前提只能靠真 Redis 验</h2>
 * <p>{@code RedisPipelines#toBooleans} 把 pipeline 的结果列表转成布尔列表，
 * 而它<b>只在「pipeline（非事务）模式下每条结果是单个 {@code Boolean}</b>时才成立
 * （真 Redis 实测：{@code [true, true, true]}）。
 * <p>⇒ 若误用 {@code MULTI/EXEC} 事务模式，每条结果是 {@code [value, ok]} 这样的
 * {@code List} ⇒ {@code toBooleans} 会<b>静默返回全 false</b>
 * ⇒ ⇒ 「本批全部新建」会被误判成「本批全部已存在」⇒ <b>没有异常、没有日志</b>
 * ⇒ ⇒ 那是一条<b>假绿</b>，且只在真 Redis 上才暴露。
 * <p>⇒ 本类用真 Redis 验两件事：
 * <ol>
 *   <li>{@code exec} 返回的元素<b>确实是单个 Boolean</b>（不是 List）；</li>
 *   <li>{@code toBooleans} 的<b>防呆分支</b>（遇到 List 元素就抛）在真 Redis 上<b>不会误触发</b>
 *       ——即 pipeline 形状是对的。</li>
 * </ol>
 *
 * <h2>🔴 SETNX 闸门的语义（两个 sink 的「新用户」判定）</h2>
 * <p>两个 sink 的第一步都是「批量 {@code SETNX key "1" EX ttl}，
 * <b>只有返回 true 的才算新用户</b>」⇒ 闸门一旦建好，后续批次<b>不得</b>再触发
 * HSET + DB 插入。
 * <p>⇒ 本类用真 Redis 验：第一次全 true、第二次全 false、<b>顺序必须与入参一致</b>
 * （Go 正是靠「按下标配对」来把 SETNX 结果映射回 uid 的）。</p>
 *
 * <h2>⚠️ 本类<b>不</b>再断言「计数器命名空间隔离」</h2>
 * <p>⚠️ **2026-11-03 变异验证 M-4 实测发现**：原先那条「计数器 INCR 跨 key 隔离」的用例
 * <b>自己手拼了 key</b>（{@code "ragv1:count:" + group + ":" + key}）⇒ <b>根本没调用</b>
 * {@code TaskCountStore} ⇒ ⇒ 去掉被测类里的 group 命名空间<b>它仍然绿</b>（<b>假覆盖</b>）
 * ⇒ ⇒ 该判据已迁到 {@code messaging/TaskCountStoreRealRedisTest}（<b>直接用生产 bean</b>
 * ＋ {@code TaskCountStore#keyOf} 钉住字面形态）⇒ ⇒ 现在 M-4 变异能精确转红。</p>
 *
 * <h2>⚠️ 本类<b>不</b>断言 key 的字面量</h2>
 * <p>key 拼法是 L1 契约，但那些断言属于<b>纯函数</b>（{@code TaskRedisKeys}）⇒
 * 放在纯单元测试里更合适；本类只验<b>与 Redis 交互</b>的那一半。</p>
 */
class TaskRedisPipelineRealRedisTest extends TaskRealInfraTestBase {

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("① 🔴 pipeline 结果是单个 Boolean（不是事务模式的 [value, ok] 数组）")
    void pipelineResultsAreSingleBooleans() {
        List<Object> raw = RedisPipelines.exec(redis, ops -> {
            ops.opsForValue().setIfAbsent("ragv1:probe:a", "1", Duration.ofSeconds(30));
            ops.opsForValue().setIfAbsent("ragv1:probe:b", "1", Duration.ofSeconds(30));
        });

        assertThat(raw).hasSize(2);
        assertThat(raw.get(0)).as("pipeline（非事务）模式 ⇒ 单个 Boolean").isInstanceOf(Boolean.class);
        assertThat(raw.get(1)).isInstanceOf(Boolean.class);
        assertThat(raw).doesNotHaveAnyElementsOfTypes(List.class);
    }

    @Test
    @DisplayName("①-b toBooleans 在真 pipeline 上正常工作（防呆分支不误触发）")
    void toBooleansWorksOnRealPipeline() {
        List<Object> raw = RedisPipelines.exec(redis, ops -> {
            ops.opsForValue().setIfAbsent("ragv1:probe:c", "1", Duration.ofSeconds(30));
        });

        assertThat(RedisPipelines.toBooleans(raw)).containsExactly(true);
    }

    @Test
    @DisplayName("①-c 🔴 toBooleans 的防呆分支：遇到 List 元素（事务模式）必须抛，而不是静默全 false")
    void toBooleansRejectsTransactionShapedResults() {
        List<Object> transactionShaped = List.of(List.of("1", true));

        assertThatThrownBy(() -> RedisPipelines.toBooleans(transactionShaped))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MULTI/EXEC");
    }

    @Test
    @DisplayName("② 🔴 SETNX 闸门：第一次全 true、第二次全 false，且顺序与入参一致")
    void setNxGateIsIdempotentAndOrdered() {
        List<Long> uids = List.of(880_100_001L, 880_100_002L, 880_100_003L);
        redis.delete(uids.stream().map(u -> TaskRedisKeys.likeTaskInit(u, 1902L)).toList());

        List<Boolean> first = setNxAll(uids);
        assertThat(first).as("首次 ⇒ 全是新用户").containsExactly(true, true, true);

        List<Boolean> second = setNxAll(uids);
        assertThat(second).as("第二次 ⇒ 全都不是新用户").containsExactly(false, false, false);
    }

    @Test
    @DisplayName("②-b 闸门带 TTL（不是永久 key）")
    void setNxGateCarriesTtl() {
        long uid = 880_100_010L;
        String key = TaskRedisKeys.likeTaskInit(uid, 1902L);
        redis.delete(key);

        RedisPipelines.exec(redis, ops ->
                ops.opsForValue().setIfAbsent(key, "1", TaskRedisKeys.LIKE_TASK_TTL));

        Long ttl = redis.getExpire(key);
        assertThat(ttl).as("TTL 必须存在（>0）").isNotNull().isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(TaskRedisKeys.LIKE_TASK_TTL.toSeconds());
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private List<Boolean> setNxAll(List<Long> uids) {
        List<Object> raw = RedisPipelines.exec(redis, ops -> {
            for (Long uid : uids) {
                ops.opsForValue().setIfAbsent(
                        TaskRedisKeys.likeTaskInit(uid, 1902L), "1", TaskRedisKeys.LIKE_TASK_TTL);
            }
        });
        return RedisPipelines.toBooleans(raw);
    }
}
