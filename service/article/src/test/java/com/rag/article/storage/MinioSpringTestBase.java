package com.rag.article.storage;

import com.rag.article.support.ArticleRealPgTestBase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <p><b>U-12 的 Spring 上下文底座</b> —— 继承 {@link ArticleRealPgTestBase}（真 PG + 真装配），
 * <b>只额外注入真 MinIO 的四个配置键</b>。
 *
 * <h2>为什么不直接改 {@code support/ArticleRealPgTestBase}</h2>
 * <p>{@code support/} 是<b>别人的写权</b>（本轮任务边界）⇒ 只能在本包内新建基类。
 * <br>⚠️ 父类的 {@code @SpringBootTest(properties=…)} 里有
 * {@code "article-rpc.minio.endpoint=127.0.0.1:1"}（让 MinIO <b>快速失败</b>，避免启动期等 DNS）
 * ⇒ 本类必须把它<b>覆盖</b>成真容器的地址。
 *
 * <h2>为什么覆盖是<b>确定</b>的（不是「应该会赢」）</h2>
 * <p>两条 property source 的优先级（Spring Framework 6.2.16 · {@code spring-test}）：
 * <ul>
 *   <li>{@code @SpringBootTest(properties=…)} ⇒ {@code TestPropertySourceUtils
 *       .addInlinedPropertiesToEnvironment} ⇒ {@code MutablePropertySources.addFirst(inlined)}；</li>
 *   <li>{@code @DynamicPropertySource} ⇒ {@code DynamicValuesPropertySource.getOrCreate(env)}
 *       ⇒ {@code MutablePropertySources.addFirst(dynamic)}（<b>在 inlined 之后执行</b>
 *       ⇒ 排在更前面 ⇒ <b>优先级更高</b>）。</li>
 * </ul>
 * ⇒ <b>动态属性赢</b>。而且这不是「靠推断」：U-12 的用例①若端点没被覆盖，
 * 连真 MinIO 都连不上 ⇒ <b>立刻红</b> ⇒ 覆盖是否生效由<b>运行时</b>判定，不靠嘴说。
 *
 * <h2>⚠️ 本类会<b>另起一个 Spring 上下文</b>（不是复用父类那个）</h2>
 * <p>Spring 的 {@code ContextCache} 用 {@code MergedContextConfiguration} 作 key，
 * 而 {@code @DynamicPropertySource} 的方法集合是 key 的一部分
 * （{@code DynamicPropertiesContextCustomizer#equals} 比的是 {@code Set<Method>}）
 * ⇒ 本类多了一个方法 ⇒ key 变了 ⇒ <b>独立上下文</b>。
 * <br>代价：多一次完整装配（连 PG、跑 Flyway、起 gRPC server）。
 * <br>好处：<b>不污染</b>其它测试类共享的那个上下文（那个上下文里 MinIO 端点是
 * {@code 127.0.0.1:1} ⇒ 启动期必失败 ⇒ 若复用，{@code MinioBucketBootstrap} 会打一条 error 日志）。
 * <br>⚠️ PG 容器仍是<b>同一个单例</b>（{@code support/TestPostgres}）⇒
 * <b>数据必须用互不重叠的 uid 段</b>（本包用 {@code 904xxxxx}）。
 */
public abstract class MinioSpringTestBase extends ArticleRealPgTestBase {

    /**
     * 把真 MinIO 的四个配置键注入上下文。
     *
     * <p>⚠️ 只注入<b>连接相关</b>的键；{@code bucket-name} / {@code image-path} /
     * {@code article-path} <b>保持生产默认值</b>（{@code articles} / {@code articles/images/} /
     * {@code articles/content/}）⇒ 被测行为与生产同形，不另造配置形态。
     *
     * <p>⚠️ 父类的 {@code properties} 里那条 {@code article-rpc.minio.endpoint=127.0.0.1:1}
     * 被本方法<b>覆盖</b>（见类级 javadoc 的优先级论证）。
     */
    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) {
        registry.add("article-rpc.minio.endpoint", TestMinio::endpoint);
        registry.add("article-rpc.minio.public-base-url", TestMinio::publicBaseUrl);
        registry.add("article-rpc.minio.access-key-id", () -> TestMinio.ACCESS_KEY);
        registry.add("article-rpc.minio.secret-access-key", () -> TestMinio.SECRET_KEY);
    }
}
