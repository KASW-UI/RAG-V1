package com.rag.article.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p><b>职责</b>：内容安全审核的<b>兜底实现</b> —— 让审核链路 <b>fail-loud 而不是 fail-open</b>。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/UnavailableContentSecurityClient.java}
 * （类声明 {@code :61} · 常量 {@code :69} · {@code sanitizeText} {@code :71-77} ·
 * {@code detectImageAd} {@code :79-84}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅改名 + 包名</b>（映射表 §1.2 已批准）——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.client} → {@code com.rag.article.client}（D7 包压平）；</li>
 *         <li><b>类名</b>：{@code UnavailableContentSecurityClient} →
 *             {@link UnavailableContentAuditClient} ⇒ <b>去 {@code Security} 的域名含义</b>；</li>
 *         <li><b>异常类型名</b>：{@code SecurityAuditException} → {@code ContentAuditException}
 *             （随端口一起改名）。</li>
 *       </ul>
 *       ⇒ <b>报错文案逐字未改</b>（{@code "content security client not initialized"}）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>仍是<b>每次调用都抛</b>（不是返回「不是广告」）；</li>
 *         <li>⇒ 审核消费者抛出的异常 ⇒ Kafka <b>不确认</b> ⇒ 消息重投
 *             ⇒ 文章停在 REVIEWING，<b>不会被误判为通过</b>。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 本类不是「临时占位」，是本仓的<b>最终设计</b></h2>
 * <p>B 侧原文写「在 {@code security-rpc} <b>尚未落地到本仓</b>时」⇒ 那个措辞暗示「等它迁进来就能撤掉」。
 * <b>但</b> AGENTS.md §1.0 写明 {@code security} 域<b>范围外 ⇒ 不是待办</b>
 * ⇒ ⇒ 本仓<b>永远不会有</b>真的 {@code security.rpc} 可调
 * ⇒ ⇒ 本类<b>不是占位</b>，而是<b>永久的</b>「审核链路 fail-loud」契约载体。
 *
 * <h2>为什么必须有这个类（而不是干脆不注册 Bean）</h2>
 * {@link ContentAuditPort} 是审核消费者的<b>唯一</b>内容安全出口。
 * 若没有兜底实现：
 * <ul>
 *   <li><b>方案 A：没有 Bean</b> ⇒ {@code ArticleReviewConsumer} 构造失败 ⇒
 *       整个 Spring 上下文起不来（{@code UnsatisfiedDependencyException}）。
 *       这让「article 域不依赖 security」变成「article 域起不来」——
 *       部署耦合被放大，且错误信息完全指不到真正的原因。</li>
 *   <li><b>方案 B（本类）</b> ⇒ 上下文正常启动，审核消费者在遇到待审文章时
 *       <b>明确抛出</b>「content security client not initialized」，
 *       Kafka <b>不确认</b>、消息重投。文章停在 REVIEWING，不会被误判为通过。</li>
 * </ul>
 *
 * <h2>🔴 这个类体现的是一条必须守住的语义：fail-loud，绝不 fail-open</h2>
 * Go 侧 {@code auditArticle} 的第一行就是：
 * <pre>
 * if l.svcCtx.SecurityRpc == nil { return fmt.Errorf("content security client not initialized") }
 * </pre>
 * ⇒ <b>Go 自己就要求 security 不可用时审核必须失败</b>，
 * 而不是「跳过审核直接放行」。本类逐字复刻这条：
 * <b>任何一次调用都抛出同样的错误</b>，让审核文章卡在 REVIEWING 而不是被放行。
 *
 * <p>⚠️ <b>严禁改成「返回 isAd=false 就算通过」</b>：
 * 那会让所有文章绕过广告检测直接进入发布流程，
 * 并且在 {@code article_status_total} 上完全看不出来（因为审核「成功」了）。
 * 这是本组件里后果最严重的可能改动。
 *
 * <h2>「接入真实内容安全服务」这件事<b>不在本项目范围内</b></h2>
 * <b>如果</b>（超出本项目范围）落地了一个实现 {@link ContentAuditPort} 的 Bean，
 * 本类<b>自动退场</b>（{@code @ConditionalOnMissingBean}），审核消费者的代码<b>一行都不用改</b>。
 * <br>⚠️ 但在那之前，<b>不得</b>把本类改成「返回 {@code isAd=false} 就算通过」。
 */
public class UnavailableContentAuditClient implements ContentAuditPort {

    private static final Logger log = LoggerFactory.getLogger(UnavailableContentAuditClient.class);

    /**
     * 对应 Go {@code article_consumer.go:154-156} 的
     * {@code fmt.Errorf("content security client not initialized")} —— <b>逐字一致</b>。
     */
    private static final String NOT_INITIALIZED = "content security client not initialized";

    /**
     * 文本审核 —— 恒抛（fail-loud）。
     *
     * <p><b>B 出处</b>：{@code .../client/UnavailableContentSecurityClient.java:71-77}。
     *
     * @param text 待审正文
     * @return 永不返回
     * @throws ContentAuditException 恒抛
     */
    @Override
    public TextAuditResult sanitizeText(String text) {
        log.error("{} — article review cannot proceed; article stays REVIEWING and the kafka "
                + "message will be redelivered", NOT_INITIALIZED);
        // 抛出而非返回：见类级说明的 fail-loud 论证
        throw new ContentAuditException(NOT_INITIALIZED);
    }

    /**
     * 图片广告检测 —— 恒抛（fail-loud）。
     *
     * <p><b>B 出处</b>：{@code .../client/UnavailableContentSecurityClient.java:79-84}。
     *
     * @param imageDataUri 图片 data URI
     * @return 永不返回
     * @throws ContentAuditException 恒抛
     */
    @Override
    public ImageAuditResult detectImageAd(String imageDataUri) {
        log.error("{} — article review cannot proceed; article stays REVIEWING and the kafka "
                + "message will be redelivered", NOT_INITIALIZED);
        throw new ContentAuditException(NOT_INITIALIZED);
    }
}
