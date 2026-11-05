package com.rag.task.messaging;

import com.rag.task.config.TaskRewardProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * <p><b>任务完成奖励事件发布</b> —— 1:1 对应 Go 端 {@code internal/reward/producer.go}
 * 的 {@code Product.Enqueue} 与 {@code internal/reward/type.go} 的 {@code NewEvent}。</p>
 *
 * <h2>⚠️ 本模块只写不读；消费方在 Go 侧<b>没有启动</b></h2>
 * <p>{@code reward.Worker}（{@code worker.go}）与 {@code Init.StartInitReward}
 * （{@code initReward.go}）<b>整个文件都是注释</b>，
 * {@code task.go:35} 的 {@code go Init.StartInitReward(ctx)} 调用也被注释了 ——
 * 因为它依赖的 {@code PointsClient} 在 {@code service_context.go:18-19} 就被注释掉了。
 * ⇒ 线上 {@code stream:task:reward} 里只有堆积的 XADD 记录，<b>没有任何消费者</b>。
 * C 侧刻意<b>不</b>补这个消费者：那是新增行为（会引入对 points 域的依赖），
 * 而 points 域<b>不在本项目的范围内</b>（AGENTS.md §1）。</p>
 *
 * <h2>⚠️ 幂等 ID 是 {@code uid + taskID} 的<b>加法</b>，不是拼接</h2>
 * <p>Go {@code NewEvent}（{@code type.go:25}）：
 * <pre>RewardID: uid + taskID</pre>
 * 例如 uid=1/taskID=1902 ⇒ 1903；uid=1903/taskID=1 也 ⇒ 1904 但数值相同的情形存在。
 * 这<b>不是</b>无碰撞的幂等键，但它是 Go 的既定行为 ——
 * 改成字符串拼接会让 reward_id 数值变化，属于可观察契约变更。</p>
 *
 * <h2>字段名是 L1 契约</h2>
 * <p>{@code producer.go:23-27} 的 map key 即 Stream 里的字段名：
 * {@code reward_id} / {@code uid} / {@code task_id} / {@code ts} / {@code score}。
 * ⚠️ 注意写出的是 {@code score}，而读取方 {@code parseRedisEvent}（{@code worker.go:116-121}）
 * 读的是 {@code add_score} —— <b>两端对不上</b>，读出来永远是 0。
 * 这是 Go 的现状；虽然 Worker 没启动，但字段名照抄不抄错。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.service} → {@code com.rag.task.messaging}
 *             （D7 包压平：Redis Stream 生产者归 {@code messaging/}）；</li>
 *         <li><b>取值方式</b>：B 侧用 {@code @Value("${task.reward.stream-key:stream:task:reward}")}
 *             ⇒ C 侧注入 {@link TaskRewardProperties}（同前缀、同键名、同默认值）
 *             ⇒ <b>配置键名一字未改</b>；</li>
 *         <li><b>字段 map</b>：B 侧用 {@code Map.of(...)}（<b>无序</b>）⇒ C 侧用
 *             {@link LinkedHashMap}（<b>有序</b>）—— Redis Stream 的字段在协议里是
 *             「key-value 对的列表」，<b>顺序可见</b>；本改动只把顺序<b>固定下来</b>，
 *             键与值逐条照抄，不改任何字段名或取值。</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 5 个字段名与取值、
 *       {@code reward_id = uid + taskID} 的加法、{@code ParseInt} 错误被丢弃、
 *       异常<b>向上抛</b>（不吞）逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskRewardPublisher.java:38-48（类声明 + 构造）
 * B 出处：…/service/TaskRewardPublisher.java:66-80（enqueue：reward_id 加法 + 5 个字段）
 * B 出处：…/service/TaskRewardPublisher.java:86-92（parseLongOrZero：ParseInt 且丢弃 error）
 * B 出处：…/service/TaskRewardPublisher.java:95-97（streamKey：诊断用）
 *       </pre></li>
 * </ol>
 */
@Component
public class TaskRewardPublisher {

    private final StringRedisTemplate redis;
    private final String streamKey;

    public TaskRewardPublisher(StringRedisTemplate redis, TaskRewardProperties properties) {
        this.redis = redis;
        this.streamKey = properties.getStreamKey();
    }

    /**
     * 对应 Go {@code reward.Product.Enqueue}。
     *
     * <p>⚠️ <b>返回值语义相反，别照抄</b>：Go 的 sink 是
     * {@code if err := c.rewardProduct.Enqueue(ctx, ev); err != nil { return err }}
     * —— <b>失败会让整个 complete 流程提前返回</b>
     * （于是 {@code markTaskDoneInDB} 与缓存失效都不会执行）。
     * 本方法必须<b>把异常抛给调用方</b>，否则会新增一条 Go 没有的"失败仍继续"路径。</p>
     *
     * @param uid      用户 ID（Go 侧是 string，内部 {@code strconv.ParseInt}）
     * @param taskId   任务 ID
     * @param addScore 奖励分值 —— ⚠️ 两个 sink 都传 <b>5</b>：
     *                 点赞路 {@code like_sink.go:355} 传 5；
     *                 文章路 {@code article_sink.go:447} 对 taskId=190 也传 <b>5</b>，
     *                 而该任务自己的 target 是 3。两者<b>刻意不联动</b>，照抄。
     */
    public void enqueue(String uid, String taskId, long addScore) {
        long parsedUid = parseLongOrZero(uid);
        long parsedTaskId = parseLongOrZero(taskId);
        long rewardId = parsedUid + parsedTaskId;   // ⚠️ Go 是加法，见类注释
        long ts = Instant.now().getEpochSecond();    // Go: time.Now().Unix()

        // ⚠️ 字段顺序 = Go 的 map 字面量顺序（producer.go:23-27），逐条照抄。
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("reward_id", String.valueOf(rewardId));
        fields.put("uid", String.valueOf(parsedUid));
        fields.put("task_id", String.valueOf(parsedTaskId));
        fields.put("ts", String.valueOf(ts));
        fields.put("score", String.valueOf(addScore));

        redis.opsForStream().add(MapRecord.create(streamKey, fields));
    }

    /**
     * Go {@code strconv.ParseInt(_uid, 10, 64)} 且<b>丢弃 error</b>（{@code uid, _ := ...}）
     * ⇒ 非法输入静默变成 0。
     */
    private static long parseLongOrZero(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 仅用于诊断：当前配置的 stream key。 */
    public String streamKey() {
        return streamKey;
    }
}
