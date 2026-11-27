package com.rag.boot.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <p><b>5 进程启动壳的装配层测试基座</b> —— 提供「<b>能起真实 Spring 上下文，但不连外部设施</b>」
 * 的公共配置（<b>属性注入</b>）。
 *
 * <p>⚠️ <b>本基座刻意不带 {@code @SpringBootTest}</b>：各测试类的
 * {@code classes} 清单<b>各不相同</b>（各自的进程启动类 ＋ Nacos 桩）⇒ ⇒ 由各测试类自己声明
 * ⇒ ⇒ 本基座只负责「<b>所有 5 个进程都要的那部分</b>」：属性注入 + 外部设施死地址。
 *
 * <h2>🔴 这一组测试要回答的问题：「<b>组件扫描边界对不对</b>」</h2>
 * <p>AGENTS.md §3.3.1：判断某个机制是否生效，<b>必须有运行证据</b>；§3.3.3：「<b>Bean 存在</b>」
 * ≠「机制生效」；「<b>扫描边界把别的域的 bean 拖进来</b>」是本仓四类静默失效之一
 * ⇒ 它<b>不报错</b>、只在运行时表现为「异常被别的域的 advice 捕获」⇒ ⇒ 守门脚本判不了
 * ⇒ ⇒ 必须有<b>运行期证据</b>：真的建上下文，断言「本域 bean 在、他域 bean 不在」。
 *
 * <h2>⚠️ 为什么本基座<b>不</b>用真 PG / Redis / Kafka</h2>
 * <p>本组测试<b>只</b>验证<b>组件扫描边界</b> ⇒ <b>不</b>需要真实数据存储
 * ⇒ ⇒ <b>不需要 Docker</b> ⇒ ⇒ 属「<b>真触发但自包含</b>」的测试类型（§3.3.3）。
 * <p>⚠️ 代价（诚实登记）：本基座<b>不</b>验证「HTTP 端点真的可达」「gRPC server 真的起来」
 * 「Kafka 真的收发」⇒ ⇒ 那属 <b>S3/S4</b> 的验证范围。
 *
 * <h2>为什么必须显式注入 {@code USER_JWT_SECRET}</h2>
 * <p>各域的 {@code *Properties} 类带<b>fail-closed 守卫</b>：secret 绑到未解析的占位符字面量
 * （{@code ${USER_JWT_SECRET}}）⇒ <b>启动直接失败</b>（实测：
 * {@code IllegalStateException: article-api.auth.access-secret 未配置 … 绑到了未解析的占位符字面量}）。
 * <p>⇒ 这是<b>域内既有的正确行为</b>（不是缺陷）⇒ ⇒ 测试必须显式提供值。
 *
 * <h2>为什么 Kafka / gRPC / MinIO 指向死地址</h2>
 * <p>本组测试<b>不测 MQ</b>、<b>不测跨域 gRPC</b> ⇒ 不必为它们起真实设施；
 * 但<b>客户端必须在初始化期不炸</b>（devh 在 Bean 初始化期建 channel ⇒ {@code discovery://}
 * 解析不了 ⇒ 上下文起不来）⇒ ⇒ 全部指向 {@code 127.0.0.1:1}（必然连不上的死地址）。
 */
public abstract class BootAssemblyTestBase {

    /**
     * 注入「让上下文能起来」的最小必需变量。
     *
     * <p>⚠️ 本方法被<b>所有</b>子类继承 ⇒ {@code Set<Method>} 相同 ⇒ <b>上下文共享</b>
     * （⇒ 5 个进程各起一次上下文，不重复起）。
     *
     * @param registry 属性注册表
     */
    @DynamicPropertySource
    public static void properties(DynamicPropertyRegistry registry) {
        // 🔴 裸占位符必须能被解析（各域 application.yml 的 auth.access-secret）
        registry.add("USER_JWT_SECRET", () -> "test-only-boot-jwt-secret-at-least-32-bytes-long");
        registry.add("ADMIN_JWT_SECRET", () -> "test-only-boot-admin-secret-at-least-32-bytes-long");
        registry.add("FAVORITE_JWT_SECRET", () -> "test-only-boot-favorite-secret-at-least-32-bytes-long");
        // ⚠️ 各域的 Kafka 通道配置统一走 `${KAFKA_BROKERS:kafka:9092}`
        //   （实测 service/article/src/main/resources/application.yml:233/239/248/254）
        //   ⇒ ⇒ 注入这一个环境变量即可让「通道 brokers」与「全局 bootstrap-servers」逐字一致
        //   ⇒ ⇒ 否则 ArticleKafkaChannelValidator 的 fail-fast 守卫会拒绝启动
        //   （实测报错：…的 brokers=[[kafka:9092]] 与全局 …[[127.0.0.1:1]] 不一致）
        registry.add("KAFKA_BROKERS", () -> "127.0.0.1:1");
        // ⚠️ **真 PostgreSQL**（本机 Homebrew PostgreSQL 18 · 127.0.0.1:5432）
        //   ⇒ article 等域有 Flyway 迁移 ⇒ Mapper 的 sqlSessionTemplate 依赖 flywayInitializer
        //      ⇒ ⇒ **启动期必须能连库**（实测：不注入 ⇒ 「Unable to obtain connection from database」）
        //   ⇒ ⇒ 用**真 PG**（不是 H2）：域 DDL 用到 JSONB / ON CONFLICT / plpgsql ⇒ H2 全不认
        //      ⇒ H2 上「跑绿」只能证明字符串被解析了（§3.3.3 假绿）
        registry.add("POSTGRES_HOST", () -> "127.0.0.1");
        registry.add("POSTGRES_PORT", () -> "5432");
        registry.add("POSTGRES_DB", () -> "ragv1_boot_test");
        registry.add("POSTGRES_USER", () -> "pr");
        registry.add("POSTGRES_PASSWORD", () -> "password");

    }
}
