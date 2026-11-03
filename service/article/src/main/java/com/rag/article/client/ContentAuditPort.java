package com.rag.article.client;

/**
 * <p><b>职责</b>：内容安全审核<b>端口</b>（port）—— 文本审核 + 图片广告检测两条腿。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/ContentSecurityPort.java}
 * （接口声明 {@code :67} · {@code sanitizeText} {@code :77} · {@code detectImageAd} {@code :86} ·
 * {@code TextAuditResult} {@code :89-146} · {@code ImageAuditResult} {@code :149-179} ·
 * {@code SecurityAuditException} {@code :189-200}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅改名</b>（映射表 §1.2 已批准）——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.client} → {@code com.rag.article.client}（D7 包压平）；</li>
 *         <li><b>类型名</b>：{@code ContentSecurityPort} → {@link ContentAuditPort}、
 *             {@code SecurityAuditException} → {@link ContentAuditException}
 *             ⇒ <b>去 {@code Security} 的域名含义</b>（本仓<b>不迁移</b> {@code security} 域 ⇒
 *             这个名字在本仓<b>永远没有对端</b> ⇒ 继续叫 {@code Security} 会误导读者以为有对端）；</li>
 *         <li><b>方法名与结果类型名一字未改</b>（{@code sanitizeText} / {@code detectImageAd} /
 *             {@code TextAuditResult} / {@code ImageAuditResult}）⇒ 审核链路的语义载体不变。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>两条失败路径仍<b>必须抛出</b>（触发 Kafka 重投），不可吞；</li>
 *         <li>{@code success == false} 仍与「RPC 错误」<b>同等对待</b>（都抛）；</li>
 *         <li>{@code SecurityAuditException} 仍<b>是 unchecked</b>（要穿过 listener 的方法签名向上传播，
 *             漏写 catch 就等于把「重投」变成「丢弃」）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 为什么是接口而不是 {@code @GrpcClient} stub（诚实说明，不是偷懒）</h2>
 * Go 侧的 {@code security.rpc} 是<b>另一个微服务</b>的 gRPC 面，它的消息类型由
 * {@code proto/security.proto} 生成。
 * <p>⚠️ <b>本仓（RAG-V1）不迁移 {@code security} 域</b>（AGENTS.md §1.0「范围外」）
 * ⇒ ⇒ 本仓<b>永远不会有</b>真的 {@code security.rpc} 可调
 * ⇒ ⇒ 本端口的<b>兜底实现必须 fail-loud（抛错），不是放行</b>
 * ⇒ ⇒ 见 {@link UnavailableContentAuditClient}。
 * <p>⇒ 因此本组件声明<b>窄端口</b>（两个方法，签名照 Go 的调用点逐字段对齐）。
 * 这不是「先造个接口以后再说」—— <b>这个接口的形状就是 wire 契约的最小充分投影</b>，
 * 且它是本组件<b>唯一</b>与内容安全交互的边界：
 * 把外部依赖收敛到一个端口后，审核流程的其余部分（幂等、状态机、事务）
 * 都可以脱离它单独验证。
 *
 * <h2>⚠️ 端口方法的语义必须与 Go 调用点逐条对齐</h2>
 * <table border="1">
 *   <caption>Go 调用点 → 端口方法</caption>
 *   <tr><th>Go</th><th>端口</th><th>不可改的点</th></tr>
 *   <tr>
 *     <td>{@code article_consumer.go:158-166}<br>SanitizeContent</td>
 *     <td>{@link #sanitizeText}</td>
 *     <td>四个开关<b>全部为 true</b>：ad / html / unicode / whitespace</td>
 *   </tr>
 *   <tr>
 *     <td>{@code article_consumer.go:167-170}<br>err != nil ⇒ return err</td>
 *     <td>抛 {@link ContentAuditException}</td>
 *     <td><b>RPC 失败必须抛出</b>（触发 Kafka 重投），不可吞</td>
 *   </tr>
 *   <tr>
 *     <td>{@code article_consumer.go:171-173}<br>!Success ⇒ error</td>
 *     <td>抛 {@link ContentAuditException}</td>
 *     <td>Success=false 也算失败，<b>与 RPC 错误同等对待</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code article_consumer.go:174-182}<br>IsAd ⇒ REJECTED</td>
 *     <td>{@link TextAuditResult#isAd()}</td>
 *     <td>文本判广告 ⇒ 拒稿并停（不继续查图）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code article_consumer.go:184-203}<br>逐图 DetectImageAd</td>
 *     <td>{@link #detectImageAd}</td>
 *     <td>阈值 <b>0.7</b>、启用文字提取；任一张是广告 ⇒ 拒稿</td>
 *   </tr>
 * </table>
 *
 * <p><b>⚠️ 这两条失败路径必须抛出而不是返回 false</b>：Go 侧
 * {@code auditArticle} 返回 error 会让 {@code Consume} 返回 error ⇒ Kafka <b>不确认</b> ⇒ 重投。
 * 若在 Java 侧改成「返回 false 表示审核不可用」，就会变成
 * 「审核不可用时放行」——那是一条<b>全新的、危险的</b>放行路径。
 */
