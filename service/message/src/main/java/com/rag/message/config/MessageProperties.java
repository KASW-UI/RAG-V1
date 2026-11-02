package com.rag.message.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <p><b>message 域业务配置</b> —— 对应 Go 端 {@code service/message/rpc/internal/config/config.go}：
 *
 * <pre>
 * type Config struct {
 *     zrpc.RpcServerConf
 *     Postgres  Postgres      // 由 spring.datasource.* 接管
 *     UserRpc   zrpc.RpcClientConf   // 由 grpc.client.user-rpc 接管
 *     AdminRpc  zrpc.RpcClientConf   // 由 grpc.client.admin-rpc 接管
 *     List      ListConf
 *     Broadcast BroadcastConf
 * }
 *
 * type ListConf struct {
 *     DefaultLimit int32 `json:",default=20"`
 *     MaxLimit     int32 `json:",default=100"`
 * }
 * type BroadcastConf struct {
 *     PageSize int64 `json:",default=200"`
 * }
 * </pre>
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/config/MessageProperties.java}
 * （B 侧类 javadoc {@code :6-38} · 前缀 {@code :40} · 两个字段 {@code :47} / {@code :53} ·
 * 手写 accessor {@code :55-58} · 内部类 {@code List :60-73} / {@code Broadcast :75-86}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.rpc.config} →
 *       {@code com.rag.message.config}，D7 包压平）＋ <b>Lombok 化</b>
 *       （{@code @Getter} ＋ {@code @Setter} 替掉 B 侧 {@code :55-58} 与两个内部类的
 *       手写 accessor，「不用 Lombok 时的完整代码」见下方三处展开块）
 *       ＋ <b>去掉 {@code @Component}</b>（见下方「装配方式」小节）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>配置前缀 {@code message} 与两个段名 {@code list} / {@code broadcast}
 *             <b>逐字未动</b> ⇒ {@code application.yml} 的 {@code message.list.*} /
 *             {@code message.broadcast.*} 键名不变（L1）；</li>
 *         <li>三个默认值 {@code defaultLimit=20} / {@code maxLimit=100} / {@code pageSize=200}
 *             <b>逐字照抄</b>（Go 的 {@code json:",default=…"} ⇒ Java 侧字段初始值）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>装配方式（⚠️ 与 B 侧不同的一处，语义等价）</b>：
 * B 侧靠类上的 {@code @Component} 被组件扫描捡起；本仓按 D7 合并为<b>一个</b>进程 ⇒
 * 由<b>应用入口类</b>统一注册（{@code @ConfigurationPropertiesScan("com.rag.message.config")}，
 * 与 C 侧 admin 域同款）⇒ ⇒ 本类<b>刻意不带 {@code @Component}</b>：
 * 若同时带 {@code @Component} 又被 {@code @ConfigurationPropertiesScan} 扫到
 * ⇒ 同一个类型注册<b>两个</b> bean ⇒ 构造器注入时 {@code NoUniqueBeanDefinitionException}。
 *
 * <p><b>字段 1:1 对齐</b> —— Go int32 对 Java {@code int}；Go int64 对 Java {@code long}。
 *
 * <p>Go 的 RpcServerConf / Postgres / UserRpc / AdminRpc 由 Spring Boot application.yml 直接接管
 * （grpc.server / spring.datasource / grpc.client.user-rpc / grpc.client.admin-rpc），
 * 所以这里只承载 List + Broadcast 两块 message 服务专属业务配置。
 *
 * <p>Logic 层通过构造器注入，从 {@link #getList()} / {@link #getBroadcast()} 读 default/max/PageSize
 * 阈值（与 Go 端 {@code normalizeLimit} + {@code collectBroadcastRecipients} 行为 1:1）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class MessageProperties {
 *     private List list = new List();
 *     private Broadcast broadcast = new Broadcast();
 *
 *     public List getList() { return list; }
 *     public void setList(List list) { this.list = list; }
 *     public Broadcast getBroadcast() { return broadcast; }
 *     public void setBroadcast(Broadcast broadcast) { this.broadcast = broadcast; }
 * }
 * }</pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "message")
public class MessageProperties {

    /**
     * 列表查询（ListNotifications / ListConversations / GetConversationMessages）的 limit 兜底配置。
     * 对应 Go {@code ListConf}：{@code DefaultLimit = 20}, {@code MaxLimit = 100}。
     */
    private List list = new List();

    /**
     * 广播发送通知的 PageSize 阈值。
     * 对应 Go {@code BroadcastConf}：{@code PageSize = 200}。
     */
    private Broadcast broadcast = new Broadcast();

    /**
     * 对应 Go {@code ListConf}。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class List {
     *     private int defaultLimit = 20;
     *     private int maxLimit = 100;
     *
     *     public int getDefaultLimit() { return defaultLimit; }
     *     public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }
     *     public int getMaxLimit() { return maxLimit; }
     *     public void setMaxLimit(int maxLimit) { this.maxLimit = maxLimit; }
     * }
     * }</pre>
     */
    @Getter
    @Setter
    public static class List {

        /** 对应 Go {@code DefaultLimit int32 json:",default=20"}。 */
        private int defaultLimit = 20;

        /** 对应 Go {@code MaxLimit int32 json:",default=100"}。 */
        private int maxLimit = 100;
    }

    /**
     * 对应 Go {@code BroadcastConf}。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class Broadcast {
     *     private long pageSize = 200;
     *
     *     public long getPageSize() { return pageSize; }
     *     public void setPageSize(long pageSize) { this.pageSize = pageSize; }
     * }
     * }</pre>
     */
    @Getter
    @Setter
    public static class Broadcast {

        /**
         * 对应 Go {@code PageSize int64 json:",default=200"}：
         * 广播发送通知时分页拉取 admin.GetUserList 的页大小。
         */
        private long pageSize = 200;
    }
}
