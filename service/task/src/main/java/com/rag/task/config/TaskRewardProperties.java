package com.rag.task.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <p><b>task 域的奖励事件流配置</b> —— 对应 Go 端
 * {@code service/task/rpc/etc/task.yaml} 的 {@code Reward.*} 段。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li>B 侧<b>没有</b> Properties 类：{@code TaskRewardPublisher} 用
 *             {@code @Value("${task.reward.stream-key:stream:task:reward}")} 直接取值
 *             ⇒ 本类把同一段配置<b>显式化</b>（{@code @ConfigurationProperties} 形态，
 *             与本域其它两个配置块 {@code task.kafka} / {@code task.definitions} 同款）；</li>
 *         <li><b>配置键名逐字未动</b>：仍是 {@code task.reward.stream-key} /
 *             {@code task.reward.group-name} ⇒ <b>不是</b> 迁到 {@code task-api.*} 之类的新前缀
 *             （配置键名属 L1 契约 · AGENTS.md §3.1）。</li>
 *         <li><b>类名</b>：本仓文件树原定的是 {@code TaskApiProperties}
 *             （前缀 {@code task-api}）⇒ <b>改名为本类</b>，理由见下方
 *             「为什么不是 {@code TaskApiProperties}」。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 两个键的<b>键名</b>与<b>默认值</b>逐字照抄
 *       （{@code stream:task:reward} / {@code task-reward-group}）；</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskRewardPublisher.java:44-45（@Value 取值 + 构造器）
 * B 出处：service/task/task-rpc/src/main/resources/application.yml:300-306（task.reward 段）
 *       </pre></li>
 * </ol>
 *
 * <h2>🔴 为什么不是 {@code TaskApiProperties}（本仓文件树原定的落点）</h2>
 * <p>B 侧 {@code task-api} 的 yaml 里<b>没有任何「域特有且有消费点」的 HTTP 层配置块</b>：
 * <table border="1">
 *   <caption>B 侧 task-api/application.yml 的逐段归属</caption>
 *   <tr><th>段</th><th>C 侧归属</th></tr>
 *   <tr><td>{@code spring.application.name} / {@code spring.servlet.multipart.enabled} /
 *           {@code server.*}</td><td>Spring Boot 标准键</td></tr>
 *   <tr><td>{@code spring.cloud.nacos.discovery.*}</td><td>Nacos starter</td></tr>
 *   <tr><td>{@code observability.http-timeout}</td>
 *       <td>共享层 {@code ObservabilityProperties}</td></tr>
 *   <tr><td>{@code rag.protection.*} / {@code rag.http.*}</td>
 *       <td>共享层 {@code ProtectionProperties}</td></tr>
 *   <tr><td>{@code grpc.client.task-rpc.*}</td>
 *       <td><b>死亡</b> —— api+rpc 合并后本域<b>没有跨域 gRPC 下游</b>（D1/D2）</td></tr>
 *   <tr><td>{@code Auth{AccessSecret, AccessExpire}}</td>
 *       <td><b>不迁</b> —— B 侧<b>零消费点</b>（死配置）</td></tr>
 * </table>
 * ⇒ 合并后<b>没有</b>任何东西可以绑到 {@code task-api.*} ⇒ 建一个
 * {@code @ConfigurationProperties(prefix = "task-api")} 的类 = <b>有类无消费点</b>
 * ⇒ 正是 AGENTS.md §八「职责有据」的反面，也是本仓反复出现的「哑弹」形态。
 * <p>⇒ 而 {@code task.reward.*} 是本域<b>唯一一个</b>「B 侧写了、C 侧有真实消费点、
 * 但 B 侧没有 Properties 类」的配置块 ⇒ 由本类承接。
 * <p>⚠️ 本条是 <b>L4 表达层</b>的结构调整（类名 + 绑定方式），
 * <b>配置键名与默认值一字未改</b> ⇒ 不构成 L1 漂移。
 */
@ConfigurationProperties(prefix = "task.reward")
public class TaskRewardProperties {

    /**
     * 奖励事件 Redis Stream —— Go {@code internal/reward/type.go:9} 的
     * {@code const StreamKey = "stream:task:reward"}。
     *
     * <p>⚠️ <b>本模块只写不读</b>：消费方 {@code reward.Worker} 在 Go 侧<b>没有被启动</b>
     * （{@code task.go:35} 的调用被注释、{@code initReward.go} 整个文件是注释），
     * 因为它依赖的 {@code PointsClient} 在 {@code service_context.go:18-19} 就被注释掉了。
     * ⇒ 线上 {@code stream:task:reward} 里只有堆积的 XADD 记录，<b>没有任何消费者</b>。
     * C 侧刻意<b>不</b>补这个消费者：那是新增行为（会引入对 points 域的依赖），
     * 而 points 域<b>不在本项目的范围内</b>（AGENTS.md §1）。
     */
    private String streamKey = "stream:task:reward";

    /**
     * 奖励事件的消费组名 —— Go {@code internal/reward/type.go} 的
     * {@code GroupName = "task-reward-group"}。
     *
     * <p>⚠️ <b>只存在于配置面</b>：Go 侧它只被 Worker 使用，而 Worker 从未启动
     * ⇒ 线上<b>不存在</b>这个消费组 ⇒ 本模块<b>不创建</b>它。
     * <p>保留这个字段是为了<b>配置面完整</b>（B 侧 yaml 里就有这一行），
     * 与 B 侧「有配置、无消费点」的状态<b>逐条一致</b>。
     */
    private String groupName = "task-reward-group";

    public String getStreamKey() {
        return streamKey;
    }

    public void setStreamKey(String streamKey) {
        this.streamKey = streamKey;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
}