public interface ContentAuditPort {

    /**
     * 对应 Go {@code SecurityRpc.SanitizeContent}。
     *
     * <p><b>B 出处</b>：{@code .../client/ContentSecurityPort.java:77}。
     *
     * @param text 待审正文（MinIO 里读到的 markdown 原文）
     * @return 审核结果；<b>RPC 失败或 {@code Success == false} 时抛异常</b>
     * @throws ContentAuditException RPC 失败、{@code Success == false}，或审核客户端不可用
     */
    TextAuditResult sanitizeText(String text);

    /**
     * 对应 Go {@code ImageSecurityRpc.DetectImageAd}。
     *
     * <p><b>B 出处</b>：{@code .../client/ContentSecurityPort.java:86}。
     *
     * @param imageDataUri {@code data:<mime>;base64,<...>} 形式的图片
     * @return 审核结果；<b>RPC 失败或 {@code Success == false} 时抛异常</b>
     * @throws ContentAuditException 同上
     */
    ImageAuditResult detectImageAd(String imageDataUri);

    /**
     * 对应 Go {@code security.SanitizeContentResponse} 中本组件实际读取的四个字段。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * final class TextAuditResult {
     *     private final boolean success;
     *     private final boolean ad;
     *     private final float adConfidence;
     *     private final String sanitizedText;
     *     private final String errorMessage;
     *
     *     public TextAuditResult(boolean success, boolean ad, float adConfidence,
     *                            String sanitizedText, String errorMessage) {
     *         this.success = success;
     *         this.ad = ad;
     *         this.adConfidence = adConfidence;
     *         this.sanitizedText = sanitizedText;
     *         this.errorMessage = errorMessage;
     *     }
     *
     *     public boolean isSuccess() { return success; }
     *     public boolean isAd() { return ad; }
     *     public float getAdConfidence() { return adConfidence; }
     *     public String getSanitizedText() { return sanitizedText; }
     *     public String getErrorMessage() { return errorMessage; }
     * }
     * }</pre>
     */
    final class TextAuditResult {

        private final boolean success;

        private final boolean ad;

        private final float adConfidence;

        private final String sanitizedText;

        private final String errorMessage;

        /**
         * @param success 对应 Go 的 {@code result.Success}（article_consumer.go:171-173）。
         *                <b>false 一定要抛错重投</b>，不是「返回一个正常结果」。
         * @param ad      对应 Go 的 {@code result.IsAd}。
         */
        public TextAuditResult(boolean success, boolean ad, float adConfidence,
                               String sanitizedText, String errorMessage) {
            this.success = success;
            this.ad = ad;
            this.adConfidence = adConfidence;
            this.sanitizedText = sanitizedText;
            this.errorMessage = errorMessage;
        }

