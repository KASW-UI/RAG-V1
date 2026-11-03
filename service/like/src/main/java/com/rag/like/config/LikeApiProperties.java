package com.rag.like.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * like-api 模块配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
 *
 * <p>对应 Go: {@code service/like/api/etc/likecenter.yaml} 的 {@code UserAuth} 段
 * （Go 侧由 {@code conf.MustLoad} 强转进 {@code internal/config/config.go} 的
 * {@code Config} struct：{@code rest.RestConf} + {@code UserAuth} + {@code LikeRpc}）。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code like-api/src/main/java/com/rag/like/like/api/config/LikeApiProperties.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：包名由 {@code com.rag.like.like.api.config} 压平为
 *       {@code com.rag.like.config}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。默认值、fail-closed 校验的判据逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <p><b>L1 外部契约冻结（默认值即 Go yaml 的字面量）</b>：
 * <table border="1">
 *   <caption>默认值对照</caption>
 *   <tr><th>字段</th><th>Go 值</th></tr>
 *   <tr><td>{@code userAuth.accessSecret}</td><td><b>36 字节 UUID</b>（Go 侧 yaml 的字面量
 *       —— ⚠️ <b>值不贴在本仓</b>：它就是那把「公开的验签密钥」⇒ 见
 *       {@code service/like/api/etc/likecenter.yaml:6}）
 *       —— 🔴 <b>2026-10-29 起 RAG 侧不再自带这个默认值</b>（P0 #8）⇒ 改由环境变量
 *       {@code USER_JWT_SECRET} 提供 ⇒ 缺变量 / 空串 / 未解析占位符 ⇒ <b>启动失败</b>
 *       ⇒ 见 {@link #verifyAccessSecret()} ⇒ <b>「RAG 更严」的有意偏离</b></td></tr>
 *   <tr><td>{@code userAuth.accessExpire}</td><td>{@code 31536000}（365 天）</td></tr>
 * </table>
 *
 * <p><b>本模块只验签、不签发</b>：Go 侧 like api 没有 login / register 端点，
 * {@code accessSecret} 的唯一用途是 {@code rest.WithJwt} 的验签密钥。
 * 签发方是 user-api —— <b>两边密钥必须一致</b>，否则验签必失败，
 * 表现为 <b>HTTP 401 + 空 body</b>（不是业务码）。排查时先看这里。
 *
 * <p>{@code accessExpire} 在本模块<b>不被读取</b>：Go 侧同样如此 ——
 * {@code api/internal/config/config.go} 的 {@code AccessExpire} 没有任何 logic 读它
 * （签发在 user-api）。保留字段是为了让 yaml 与 Go 端 1:1 可对照。
 *
 * <p><b>为什么 {@code LikeRpc} 段<b>只有</b> timeout、<b>没有</b>寻址字段</b>：
 * Go 侧 {@code config.Config} 有 {@code LikeRpc zrpc.RpcClientConf}，它有<b>两类</b>字段：
 * <ul>
 *   <li><b>寻址类</b>（{@code Etcd.Hosts} / {@code Key} / {@code Endpoints} / {@code Target}）
 *       —— Java 侧由 {@code @GrpcClient("like-rpc")} + Nacos discovery 接管，
 *       写在与 Go {@code Etcd.Key: like.rpc} 对应的
 *       {@code grpc.client.like-rpc.address=discovery:///like-rpc}。
 *       <b>再放一份 yaml 字段就是没有消费者的死配置</b>（Go 侧那份有消费者，两边情况不同）。</li>
 *   <li><b>语义旋钮类</b>（{@code Timeout}）—— ⚠️ <b>这一类必须有 Java 等价物</b>：
 *       Go 的 {@code Timeout int64 json:",default=2000"} 是<b>单次 RPC 调用的独立上界</b>
 *       （{@code zrpc/client.go:70-71} → {@code clientinterceptors/timeoutinterceptor.go:25}
 *       的 {@code context.WithTimeout(ctx, 2s)}），它与 HTTP 总预算（3s）是<b>两个独立旋钮</b>。
 *       没有它 ⇒ Java 侧会把 Go 判超时（2s~3s 窗口）的调用<b>放过去</b>（DRIFT-LIKEAPI-017）。
 *       ⇒ 见 {@link LikeRpc#getTimeout()}。</li>
 * </ul>
 *
 * <p>⚠️ <b>默认值取自 Go 的 struct tag，不是从 yaml 抄的</b>：
 * Go 的 {@code likecenter.yaml} <b>没有</b> {@code LikeRpc.Timeout} 这一行，
 * 2000ms 来自 {@code zrpc/config.go:31} 的 {@code json:",default=2000"} ——
 * 写不写都是 2000（AGENTS.md §3.3.1 同型：<b>「yaml 里没这个字段」不等于「值是 0」</b>）。
 * ⇒ Java 侧同样<b>不写进 application.yml</b>，由字段默认值承担，与 Go 端 1:1。
 */
@ConfigurationProperties(prefix = "like-api")
public class LikeApiProperties {

    @NestedConfigurationProperty
    private UserAuth userAuth = new UserAuth();

    @NestedConfigurationProperty
    private LikeRpc likeRpc = new LikeRpc();

    /**
     * 🔴 <b>验签密钥的 fail-closed 校验</b>（2026-10-29 · R1·T-1.3 · P0 #8；
     * 2026-10-29 · 本轮补「未解析占位符」判据）。
     *
     * <h3>为什么「去掉 yaml 默认值」<b>只堵住了一半</b></h3>
     * <p>主配置 {@code application.yml} 写的是
     * {@code like-api.user-auth.access-secret: ${USER_JWT_SECRET}} ——<b>没有默认值</b>。
     * <p>🔴 <b>实测</b>（真 {@code SpringApplication} + 真 ConfigData 流程）：
     * <b>变量完全没设时，Spring 的 {@code @ConfigurationProperties} 绑定器并不报错</b>
     * ⇒ ⇒ 它把<b>占位符原文</b>（形如 {@code "${USER_JWT_SECRET}"}）<b>当成值绑上去</b>
     * ⇒ ⇒ 应用<b>照常启动</b> ⇒ ⇒ 而这个「密钥」是<b>仓里公开可知的一串字符</b>
     * ⇒ ⇒ 🔴 <b>比空密钥更危险</b>（HMAC 密钥公开 ⇒ 谁都能伪造 token）。
     * <p>🔴 <b>另一条路</b>：<b>变量存在但值是空串</b> ⇒ Spring 的
     * {@code PropertyPlaceholderHelper} 把<b>空串当合法值</b>（<b>不走默认值、也不报错</b>）
     * ⇒ ⇒ 属性绑成 {@code ""} ⇒ ⇒ 应用带着<b>空签名密钥</b>正常起来 ⇒ ⇒ 🔴 <b>token 可伪造</b>。
     * <p>⇒ ⇒ 所以「去掉 yaml 默认值」<b>只堵住了一半</b> ⇒ ⇒ <b>另一半必须由代码堵</b> ⇒ 即本方法。
     *
     * <h3>为什么空串 / 占位符 ⇒ 启动失败（而不是「启动成功但验签永远失败」）</h3>
     * <p>「用空密钥签发的 token」是<b>可被任何人算出来的</b>（HMAC 空密钥），
     * 而「用占位符原文当密钥」是<b>公开可知</b>的 ⇒ ⇒ 两者都属于<b>静默的安全降级</b>
     * ⇒ ⇒ 宁可起不来。
     *
     * <h3>判据</h3>
     * <p><b>null / 空串 / 全空白 / 未解析的占位符字面量</b> ⇒ 不可用 ⇒ 抛。
     *
     * <h3>Go 出处与语义等价论证</h3>
     * <p>Go 侧 {@code service/like/api/etc/likecenter.yaml:6} 写的是<b>字面量</b>
     * ⇒ ⇒ <b>Go 侧同样有「公开默认密钥」这个问题</b> ⇒ ⇒ 本条属 <b>「RAG 更严」</b>的一侧
     * ⇒ ⇒ 登记为有意偏离（不是「RAG 抄错了 Go」）。
     * <p>语义等价论证：<b>变量一旦被提供</b>，本方法<b>不改动任何值</b> ⇒ 验签密钥与 Go 侧
     * <b>逐字节一致</b> ⇒ ⇒ 验签行为零变化（Go 侧消费点 =
     * {@code service/like/api/internal/handler/routes.go} 的
     * {@code rest.WithJwt(serverCtx.Config.UserAuth.AccessSecret)}）。
     *
     * <h3>为什么用 {@code @PostConstruct} 而不是 {@code @Validated + @NotBlank}</h3>
     * <p>后者要引入 {@code spring-boot-starter-validation}（<b>新增依赖</b>）⇒ ⇒ 本模块
     * <b>零新增依赖</b>更稳；且这里的判据是「<b>不得为空、也不得是未解析的占位符</b>」
     * 而不是「长度/格式」⇒ 显式抛异常更直白。
     */
    @PostConstruct
    void verifyAccessSecret() {
        String secret = userAuth == null ? null : userAuth.getAccessSecret();
        requireSecret(secret, "like-api.user-auth.access-secret", "USER_JWT_SECRET");
    }

    /**
     * 判据本体：<b>null / 空串 / 全空白 / 未解析的占位符字面量</b> ⇒ 不可用 ⇒ 抛。
     *
     * @param secret       绑到的密钥值（可能是 {@code null}）
     * @param propertyPath 出问题的配置路径（写进报错 ⇒ 运维能直接定位）
     * @param envVar       对应的环境变量名（写进报错）
     */
    private static void requireSecret(String secret, String propertyPath, String envVar) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    propertyPath + " 不能为空："
                            + "请设置环境变量 " + envVar + "（必须与签发方 user-api 完全一致）"
                            + "—— 本模块只验签、不签发，空密钥会让任何人都能伪造 JWT");
        }
        if (UNRESOLVED_PLACEHOLDER.matcher(secret).matches()) {
            throw new IllegalStateException(
                    propertyPath + " 未配置（绑到了未解析的占位符字面量 " + secret + "）："
                            + "请设置环境变量 " + envVar + "（必须与签发方 user-api 完全一致）"
                            + "—— 本模块只验签、不签发，占位符原文是公开可知的「密钥」⇒ 任何人都能伪造 JWT");
        }
    }

    /**
     * 「整串<b>就是</b>一个未解析的 {@code ${...}} 占位符」⇒ 说明环境变量<b>根本没设</b>。
     *
     * <p>⚠️ <b>为什么必须专门识别它（2026-10-29 实测）</b>：Spring 的
     * {@code @ConfigurationProperties} 绑定器遇到<b>解析不出来</b>的占位符时，
     * <b>不会抛异常</b>，而是把<b>占位符原文</b>当成值绑上去
     * ⇒ ⇒ 若只判「空 / 空白」，这一条就<b>漏过去了</b>
     * ⇒ ⇒ 「yaml 不给默认值」<b>不等于</b>「缺变量会启动失败」
     * ⇒ ⇒ 必须在这里显式堵。
     */
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("^\\$\\{[^}]*\\}$");

    public UserAuth getUserAuth() {
        return userAuth;
    }

    public void setUserAuth(UserAuth userAuth) {
        this.userAuth = userAuth;
    }

    public LikeRpc getLikeRpc() {
        return likeRpc;
    }

    public void setLikeRpc(LikeRpc likeRpc) {
        this.likeRpc = likeRpc;
    }

    /**
     * Go {@code UserAuth} 段 —— {@code AccessSecret} + {@code AccessExpire}。
     */
    public static class UserAuth {

        /**
         * 验签密钥 —— 由环境变量 {@code USER_JWT_SECRET} 提供（<b>无默认值</b>，见
         * {@link LikeApiProperties#verifyAccessSecret()}）。
         */
        private String accessSecret = "";

        /** Go {@code AccessExpire int64 json:",default=31536000"}（365 天）。本域不读取。 */
        private long accessExpire = 31536000L;

        public String getAccessSecret() {
            return accessSecret;
        }

        public void setAccessSecret(String accessSecret) {
            this.accessSecret = accessSecret;
        }

        public long getAccessExpire() {
            return accessExpire;
        }

        public void setAccessExpire(long accessExpire) {
            this.accessExpire = accessExpire;
        }
    }

    /**
     * Go {@code LikeRpc zrpc.RpcClientConf} —— 本域只保留<b>语义旋钮</b>（超时）。
     *
     * <p>⚠️ <b>D2 的影响</b>：C 侧 api → domain 是<b>域内直连</b>（没有 gRPC 往返）
     * ⇒ 这个超时<b>不再有 gRPC 客户端的消费者</b> ⇒ 它在 C 侧退化为「配置面完整」的保留字段。
     * ⇒ <b>不得</b>因为「没有消费者」就删掉（那是配置面漂移），
     * 也<b>不得</b>因为「有字段」就假装它在生效 —— 台账登记为「D2 后的无消费者字段」。
     */
    public static class LikeRpc {

        /**
         * Go {@code Timeout int64 json:",default=2000"}（毫秒）。
         *
         * <p>C 侧用 {@link Duration} 让 yaml 必须写单位（{@code 2s}），
         * 消除「秒/毫秒」这类 AGENTS.md 点名的配置语义错误。
         */
        private Duration timeout = Duration.ofMillis(2000);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
