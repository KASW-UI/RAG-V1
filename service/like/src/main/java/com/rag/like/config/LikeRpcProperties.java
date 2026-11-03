package com.rag.like.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * like 域配置 —— 对应 Go 端 {@code service/like/rpc/internal/config/config.go} 的
 * {@code StorageConf}（本服务<b>实际读取</b>的那些字段）。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/config/LikeRpcProperties.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：包名由 {@code com.rag.like.like.rpc.config} 压平为
 *       {@code com.rag.like.config}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。三个嵌套段（{@code Redis} / {@code Fact} / {@code Authority}）
 *       的字段名、默认值、单位逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <p><b>只映射 Go 侧真的被读到的字段</b>。Go 的 {@code Config} 里还有一批「配了但没人用」的项，
 * 逐一说明为什么不搬：
 * <table border="1">
 *   <caption>Go Config 中未被使用的字段</caption>
 *   <tr><th>Go 字段</th><th>为什么不映射</th></tr>
 *   <tr><td>{@code RocksDB *}</td>
 *       <td>全量 grep 确认 like/rpc 下<b>无任何引用</b>（点赞数据实际走 Redis + Postgres）。
 *           照搬会让人误以为存在 RocksDB 写路径。</td></tr>
 *   <tr><td>{@code Postgres.SyncBatchSize / SyncInterval}</td>
 *       <td>注释描述的「RocksDB 异步冷备」未实现，无读取点。</td></tr>
 *   <tr><td>{@code HotSpot.*}</td>
 *       <td>热点探测 / 防雪崩参数，like/rpc 下无读取点。</td></tr>
 *   <tr><td>{@code AntiBrush.*}</td>
 *       <td>防刷限流参数，like/rpc 下无读取点（限流在 like-api 或未实现）。</td></tr>
 *   <tr><td>{@code KafkaPusher}</td>
 *       <td>{@code Config} 里有这个匿名字段，但 {@code NewServiceContext} 建的是
 *           {@code c.KafkaConf} 的 pusher，这份配置从未被读取（死配置）。</td></tr>
 * </table>
 *
 * <p>⚠️ 单位：Go 的 {@code CacheTTL int} 是<b>秒</b>（{@code ExpireCtx(ctx, key, int(ttl))}）。
 * Java 侧用 {@link Duration} 让 yaml 必须写单位（{@code 3600s}），消除「秒/毫秒」这类
 * AGENTS.md 点名的配置语义错误。
 */
@ConfigurationProperties(prefix = "like")
public class LikeRpcProperties {

    private final Redis redis = new Redis();
    private final Fact fact = new Fact();
    private final Authority authority = new Authority();

    public Redis getRedis() {
        return redis;
    }

    public Fact getFact() {
        return fact;
    }

    public Authority getAuthority() {
        return authority;
    }

    /**
     * 对应 Go {@code Storage.RedisExtConf} 中被真正读取的字段。
     *
     * <p>⚠️ Go 侧 {@code BatchSize}（yaml 写 1000）<b>没有任何读取点</b>，此处刻意不映射 ——
     * 映射了就会给人「批量查询上限已生效」的错觉。
     */
    public static class Redis {

        /** Go {@code CacheTTL int `default=3600`}：{@code GetUserTotalLike} 回源 SETEX / 命中续期用。 */
        private Duration cacheTtl = Duration.ofSeconds(3600);

        /**
         * Go {@code HotspotTTL int `default=1800`}。
         *
         * <p><b>同样在 Go 侧没有读取点</b>（grep 确认）。保留映射只为配置面完整，
         * 并在此显式标注 —— 避免下一个人以为「热点数据已经按 1800s 过期」。
         */
        private Duration hotspotTtl = Duration.ofSeconds(1800);

