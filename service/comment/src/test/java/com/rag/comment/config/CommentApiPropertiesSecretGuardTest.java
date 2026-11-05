package com.rag.comment.config;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <p>🔴 comment 域 <b>JWT 验签密钥 fail-closed</b> 的守门网（生产同款启动 4 条 ＋ 判据边界 ＋ 真 yaml）。
 *
 * <h3>守的是什么</h3>
 * <p>主 yaml 的 {@code comment-api.user-auth.access-secret: ${USER_JWT_SECRET}}（<b>无默认值</b>，
 * C 侧 {@code service/comment/src/main/resources/application.yml:275}）
 * ⇒ ⇒ <b>缺变量 / 空串 / 全空白 / 占位符原文</b> 四条路都要 <b>启动失败</b>；变量正常 ⇒ 正常启动。
 *
 * <h3>⚠️ 守门背后那条<b>实测</b>出来的机制</h3>
 * <p>「把 yaml 的明文默认值去掉」<b>并不自动等于</b>「缺变量 ⇒ 启动失败」——
 * <b>实测</b>（本类的 {@link #prodLikeStartupWithoutEnvVarFails()} ＋
 * {@link #realYamlWithoutEnvVarFailsTheContext()}）发现：
 * <ul>
 *   <li><b>变量没设</b> ⇒ Spring 的绑定器把<b>占位符原文</b> {@code "${USER_JWT_SECRET}"}
 *       <b>当成值绑上去</b>（<b>不是</b>报错、<b>不是</b>空串）⇒ ⇒ 应用<b>照常启动</b>
 *       ⇒ ⇒ 而这个「密钥」是<b>仓里公开可知的一串字符</b> ⇒ ⇒ 🔴 <b>比空密钥更危险</b>
 *       （HMAC 密钥公开 ⇒ 谁都能伪造 token）；</li>
 *   <li><b>变量是空串</b> ⇒ Spring 把<b>空串当合法值</b>（<b>不走默认值、也不报错</b>）
 *       ⇒ ⇒ 属性绑成 {@code ""} ⇒ ⇒ 应用带着<b>空签名密钥</b>正常起来
 *       ⇒ ⇒ 🔴 <b>token 可伪造</b>（HMAC 空密钥谁都能算）。</li>
 * </ul>
 * ⇒ ⇒ 所以四条都得由 {@code CommentApiProperties#verifyAccessSecret}（<b>消费点</b>：
 * C 侧 {@code service/comment/src/main/java/com/rag/comment/config/CommentApiProperties.java:70-89}）显式堵
 * ⇒ ⇒ 本类钉住它在<b>真实装配</b>里真的挡住了。
 *
 * <h3>🔴 为什么「生产同款启动」这一组是本类最重要的一半</h3>
 * <p>{@link ApplicationContextRunner} <b>不走 ConfigData 流程</b>（它<b>默认不加载</b>
 * {@code application.yml}）⇒ ⇒ 「真 yaml 怎么被加载、placeholder 怎么被解析」都<b>不是生产那条路</b>。
 * ⇒ ⇒ 下面 4 条用 {@link SpringApplicationBuilder}（<b>真</b> {@code SpringApplication} ＋ <b>真</b> ConfigData
 * ＋ <b>只有</b> {@code application.yml} = 产物 jar 的真实构成）⇒ ⇒ 这是「缺变量 ⇒ 启动失败」这个命题的
 * <b>生产等价</b>证据（AGENTS.md §3.3.3：「纯 mock 单测不算」）。
 *
 * <h3>三段式（AGENTS.md §3.2）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>「全空白」这一条是新增的</b>：B 侧只守了「未设 / 空串 / 占位符原文 / 正常」四条，
 *             本类补上「变量 = 全空白（{@code "   "}）」⇒ 判据是 {@code secret.isBlank()}
 *             （C 侧 {@code CommentApiProperties.java:77}）⇒ 与「空串」同一条拒绝路径。</li>
 *         <li><b>根因断言改成「在 cause 链上找守卫抛的那一个」</b>：B 侧断言的是
 *             {@code rootCause(failure)}；C 侧实测（Boot 3.5.11）失败链是
 *             {@code BeanCreationException → IllegalStateException}
 *             ⇒ 根因<b>就是</b>守卫抛的那个 ⇒ 两条断言逐条同形，只是多写了一层可读的诊断信息。</li>
 *         <li><b>最小上下文只装配 {@link CommentApiProperties}</b>：真起整个 app 会连
 *             PG / Redis / Kafka / Nacos ⇒ ⇒ 用一个<b>不带 {@code @EnableAutoConfiguration}</b> 的
 *             {@code @Configuration} ⇒ ⇒ <b>零自动配置</b>（无 DataSource ⇒
 *             {@code ${POSTGRES_PASSWORD}} 那条无默认值的占位符<b>永不被解析</b>）
 *             ⇒ ⇒ 「变量未设 ⇒ 启动失败」这条<b>不会</b>被别的失败原因污染。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 4 条生产同款启动的期望值（失败 / 失败 / 失败 / 成功）
 *       与 B 侧逐条一致；判据边界（null / "" / "   " / 占位符原文 ⇒ 抛）与 B 侧逐条一致；
 *       真 yaml 必须真的绑到嵌套字段 {@code UserAuth#accessSecret} 这条也一致。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code service/comment/comment-api/src/test/java/com/rag/comment/comment/api/config/CommentApiPropertiesSecretGuardTest.java:57-341}
 *             （B 侧本类的逐条 oracle）</li>
 *         <li>{@code service/comment/comment-api/src/main/resources/application.yml:72-77}
 *             （B 侧 {@code access-secret: ${USER_JWT_SECRET}}，无默认值）</li>
 *         <li>同仓 article-api 的真实踩坑记录：B 侧 commit {@code 45e0af4} 把 {@code auth:} 整行删掉
 *             ⇒ ⇒ article-api 起不来，而「只喂键路径」的那几条用例<b>照样全绿</b>
 *             ⇒ ⇒ 「读真 yaml」这一条不是洁癖，是回归网。</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界（AGENTS.md §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧的守卫在真实装配里挡住了」。
 * 「B 侧此刻也如此」属于<b>双侧活体对拍</b>（本类<b>不</b>冒充该证据）。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（不需要容器）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
class CommentApiPropertiesSecretGuardTest {

    /** 正常密钥（形状 = 36 字节 UUID；⚠️ <b>不是</b>真源的生产密钥）。 */
    private static final String USER_SECRET = "ae0536f9-6450-4606-8e13-5a19ed505da0";

    /** 主 yaml 里那条占位符的<b>字面量</b>形态（= 变量没设时被绑上去的那个值）。 */
    private static final String UNRESOLVED_PLACEHOLDER = "${USER_JWT_SECRET}";

    // ====================================================================================
    // ① 生产同款启动（真 SpringApplication ＋ 真 ConfigData ＋ 只有 application.yml）
    // ====================================================================================

    /**
     * 🔴🔴🔴 <b>生产同款启动</b>：变量<b>没设</b> ⇒ <b>启动失败</b>。
     *
     * <p>⚠️ <b>本条是本类最重要的一条</b>：它是「缺变量 ⇒ 启动失败」这个命题的
     * <b>生产等价</b>证据（AGENTS.md §3.3.3）。
     *
     * <p>⚠️ <b>断言必须锁死「是哪一条失败」</b>：只看 {@code hasFailed()} 是不够的 ——
     * 万一是「{@code ${POSTGRES_PASSWORD}} 解析不到」或「某个自动配置缺 Bean」，
     * 上下文也会失败 ⇒ ⇒ 那样这条用例就<b>因为错误的原因而绿</b>（§3.3）。
     * ⇒ ⇒ 本条断言失败链上<b>必须</b>出现守卫抛的 {@link IllegalStateException}，
     * 且其消息必须点名 {@code comment-api.user-auth.access-secret} 与 {@code USER_JWT_SECRET}。
     */
    @Test
    @DisplayName("🔴🔴🔴 生产同款启动（真 SpringApplication + 真 ConfigData + 只有 application.yml）"
            + " + 变量未设 ⇒ 启动失败（根因 = 守卫抛的 IllegalStateException）")
    void prodLikeStartupWithoutEnvVarFails() {
        Throwable failure = startOrCaptureFailure();

        assertThat(failure).as("变量未设 ⇒ 必须启动失败").isNotNull();
        IllegalStateException guard = findGuardFailure(failure);
        assertThat(guard)
                .as("失败原因必须是密钥守卫（而不是别的，例如占位符解析失败 / 缺 Bean）"
                        + "；实际失败链：" + describeChain(failure))
                .isNotNull();
        assertThat(guard.getMessage())
                .as("守卫的消息必须点名配置路径与环境变量名")
                .contains("comment-api.user-auth.access-secret")
                .contains("USER_JWT_SECRET");
    }

    /**
     * 🔴🔴🔴 生产同款启动 + 变量设成<b>空串</b> ⇒ 启动失败。
     *
     * <p>⚠️ 空串在 Spring 里是<b>合法值</b>（{@code PropertyPlaceholderHelper} 不走默认值、也不报错）
     * ⇒ ⇒ yaml 那层<b>挡不住</b> ⇒ ⇒ 必须由 {@code verifyAccessSecret()} 挡。
     */
    @Test
    @DisplayName("🔴🔴🔴 生产同款启动 + 变量为空串 ⇒ 启动失败（空串是合法值 ⇒ 由守卫拦下）")
    void prodLikeStartupWithBlankEnvVarFails() {
        Throwable failure = startOrCaptureFailure("USER_JWT_SECRET=");

        assertThat(failure).as("变量为空串 ⇒ 必须启动失败").isNotNull();
        IllegalStateException guard = findGuardFailure(failure);
        assertThat(guard)
                .as("失败原因必须是密钥守卫；实际失败链：" + describeChain(failure))
                .isNotNull();
        assertThat(guard.getMessage())
                .contains("comment-api.user-auth.access-secret")
                .contains("USER_JWT_SECRET");
    }

    /**
     * 🔴🔴🔴 生产同款启动 + 变量设成<b>全空白</b> ⇒ 启动失败。
     *
     * <p>⚠️ <b>本条是 C 侧相对 B 侧新增的一条</b>（B 侧那个类只守了「未设 / 空串 / 占位符原文 / 正常」四条）：
     * 全空白与空串落在<b>同一条</b>拒绝路径上（C 侧 {@code CommentApiProperties.java:77}
     * 的 {@code secret == null || secret.isBlank()}）⇒ ⇒ 断言的<b>期望值</b>与 B 侧「空串 ⇒ 失败」同形。
     */
    @Test
    @DisplayName("🔴🔴🔴 生产同款启动 + 变量为全空白 ⇒ 启动失败（isBlank 判据 ⇒ 与空串同一条拒绝路径）")
    void prodLikeStartupWithWhitespaceEnvVarFails() {
        Throwable failure = startOrCaptureFailure("USER_JWT_SECRET=   ");

        assertThat(failure).as("变量为全空白 ⇒ 必须启动失败").isNotNull();
        IllegalStateException guard = findGuardFailure(failure);
        assertThat(guard)
                .as("失败原因必须是密钥守卫；实际失败链：" + describeChain(failure))
                .isNotNull();
        assertThat(guard.getMessage())
                .contains("comment-api.user-auth.access-secret")
                .contains("USER_JWT_SECRET");
    }

    /**
     * ✅ 生产同款启动 + 变量<b>正常</b> ⇒ <b>正常启动</b>（对照组：证明守卫没有误伤）。
     *
     * <p>⚠️ 这一条与上面三条是<b>同一段启动代码</b>、只差一个属性 ⇒ ⇒
     * 它是「守卫有牙齿」的<b>反向</b>证明：不误伤。
     */
    @Test
    @DisplayName("✅ 生产同款启动 + 变量正常 ⇒ 正常启动（对照组：证明守卫不误伤）")
    void prodLikeStartupWithRealEnvVarStarts() {
        try (ConfigurableApplicationContext ctx = prodLikeStart("USER_JWT_SECRET=" + USER_SECRET)) {
            assertThat(ctx.getBean(CommentApiProperties.class).getUserAuth().getAccessSecret())
                    .as("变量正常 ⇒ 密钥必须原样绑定（守卫不改动任何值 ⇒ 验签行为零变化）")
                    .isEqualTo(USER_SECRET);
        }
    }

    /**
     * 🔴🔴🔴 生产同款启动 + <b>绑到的值是占位符原文</b> ⇒ 启动失败。
     *
     * <p>本条模拟的是「绑定器把<b>未解析的占位符原文</b>绑成值」那个状态
     * ⇒ ⇒ 它是「变量没设」那条路的<b>等价物</b>（绑上去的值长得一模一样）
     * ⇒ ⇒ ⇒ 守卫必须把它也判成不可用。
     *
     * <p>⚠️ <b>为什么这里注入的是「属性路径」而不是「环境变量」</b>：
     * 若把环境变量本身设成 {@code ${USER_JWT_SECRET}}（<b>自引用</b>），
     * Spring 会先抛 {@code PlaceholderResolutionException: Circular placeholder reference}
     * ⇒ ⇒ 那是 <b>Spring 自己</b>挡的，<b>证明不了本守卫有牙齿</b>
     * ⇒ ⇒ 本条注入的是「属性路径 = 占位符原文、环境变量未设」这个
     * <b>与真实失败场景逐字节相同</b>的绑定结果。
     */
    @Test
    @DisplayName("🔴🔴🔴 生产同款启动 + 绑到的值是占位符原文 ⇒ 启动失败（公开可知的「密钥」⇒ 由守卫拦下）")
    void prodLikeStartupWithLiteralPlaceholderBoundValueFails() {
        Throwable failure = startOrCaptureFailure("comment-api.user-auth.access-secret=" + UNRESOLVED_PLACEHOLDER);

        assertThat(failure).as("绑到的值是占位符原文 ⇒ 必须启动失败").isNotNull();
        IllegalStateException guard = findGuardFailure(failure);
        assertThat(guard)
                .as("失败原因必须是密钥守卫；实际失败链：" + describeChain(failure))
                .isNotNull();
        assertThat(guard.getMessage())
                .contains("comment-api.user-auth.access-secret")
                .contains("USER_JWT_SECRET");
    }

    // ====================================================================================
    // ② 判据本身的边界（纯单元，不依赖容器）
    // ====================================================================================

    /**
     * 🔴 判据本身的边界（纯单元，不依赖容器）。
     *
     * <p>⚠️ <b>「未解析的占位符字面量」这一条是<b>实测</b>逼出来的</b>：
     * 变量没设时绑定器会把<b>占位符原文</b>绑上去 ⇒ ⇒ 只判「空 / 空白」会漏掉它。
     *
     * <p>⚠️ 本类与 {@code CommentApiProperties} <b>同包</b>（{@code com.rag.comment.config}）
     * ⇒ ⇒ 才能直接调它的<b>包级可见</b> {@code verifyAccessSecret()}。
     */
    @Test
    @DisplayName("🔴 判据边界：null / 空串 / 全空白 / 未解析占位符字面量 ⇒ 抛；正常值 ⇒ 不抛")
    void guardRejectsBlankAndAcceptsRealSecret() {
        CommentApiProperties props = new CommentApiProperties();

        props.getUserAuth().setAccessSecret(null);
        assertThrows(IllegalStateException.class, props::verifyAccessSecret);

        props.getUserAuth().setAccessSecret("");
        assertThrows(IllegalStateException.class, props::verifyAccessSecret);

        props.getUserAuth().setAccessSecret("   ");
        assertThrows(IllegalStateException.class, props::verifyAccessSecret);

        // ⚠️ 这一条是**实测**逼出来的：变量没设时绑定器会把**占位符原文**绑上去
        props.getUserAuth().setAccessSecret(UNRESOLVED_PLACEHOLDER);
        assertThrows(IllegalStateException.class, props::verifyAccessSecret);

        props.getUserAuth().setAccessSecret(USER_SECRET);
        assertDoesNotThrow(props::verifyAccessSecret);
    }

    // ====================================================================================
    // ③ 真 yaml（不靠 withPropertyValues 喂键路径）
    // ====================================================================================

    /**
     * 🔴🔴 <b>读<b>真 yaml</b>的回归网</b>。
     *
     * <p>如果只用 {@code withPropertyValues("comment-api.user-auth.access-secret=…")}
     * —— 也就是<b>直接喂键路径</b>，<b>从不读</b> {@code src/main/resources/application.yml}
     * ⇒ ⇒ <b>yaml 里的键路径写错，它们照样全绿</b>
     * ⇒ ⇒ 真缺陷已在同仓 article-api 上真踩到（commit {@code 45e0af4} 把 {@code auth:} 整行删掉
     * ⇒ ⇒ article-api 起不来，而那几条用例全绿）⇒ ⇒ 本用例把<b>真</b> yaml 塞进上下文
     * ⇒ ⇒ 再断言嵌套字段真的拿到了值。
     */
    @Test
    @DisplayName("🔴🔴 主 application.yml 的 access-secret 必须真的绑到 UserAuth 嵌套字段（读真 yaml，不靠 withPropertyValues）")
    void realYamlBindsSecretIntoNestedUserAuth() {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        Resource yaml = new ClassPathResource("application.yml");
                        new YamlPropertySourceLoader().load("main-yaml", yaml)
                                .forEach(ps -> context.getEnvironment().getPropertySources().addLast(ps));
                    } catch (IOException e) {
                        throw new IllegalStateException("读不到主 application.yml（src/main/resources）", e);
                    }
                })
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues("USER_JWT_SECRET=" + USER_SECRET)
                .run(context -> {
                    // ⚠️ 绑定落空时，`verifyAccessSecret()` 会在 @PostConstruct 抛 ⇒ 上下文起不来
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CommentApiProperties.class).getUserAuth().getAccessSecret())
                            .isEqualTo(USER_SECRET);
                });
    }

    /**
     * 🔴🔴 <b>真 yaml ＋ 变量没设 ⇒ 启动失败</b>。
     *
     * <p>⚠️ 本条<b>第一次跑时是红的</b>：上下文<b>照常起来了</b> ⇒ 因为 Spring 的绑定器
     * 把未解析的占位符<b>原文</b>绑成了值（{@code "${USER_JWT_SECRET}"}）
     * ⇒ ⇒ 所以「只去默认值」在「变量没设」这条路上<b>原本是不 fail-closed 的</b>
     * ⇒ ⇒ {@code verifyAccessSecret()} 增补了「未解析占位符字面量」这一判据 ⇒ ⇒ 本条现在才绿。
     *
     * <p>⚠️ 这一条<b>不能</b>用 {@code withPropertyValues} 代替 ⇒ 因为
     * {@code ApplicationContextRunner} <b>默认不加载</b> {@code application.yml}
     * （它不走 ConfigData 流程）⇒ ⇒ 必须把真 yaml 显式塞进属性源。
     */
    @Test
    @DisplayName("🔴🔴 真 yaml + 变量未设 ⇒ 启动失败（绑到未解析占位符 ⇒ 被 @PostConstruct 拦下）")
    void realYamlWithoutEnvVarFailsTheContext() {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        Resource yaml = new ClassPathResource("application.yml");
                        new YamlPropertySourceLoader().load("main-yaml", yaml)
                                .forEach(ps -> context.getEnvironment().getPropertySources().addLast(ps));
                    } catch (IOException e) {
                        throw new IllegalStateException("读不到主 application.yml（src/main/resources）", e);
                    }
                })
                .withUserConfiguration(PropsConfig.class)
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 🔴🔴 <b>真 yaml ＋ 变量设成空串 ⇒ 启动失败</b>。
     *
     * <p>Spring 的 {@code PropertyPlaceholderHelper} 对「属性存在但值为空串」解析成<b>空串</b>
     * （<b>不走默认值、也不报错</b>）⇒ ⇒ 只去掉 yaml 默认值<b>堵不住</b>这一条
     * ⇒ ⇒ 必须由 {@code @PostConstruct} 显式拦 ⇒ ⇒ 本条就是那条路的<b>真 yaml 证据</b>。
     */
    @Test
    @DisplayName("🔴🔴 真 yaml + 变量为空串 ⇒ 启动失败（空串当合法值 ⇒ 由 @PostConstruct 拦下）")
    void realYamlWithBlankEnvVarFailsTheContext() {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        Resource yaml = new ClassPathResource("application.yml");
                        new YamlPropertySourceLoader().load("main-yaml", yaml)
                                .forEach(ps -> context.getEnvironment().getPropertySources().addLast(ps));
                    } catch (IOException e) {
                        throw new IllegalStateException("读不到主 application.yml（src/main/resources）", e);
                    }
                })
                .withUserConfiguration(PropsConfig.class)
                .withPropertyValues("USER_JWT_SECRET=")
                .run(context -> assertThat(context).hasFailed());
    }

    // ====================================================================================
    // helpers
    // ====================================================================================

    /**
     * 跑一次「生产同款」启动：真 {@code SpringApplication} ＋ 真 ConfigData 流程，
     * <b>只</b>加载主 {@code application.yml}（= 产物 jar 的真实构成）。
     *
     * @param extraProperties 额外注入的属性（可为空）
     * @return 启动成功的上下文（调用方负责关闭）
     */
    private static ConfigurableApplicationContext prodLikeStart(String... extraProperties) {
        String[] props = new String[extraProperties.length + 2];
        props[0] = "spring.config.location=classpath:/application.yml";
        props[1] = "spring.main.banner-mode=off";
        System.arraycopy(extraProperties, 0, props, 2, extraProperties.length);
        return new SpringApplicationBuilder(PropsConfig.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }

    /**
     * 跑一次「生产同款」启动，<b>把失败原样带回来</b>（而不是让它冒出去）。
     *
     * <p>⚠️ 启动成功时返回 {@code null} ⇒ ⇒ 调用方必须断言「失败不为 null」。
     */
    private static Throwable startOrCaptureFailure(String... extraProperties) {
        try (ConfigurableApplicationContext ctx = prodLikeStart(extraProperties)) {
            // 走到这里就说明「起来了」⇒ 合成一个失败（下面的断言会把它判红）
            CommentApiProperties props = ctx.getBean(CommentApiProperties.class);
            return new IllegalStateException(
                    "启动成功了（实际绑到的值 = [" + props.getUserAuth().getAccessSecret() + "]）");
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * 在失败链上找<b>守卫抛的那一个</b> {@link IllegalStateException}。
     *
     * <p>⚠️ <b>为什么不用「rootCause 就是它」这个更严的判据</b>：
     * Boot 3.5.11 实测失败链 = {@code BeanCreationException → IllegalStateException}
     * ⇒ 根因<b>就是</b>守卫抛的那个 ⇒ 但本方法写成「沿 cause 链找」是为了
     * <b>不把断言绑死在 Spring 的包装层数上</b>，同时<b>仍然</b>要求「必须是守卫抛的那个」
     * （按消息点名配置路径与环境变量名 ⇒ 不会误命中别的 IllegalStateException）。
     */
    private static IllegalStateException findGuardFailure(Throwable failure) {
        Throwable current = failure;
        int guard = 0;
        while (current != null && guard < 20) {
            if (current instanceof IllegalStateException ise
                    && ise.getMessage() != null
                    && ise.getMessage().contains("comment-api.user-auth.access-secret")) {
                return ise;
            }
            current = current.getCause();
            guard++;
        }
        return null;
    }

    /** 把失败链渲染成一行，便于断言失败时直接看出「是哪一条失败」。 */
    private static String describeChain(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 8) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * 让 {@link CommentApiProperties} 进入真实 Spring 生命周期（{@code @PostConstruct} 才会被调）。
     *
     * <p>⚠️ <b>刻意不带 {@code @EnableAutoConfiguration}</b>：⇒ 零自动配置
     * ⇒ ⇒ 不会拉起 DataSource ⇒ ⇒ 主 yml 里那条<b>无默认值</b>的
     * {@code ${POSTGRES_PASSWORD}} <b>永不被解析</b>
     * ⇒ ⇒ 「变量未设 ⇒ 启动失败」这条<b>只可能</b>是密钥守卫造成的。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CommentApiProperties.class)
    static class PropsConfig {
    }
}