        /**
         * Go 侧的 {@code if !result.Success { return fmt.Errorf(...) }}（article_consumer.go:171-173）。
         *
         * <p>⚠️ 这个字段在 Go 侧<b>必须</b>被消费：否则「调用成功但业务失败」会被当成
         * 「不是广告」放行 ⇒ <b>fail-open</b>。审核消费者已强制消费它。
         *
         * @return 是否调用成功
         * @see ContentAuditException
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 对应 Go {@code result.IsAd} —— 唯一影响业务分支的字段。
         *
         * <p>⚠️ Go 只读 {@code IsAd}，<b>不</b>使用 {@code SanitizedText}
         * （审核不会改写正文）。本类保留 {@link #getSanitizedText()} 仅供排障。
         *
         * @return 是否判定为广告
         */
        public boolean isAd() {
            return ad;
        }

        /**
         * 广告置信度。
         *
         * @return 置信度
         */
        public float getAdConfidence() {
            return adConfidence;
        }

        /**
         * 审核后的文本（B 侧只读不写，仅供排障）。
         *
         * @return 审核后的文本
         */
        public String getSanitizedText() {
            return sanitizedText;
        }

        /**
         * 错误信息。
         *
         * @return 错误信息
         */
        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * 对应 Go {@code imagesecurity.DetectImageAdResponse} 中本组件实际读取的三个字段。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * final class ImageAuditResult {
     *     private final boolean success;
     *     private final boolean ad;
     *     private final float adConfidence;
     *
     *     public ImageAuditResult(boolean success, boolean ad, float adConfidence) {
     *         this.success = success;
     *         this.ad = ad;
     *         this.adConfidence = adConfidence;
     *     }
     *
     *     public boolean isSuccess() { return success; }
     *     public boolean isAd() { return ad; }
     *     public float getAdConfidence() { return adConfidence; }
     * }
     * }</pre>
     */
    final class ImageAuditResult {

        private final boolean success;

        private final boolean ad;

        private final float adConfidence;

        /**
         * @param success 对应 Go 的 {@code resp.GetSuccess()}（article_consumer.go:263-265）。
         *                <b>false 一定要抛错重投</b>（整篇重投，不是跳过这张图）。
         * @param ad      对应 Go 的 {@code resp.IsAd}。
         */
        public ImageAuditResult(boolean success, boolean ad, float adConfidence) {
            this.success = success;
            this.ad = ad;
            this.adConfidence = adConfidence;
        }

        /**
         * 见 {@link TextAuditResult#isSuccess()}。
         *
         * @return 是否调用成功
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 对应 Go {@code resp.IsAd}。
         *
         * @return 是否判定为广告
         */
        public boolean isAd() {
            return ad;
        }

        /**
         * 对应 Go {@code resp.AdConfidence}。⚠️ Go 读取它但<b>不用于判定</b>（阈值在服务端）。
         *
         * @return 置信度
         */
        public float getAdConfidence() {
            return adConfidence;
        }
    }

    /**
     * 审核不可用 / 审核判定为失败。
     *
     * <p><b>必须是 unchecked</b>：它要穿过 {@code Consume} 的方法签名向上传播
     * 到 Kafka listener，由 listener 抛出以触发重投。
     * 若声明成 checked exception，会强迫调用方写 try/catch，
     * 而<b>漏写 catch 就等于把「重投」变成「丢弃」</b>——那是一条静默丢消息路径。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * class ContentAuditException extends RuntimeException {
     *     private static final long serialVersionUID = 1L;
     *
     *     public ContentAuditException(String msg) { super(msg); }
     *     public ContentAuditException(String msg, Throwable cause) { super(msg, cause); }
     * }
     * }</pre>
     */
    class ContentAuditException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param msg 错误信息
         */
        public ContentAuditException(String msg) {
            super(msg);
        }

        /**
         * @param msg   错误信息
         * @param cause 原因
         */
        public ContentAuditException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
