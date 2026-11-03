package com.rag.article;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/**
 * <p><b>职责</b>：article 域的<b>唯一启动入口</b> —— 一个进程内同时起 Tomcat（HTTP 面）
 * 与 gRPC server（业务面）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/ArticleRpcApplication.java}
 * （类声明 {@code :73-95} · {@code PROCESSOR_CONCURRENCY} {@code :109} ·
 * {@code rpcInterceptorConfigurer} {@code :123-132} · {@code articleNoCommitErrorHandler} {@code :228-243} ·
 * {@code articleKafkaListenerContainerFactory} {@code :257-274} · {@code main} {@code :276-278}）
 * ＋ {@code service/article/article-api/src/main/java/com/rag/article/article/api/ArticleApiApplication.java}
 * （类声明 {@code :81-87} · {@code main} {@code :89-91}）⇒ <b>两段合并，逐条抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>D1 合并</b> —— B 侧是<b>两个</b>进程、<b>两个</b>入口类
 *       ⇒ 本仓合并为<b>一个</b>。具体合并口径：
 *     <ul>
 *       <li><b>包扫描</b>：B 侧 api 扫 {@code com.rag.article.article.api} + {@code com.rag.common}；
 *           本仓<b>只扫 {@code com.rag.article}</b>（{@code @SpringBootApplication} 的默认包 = 本类所在包）
 *           ⇒ ⇒ <b>刻意不扫 {@code com.rag.common}</b>：共享层在本仓是 <b>starter</b>（D3），
 *           由 {@code AutoConfiguration.imports} 自动装配 ⇒ 再扫一遍会<b>双注册</b>。</li>
 *       <li><b>{@code @Import} 清单</b>：B 侧 article-rpc 显式 {@code @Import} 了 8 个共享层类
 *           （{@code SnowflakeBean} / {@code SnowflakeProperties} / {@code LoggerFactoryBean} /
 *           {@code RpcServerInterceptorFactory} / {@code ObservabilityProperties} /
 *           {@code ObservabilityLifecycle} / {@code DeadlineQueryTimeoutInjector} /
 *           {@code SqlTimeoutStatusTranslator} / {@code GoStyleProtectionConfiguration}）
 *           ⇒ 本仓<b>全部由共享层的 auto-config 提供</b>
 *           ⇒ ⇒ <b>本类的 {@code @Import} 清单为空</b>；域内那一条
 *           （{@link ContentAuditAutoConfiguration}）改由<b>域内 {@code .imports} 文件</b>注册
 *           （见下）。</li>
 *       <li><b>gRPC 拦截器链</b>：B 侧 article-rpc 自己写了 {@code rpcInterceptorConfigurer}
 *           （把 {@code ServerInterceptor} 挂到 gRPC server 上）⇒ 本仓由共享层的
 *           {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 承担
 *           （devh starter 只认 {@code GrpcServerConfigurer}，域内再来一份会挂 2 条链）
 *           ⇒ ⇒ <b>本类不写这条 bean</b>。</li>
 *       <li><b>Kafka 错误契约</b>：B 侧的两个 Kafka bean
 *           （{@code articleNoCommitErrorHandler} / {@code articleKafkaListenerContainerFactory}）
 *           是<b>域内契约</b>（不是共享层能力）⇒ <b>本类保留</b>，方法体逐条照抄。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *     <ul>
 *       <li>{@code @EnableDiscoveryClient}（Nacos 自动发现）保留；</li>
 *       <li>{@code @ConfigurationPropertiesScan("com.rag.article.config")} ⇒ 绑定
 *           {@link com.rag.article.config.ArticleRpcProperties}（{@code article-rpc.*}）与
 *           {@link com.rag.article.config.ArticleApiProperties}（{@code article-api.*}）
 *           —— <b>两段前缀都保留</b>（B 侧就是两份 yml 各有一份）⇒ ⇒ <b>配置键逐字不变</b>；
 *           ⚠️ <b>这一行是硬要求</b>：两个配置类<b>刻意都不带 {@code @Component}</b>
 *           （若同时带 {@code @Component} 又被本扫描扫到 ⇒ 同一个类型注册<b>两个</b> bean
 *           ⇒ 构造器注入时 {@code NoUniqueBeanDefinitionException}）⇒ ⇒ 缺了本行，
 *           {@code ArticleApiProperties} 的 <b>fail-closed 守卫</b>
 *           （缺变量 / 空串 / 占位符 ⇒ 启动失败）与 {@code ArticleRpcProperties} 的
 *           {@code minio.*} / {@code kq-*.topic} 等绑定<b>全部失效</b>；</li>
 *       <li>{@code @MapperScan("com.rag.article.mapper")} ⇒ 注册 6 个 Mapper
 *           （B 侧 article-rpc 同款；缺它它们不会成为 bean，
 *           报错是 {@code No qualifying bean of type '…mapper.ArticleMapper'}）；</li>
 *       <li>{@code @EnableScheduling} ⇒ outbox relay 的 1 秒 ticker 靠它，
 *           缺它 relay <b>静默不跑</b>，事件永远积压。</li>
 *     </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>⚠️ 域内 auto-config 的注册方式</b>：{@link ContentAuditAutoConfiguration} 是<b>域内</b>的
 * auto-config（B 侧同样靠模块内 {@code .imports} 文件注册）⇒ 本仓用<b>域内 {@code .imports} 文件</b>
 * （{@code resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}）
 * ⇒ ⇒ <b>本类的 {@code @Import} 清单为空</b>（A5 守门判据）。
 * 判据时序仍然是确定的（见 {@link ContentAuditAutoConfiguration} 的类注释）。
 *
 * <p><b>⚠️ 本模块同时起 Tomcat 与 gRPC</b>（D1）：HTTP 面 = {@code server.port}，
 * gRPC 面 = {@code grpc.server.port} ⇒ ⇒ Nacos 自动注册会取到 <b>web 端口</b> ⇒
 * ⇒ <b>必须</b>关掉自动注册（{@code spring.cloud.nacos.discovery.register-enabled: false}），
 * 由 {@link com.rag.article.wiring.NacosRegistration} 用 <b>gRPC 端口</b>显式注册。
 *
 * <p><b>⚠️ gRPC server 由 devh starter 自动装配</b>：{@code grpc/ArticleGrpcService} 上的
 * {@code @GrpcService} 由 starter 扫描注册 ⇒ <b>本类不手写任何 gRPC 注册代码</b>。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.rag.article.config")
@MapperScan("com.rag.article.mapper")
@EnableScheduling
@EnableDiscoveryClient
public class ArticleServiceApplication {

    /**
     * Kafka handler 的并发度 —— 对应 Go {@code kq.KqConf.Processors} 的默认值 8。
     *
     * <p>Go 出处：<b>声明点</b> {@code kq/config.go:19}
     * {@code Processors int `json:",default=8"}`；<b>消费点</b>
     * {@code kq/queue.go:220} {@code for i := 0; i < q.c.Processors; i++}。
     * article-rpc 的 yaml 只配了 {@code Consumers: 1}，未配 {@code Processors} ⇒ 取默认 8。
     *
     * <p>⚠️ <b>不要与 {@code Consumers} 混淆</b>：{@code Consumers} 控制 reader 协程，
     * {@code Processors} 才控制调用 handler 的并发度。
     *
     * <p><b>B 出处</b>：{@code .../rpc/ArticleRpcApplication.java:109}。
     */
    static final int PROCESSOR_CONCURRENCY = 8;

    /**
     * article 域的 Kafka {@link CommonErrorHandler} —— <b>零次进程内重试、失败即提交位点</b>。
     *
     * <h3>Go 侧真源（go-queue v1.2.2）：判定在 tag 的默认值上，不在 continue 那行</h3>
     * <p>失败位点的处置由 <b>{@code ForceCommit} 这个 struct tag 的默认值</b>决定：
     * <pre>{@code
     * // kq/config.go:24    ← 声明点，默认值在这里
     * ForceCommit   bool   `json:",default=true"`
     *
     * // kq/queue.go:230-242   ← 消费点，真正的判定在这里
     * if err := q.consumeOne(ctx, string(msg.Key), string(msg.Value)); err != nil {
     *     if q.errorHandler != nil { q.errorHandler(ctx, msg, err) }  // article-rpc 未传 ⇒ nil
     *     if !q.c.ForceCommit { continue }                             // ForceCommit=true ⇒ 不 continue
     * }
     * if err := q.consumer.CommitMessages(ctx, msg); err != nil { ... } // ← 失败也照样提交
     * }</pre>
     * article-rpc 的 yaml <b>没有</b>配置 {@code ForceCommit} ⇒ 生效值 <b>true</b>
     * ⇒ <b>Go 在 handler 返回 error 时依然提交该消息的位点</b>（消息被丢弃），
     * 且进程内<b>无重试循环</b>。
     *
     * <h3>🔴 曾经的错误：本方法原先设 {@code seekAfterError=false}，依据是被证伪的</h3>
     * <p>原注释写「Go 的 {@code continue} 说明失败位点不提交」。但那行 {@code continue} 前面有
     * <b>{@code if !q.c.ForceCommit} 的前置判定</b>，而 {@code ForceCommit} 取默认 {@code true}
     * ⇒ <b>该 {@code continue} 永远不执行</b>。
     * <br>（AGENTS.md §3.3.1：判断 Go 默认行为是否生效，必须读到<b>消费该 tag 的那段控制流</b>，
     * 只贴 struct 定义行不算证据。）
     * <br>后果：失败消息在 Java 侧不提交位点 ⇒ rebalance / 重启后<b>无限重投</b>，
     * 与 Go 相反的 poison-pill 语义。
     *
     * <h3>🔴 真实机制（真 Kafka 实测 ＋ spring-kafka 源码逐行核对）</h3>
     * <p>失败消息被丢弃（不重投）由<b>两件独立的事</b>合成，<b>缺一不可</b>：
     * <ol>
     *   <li><b>「不 seek（⇒ 不重投）」＝ {@code FixedBackOff(0L, 0L)}</b><br>
     *       {@code FailedRecordTracker} 构造时 {@code backOff.start().nextBackOff() == STOP}
     *       ⇒ {@code noRetries = true} ⇒ 失败时 {@code recovered(...)} <b>第一次就返回 true</b>
     *       ⇒ {@code SeekUtils.doSeeks(...)} 把<b>首条（失败记录）判为 skipped ⇒ 不 seek</b>；</li>
     *   <li><b>「提交失败记录自己的 offset+1」＝ 容器侧的 RECORD ack</b><br>
     *       {@code KafkaMessageListenerContainer#commitOffsetsIfNeededAfterHandlingError(cRecord)}
     *       ⇒ {@code ackCurrent(cRecord, isManualAck)} ⇒ RECORD ack 分支提交
     *       <b>这条失败记录的 offset+1</b>。</li>
     * </ol>
     *
     * <p>⇒ 与 Go <b>同构</b>：Go {@code kq/queue.go:230-242} 在 handler 返回 error 后，
     * 因 {@code ForceCommit=true} <b>不执行 continue</b> ⇒ 落到 {@code CommitMessages}
     * ⇒ 提交的正是<b>失败消息自己的位点</b> ⇒ 消息被丢弃；且 Go 侧<b>没有重试循环</b>
     * ⇒ 不会进程内重投。两侧逐条一致。
     *
     * <p>⚠️ {@code setSeekAfterError(true)} <b>保留</b>（它与 Spring 默认值一致、且显式写出来更可 review），
     * 但<b>不得再把它说成对齐 Go 的原因</b> —— 它在本模块<b>不是承重项</b>。
     *
     * <p>⚠️ recoverer 只记日志、不做任何恢复动作 —— 与 Go 的 {@code errorHandler == nil}
     * （未传 {@code WithHandleError}）不冲突：Go 那条分支本来就只做 {@code CommitMessages}。
     *
     * <p><b>做成具名 Bean 而不是工厂方法内部的局部变量</b>：<b>可被测试读到</b>，
     * 且<b>单一真相源</b>——两个 listener 共用同一契约。
     *
     * <p><b>B 出处</b>：{@code .../rpc/ArticleRpcApplication.java:228-243}。
     *
     * @return 零重试的错误处理器
     */
    @Bean
    public CommonErrorHandler articleNoCommitErrorHandler() {
        Logger logger = LoggerFactory.getLogger(ArticleServiceApplication.class);
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> logger.error(
                        "article kafka message failed, offset committed as in Go kq (ForceCommit=true): "
                                + "the message is dropped, not redelivered: "
                                + "topic={} partition={} offset={} err={}",
                        record.topic(), record.partition(), record.offset(), exception.getMessage()),
                // 0 次进程内重试：Go 的 kq 返回 error 后立即取下一条，没有重试循环
                new FixedBackOff(0L, 0L));
        // 🔴 与 Go 的 ForceCommit 默认值 true 对齐：失败记录同样提交位点（消息被丢弃）。
        //    写成 false 会让失败消息在 rebalance/重启后无限重投 —— 与 Go 相反。
        handler.setSeekAfterError(true);
        return handler;
    }

    /**
     * article 域的 Kafka listener 容器工厂 —— 用上面那个错误处理器。
     *
     * <p><b>⚠️ 为什么必须自定义</b>：Spring Kafka 出厂默认在重试耗尽后会
     * <b>把失败记录的位点 commit 掉</b>（即丢消息），这与 Go 的
     * 「返回 error ⇒ 不确认、靠重启/rebalance 重投」不符。
     *
     * <p><b>⚠️ 为什么用 RECORD 而非 BATCH</b>：RECORD 是「listener 正常返回后提交当前记录的位点」，
     * 与 Go kq 的逐条 {@code CommitMessages} 一致；BATCH 会把位点推到整个 poll 批次的末尾，
     * 一条失败可能连带丢掉同批次已成功的其它消息。
     *
     * <p><b>B 出处</b>：{@code .../rpc/ArticleRpcApplication.java:257-274}。
     *
     * @param consumerFactory            全局消费者工厂
     * @param articleNoCommitErrorHandler 本域的错误处理器
     * @return listener 容器工厂
     */
    @Bean
    @ConditionalOnMissingBean(name = "articleKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> articleKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            CommonErrorHandler articleNoCommitErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // ⚠️ 两个消费者共用同一错误契约：审核与同步结果的 drop/retry 规则不同，
        //    但「失败都不提交位点」这一条相同（见两个 Consumer 的类级注释）。
        factory.setCommonErrorHandler(articleNoCommitErrorHandler);
        // 🔴 Go 的 handler 并发度是 8（kq/config.go:19 Processors default=8，消费点 kq/queue.go:220）
        //    Spring 默认 concurrency=1 会退化成严格串行。
        factory.setConcurrency(PROCESSOR_CONCURRENCY);
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * 进程入口 —— 对应 B 侧两个 {@code main}（api 的与 rpc 的）合并后的唯一入口。
     *
     * <p><b>B 出处</b>：{@code .../rpc/ArticleRpcApplication.java:276-278}
     * ＋ {@code .../api/ArticleApiApplication.java:89-91}。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ArticleServiceApplication.class, args);
    }
}