        /**
         * 列表类 ZSet 的 TTL —— Go 侧<b>硬编码</b>：
         * {@code getuserlikelistlogic.go:120 Expire(redisKey, 3600*24*7)}、
         * {@code gettargetlikerlistlogic.go:128 Expire(redisKey, 86400*7)}，两处数值相同（7 天）。
         *
         * <p>Go 没把它做成配置，这里也不做成可随意改的开关 ——
         * 改默认值必须是<b>显式改 yaml 并经过 review</b>，而不是代码里一个魔数。
         */
        private Duration listTtl = Duration.ofDays(7);

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public Duration getHotspotTtl() {
            return hotspotTtl;
        }

        public void setHotspotTtl(Duration hotspotTtl) {
            this.hotspotTtl = hotspotTtl;
        }

        public Duration getListTtl() {
            return listTtl;
        }

        public void setListTtl(Duration listTtl) {
            this.listTtl = listTtl;
        }
    }

    /**
     * 事实流派发（Go P3 {@code cmd/fact-dispatch}）配置。
     *
     * <p>Go 是独立进程，配置不全就直接 {@code return error} 让进程退出。
     * Java 侧是单 JVM 合并进程，缺 DC 配置<b>不能</b>让整个 like-rpc 起不来，
     * 因此改为「默认关闭 + 启动时告警」，关闭时该功能完全不参与运行。
     * 这是「多进程 → 单进程」带来的<b>生命周期</b>差异，不改变任何对外行为。
     */
    public static class Fact {

        /** 是否启用事实投递。默认 false（等价于「DC 配置不全时的 Go 进程不启动」）。 */
        private boolean dispatchEnabled = false;

        /** Go {@code DC_PLATFORM_EVENT_URL}。 */
        private String dcEndpoint = "";

        /** Go {@code DC_PLATFORM_SERVICE_TOKEN}（Go 要求长度 ≥32）。 */
        private String dcToken = "";

        /** Go {@code http.Client{Timeout: 8 * time.Second}}。 */
        private Duration dcTimeout = Duration.ofSeconds(8);

        /**
         * Go {@code cmd/fact-dispatch/main.go:25} 的 {@code -once} flag（默认 false）：
         * 「只投递至多一条待发事实」。
         *
         * <p>🔴 <b>2026-10-25（第二十轮）新增</b> —— Go 侧它是<b>独立进程的命令行 flag</b>
         * ⇒ Java 侧合并成单 JVM 后没有天然的对应物 ⇒ 补一个配置项，
         * 与 comment 域（{@code comment.fact.once}）、favorite 域（{@code favorite.dispatch.once}）<b>同款</b>。
         * 默认 false ⇒ 与 Go 的默认值一致。
         * ⇒ 决策记录 <b>P6-a</b> 已定：走 <b>① 降级为配置项</b>（本字段即该落点）。
         */
        private boolean once = false;

        public boolean isDispatchEnabled() {
            return dispatchEnabled;
        }

        public void setDispatchEnabled(boolean dispatchEnabled) {
            this.dispatchEnabled = dispatchEnabled;
        }

        public String getDcEndpoint() {
            return dcEndpoint;
        }

        public void setDcEndpoint(String dcEndpoint) {
            this.dcEndpoint = dcEndpoint;
        }

        public String getDcToken() {
            return dcToken;
        }

        public void setDcToken(String dcToken) {
            this.dcToken = dcToken;
        }

        public Duration getDcTimeout() {
            return dcTimeout;
        }

        public void setDcTimeout(Duration dcTimeout) {
            this.dcTimeout = dcTimeout;
        }

        public boolean isOnce() {
            return once;
        }

        public void setOnce(boolean once) {
            this.once = once;
        }
    }

    /** 权威读端点（Go P4 {@code cmd/fact-authority}）配置。默认关闭。 */
    public static class Authority {

        /** 是否注册权威读 HTTP 端点。默认 false。 */
        private boolean enabled = false;

        /** Go {@code LIKE_FACT_AUTHORITY_TOKEN}（Go 要求长度 ≥32）。 */
        private String token = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}
