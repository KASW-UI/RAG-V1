package com.rag.follow.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.follow.FollowServiceApplication;
import com.rag.follow.support.FakeUserRpcServer;
import com.rag.follow.support.FollowTestSupport;
import com.rag.follow.support.NacosStubConfiguration;
import com.rag.follow.support.PrometheusTestRegistryConfiguration;
import com.rag.follow.support.TestPostgres;
import com.rag.follow.support.TestRedis;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>follow 域 HTTP 层的「业务码还原链路」端到端断言（真 Tomcat · 真 PG · 真 user-rpc gRPC）</b>。
 *
 * <h2>🔴 为什么必须单独钉这一层（而不是靠领域层测试「顺便」覆盖）</h2>
 * <p>本域的业务码<b>藏在 gRPC status 的 description 文案里</b>
 * （{@code GrpcErrorMapper#grpcError} 用 {@code ErrMsg.getMsg(bizCode)} 当 description），
 * 而「标准 gRPC 码 ⇒ 业务码」的兜底映射表<b>还原不出</b> 5 类业务码：
 * <table border="1">
 *   <caption>5 类「只有文案反查才能还原」的业务码</caption>
 *   <tr><th>业务码</th><th>语义</th><th>gRPC 标准码</th><th>仅靠标准码兜底会得到</th></tr>
 *   <tr><td><b>5201</b></td><td>自关系</td><td>INVALID_ARGUMENT</td><td>5003 ❌</td></tr>
 *   <tr><td><b>5202</b></td><td>已拉黑</td><td>PERMISSION_DENIED</td><td>5005 ❌</td></tr>
 *   <tr><td><b>5101</b></td><td>用户不存在</td><td>NOT_FOUND</td><td>5002 ❌</td></tr>
 *   <tr><td><b>5007</b></td><td>DB 读失败</td><td>INTERNAL</td><td>5001 ❌</td></tr>
 *   <tr><td><b>5008</b></td><td>DB 写失败</td><td>INTERNAL</td><td>5001 ❌</td></tr>
 * </table>
 * ⇒ ⇒ <b>api 层必须真的调用反查</b>，否则客户端拿到的全是 5001/5002/5003/5005。
 * <p>⚠️ <b>而领域层测试证不了这件事</b>：
 * {@code FollowDomainServiceRealPgTest} 是<b>直接调领域层</b>、再自己调
 * {@code GrpcErrorMapper.bizCodeFromError} 反查的 ⇒ 它只证明「反查函数本身正确」，
 * <b>证不了「api 层会调用反查」</b>（§3.1 的自举陷阱：拿自己写的实现当基准）。
 * ⇒ ⇒ <b>本类必须从 HTTP 入口打进去</b>，断言响应体里的 <b>精确码值</b>。
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧产出的码值与 B 侧源码记录的期望一致」。
 * 「B 侧此刻真的产出同样码值」属于<b>双侧活体对拍</b>（本类不冒充该证据）。
 *
 * <h2>⚠️ 为什么注入 {@link PrometheusTestRegistryConfiguration}</h2>
 * <p>本类有一条<b>指标桶边界</b>断言（Go {@code DefBuckets} ⇒ Micrometer
 * {@code serviceLevelObjectives}）。被测对象是<b>主代码里那串 SLO 边界</b>，
 * registry 类型只决定「用什么文本格式把边界吐出来」⇒ ⇒ 注入一个 Prometheus registry
 * 只是让边界<b>可观测</b>，<b>不掩盖</b>主代码的桶配置。
 * <p>⚠️ 「<b>生产形态上下文里没有 PrometheusMeterRegistry ⇒ {@code /actuator/prometheus} 不可用</b>」
 * 是<b>另一件事</b>（Boot 3.5 的自动配置装配顺序问题，<b>跨域共性</b> ⇒ 归 {@code global} 收口域），
 * <b>不在本类固化</b> —— 详见
 * {@code docs/DRIFT/follow/verification-follow-2026-11-02.md} §5.6。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {FollowServiceApplication.class, NacosStubConfiguration.class,
                PrometheusTestRegistryConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "grpc.server.port=-1"
        })
@ActiveProfiles("test")
class FollowHttpBusinessCodeRealTomcatTest {

    /** uid 序号器：与其它测试类共用同一个库 ⇒ uid 必须全局唯一。 */
    private static final AtomicLong UID_SEQ = new AtomicLong(7_200_000_000_000L);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        registry.add("grpc.client.user-rpc.address", FakeUserRpcServer::address);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<PrometheusMeterRegistry> prometheusRegistry;

    /** 本类标记成「下游查不到」的 uid（{@link FakeUserRpcServer} 的集合是静态的 ⇒ 必须精确清理）。 */
    private Long markedNotFoundUid;

    /** 本类标记成「下游超时」的 uid（同上 ⇒ 静态集合，必须精确清理）。 */
    private Long markedTimeoutUid;

    @AfterEach
    void clearFakeNotFound() {
        // FakeUserRpcServer 的 NOT_FOUND 是**静态全局集合** ⇒ 本类用过的 uid 必须清掉，
        // 否则会污染别的测试类对同一个 uid 的存在性判断。
        if (markedNotFoundUid != null) {
            FakeUserRpcServer.markFound(markedNotFoundUid);
            markedNotFoundUid = null;
        }
        if (markedTimeoutUid != null) {
            FakeUserRpcServer.markNotTimeout(markedTimeoutUid);
            markedTimeoutUid = null;
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private static HttpEntity<String> json(String body, String... headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            h.set(headers[i], headers[i + 1]);
        }
        return new HttpEntity<>(body, h);
    }

    private JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("响应体不是合法 JSON: [" + raw + "]", e);
        }
    }

    private void insertBlock(long userId, long targetId) {
        jdbcTemplate.update(
                "INSERT INTO block_relation (user_id, target_id, create_time, update_time) "
                        + "VALUES (?, ?, now(), now()) ON CONFLICT DO NOTHING",
                userId, targetId);
    }

    // ==================================================================
    // 1. 业务码还原链路（api 层真的调用了文案反查）
    // ==================================================================

    @Test
    @DisplayName("🔴 自关注 ⇒ HTTP 200 + {code:5201}（不是 5003/5001 —— 证明 api 层调用了文案反查）")
    void selfRelationReturns5201() {
        long userId = UID_SEQ.incrementAndGet();

        ResponseEntity<String> resp = rest.exchange(
                "/follow/v1/follow", org.springframework.http.HttpMethod.POST,
                json("{\"target_id\":" + userId + "}", "Authorization", FollowTestSupport.bearer(userId)),
                String.class);

        // HTTP 恒 200（B 侧注册了错误处理器 ⇒ 一切业务错误压成 200）
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("自关注必须还原成 5201；若得到 5003 说明退化成 gRPC 标准码兜底，"
                        + "若得到 5001 说明反查根本没被调用")
                .isEqualTo(5201);
        assertThat(body.get("msg").asText()).isEqualTo("self relation is not allowed");
    }

    @Test
    @DisplayName("🔴 已被拉黑 ⇒ HTTP 200 + {code:5202}（不是 5005 —— 证明 api 层调用了文案反查）")
    void blockedRelationReturns5202() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();

        // 对方拉黑我 ⇒ block 行是 (targetId → userId)
        //   ⇒ ExistsAnyBlock 是**双向**判定（(u,t) 或 (t,u)）⇒ 我关注他必须被拒
        insertBlock(targetId, userId);

        ResponseEntity<String> resp = rest.exchange(
                "/follow/v1/follow", org.springframework.http.HttpMethod.POST,
                json("{\"target_id\":" + targetId + "}", "Authorization", FollowTestSupport.bearer(userId)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("已拉黑必须还原成 5202；若得到 5005 说明退化成 gRPC 标准码兜底")
                .isEqualTo(5202);
        assertThat(body.get("msg").asText()).isEqualTo("blocked relation exists");
    }

    @Test
    @DisplayName("🔴 目标用户不存在 ⇒ HTTP 200 + {code:5101}（不是 5002 —— 证明 api 层调用了文案反查）")
    void missingTargetUserReturns5101() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();
        FakeUserRpcServer.markNotFound(targetId);
        markedNotFoundUid = targetId;

        ResponseEntity<String> resp = rest.exchange(
                "/follow/v1/follow", org.springframework.http.HttpMethod.POST,
                json("{\"target_id\":" + targetId + "}", "Authorization", FollowTestSupport.bearer(userId)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("用户不存在必须还原成 5101；若得到 5002 说明退化成 gRPC 标准码兜底")
                .isEqualTo(5101);
        assertThat(body.get("msg").asText()).isEqualTo("user not found");
    }

    /**
     * 🔴 <b>下游超时 ⇒ HTTP 200 + {@code {code:1015, msg:"service busy"}}</b>
     * —— <b>不是 5001</b>。
     *
     * <h3>⚠️ 为什么这条是「契约」而不是「实现细节」</h3>
     * <p>B 侧的 {@code FollowUserExistenceService} 把下游超时折叠成 {@code INTERNAL + 5001}，
     * 但<b>那层折叠在 B 侧从来不可观测</b>：
     * <ul>
     *   <li><b>gRPC 层</b>：服务端 2s 超时调度器先关 call ⇒ 客户端看到 {@code DEADLINE_EXCEEDED}；</li>
     *   <li><b>HTTP 层</b>：api→rpc 那一跳的 2s <b>客户端</b>预算先到 ⇒ api 层看到
     *       {@code DEADLINE_EXCEEDED} ⇒ 反查落到默认码 <b>1015</b>。</li>
     * </ul>
     * ⇒ ⇒ B 侧对客户端的承诺是 <b>1015</b>。
     * <p>⚠️ C 侧合并进程（D1 + D7）后<b>没有 api→rpc 那一跳</b>
     * ⇒ ⇒ 若照抄折叠，折叠就会<b>第一次变得可观测</b> ⇒ 变成 5001
     * ⇒ ⇒ 与 B 侧<b>逐字节不同</b>（E-4 双侧活体对拍场景轮实测：修复前 C=5001 ×3 轮 · B=1015 ×3 轮）。
     * ⇒ ⇒ 领域层必须<b>单独放过 DEADLINE_EXCEEDED</b>（见
     * {@code FollowUserExistenceService} 的类注释「为什么唯独 DEADLINE_EXCEEDED 不折叠」）。
     *
     * <p>⚠️ <b>与「下游挂了」（UNAVAILABLE）必须区分</b>：那是 5001（两侧实测一致）。
     * ⇒ ⇒ 本测试用<b>同一个端点</b>分别覆盖两种形态 ⇒ 少一条分支就红。
     */
    @Test
    @DisplayName("🔴 下游超时 ⇒ HTTP 200 + {code:1015, msg:service busy}（不是 5001 —— B 侧承诺码）")
    void downstreamTimeoutReturns1015ServiceBusy() {
        long callerId = UID_SEQ.incrementAndGet();
        long timedOutUid = UID_SEQ.incrementAndGet();
        FakeUserRpcServer.markTimeout(timedOutUid);
        markedTimeoutUid = timedOutUid;

        ResponseEntity<String> resp = rest.exchange(
                "/follow/v1/follow/list", org.springframework.http.HttpMethod.POST,
                json("{\"user_id\":" + timedOutUid + "}", "Authorization", FollowTestSupport.bearer(callerId)),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = parse(resp.getBody());
        assertThat(body.get("code").asInt())
                .as("下游超时必须还原成 1015（DEADLINE_EXCEEDED 走 gRPC 标准码兜底 ⇒ 默认码）；"
                        + "若得到 5001 说明领域层把它折叠成了 INTERNAL ⇒ 与 B 侧逐字节不同")
                .isEqualTo(1015);
        assertThat(body.get("msg").asText()).isEqualTo("service busy");
    }

    // ==================================================================
    // 2. 指标桶边界（Go DefBuckets ⇒ Micrometer serviceLevelObjectives）
    // ==================================================================
    @Test
    @DisplayName("🔴 指标桶边界必须与 Go 的 DefBuckets 逐个相同（11 个 + Inf），且不得混入 HdrHistogram 边界")
    void metricBucketsMatchGoDefBuckets() {
        // 先打一次请求 ⇒ 让 timer 真正被观察到（桶的 count 从 0 变成 1）
        long userId = UID_SEQ.incrementAndGet();
        rest.exchange(
                "/follow/v1/follow/list", org.springframework.http.HttpMethod.POST,
                json("{}", "Authorization", FollowTestSupport.bearer(userId)),
                String.class);

        Set<String> actual = bucketBoundariesOf("follow_api_request_duration_seconds_bucket");

        // Go prometheus.DefBuckets 的 11 个边界（微秒→秒换算后逐字相同）
        Set<String> expected = new LinkedHashSet<>(java.util.List.of(
                "0.005", "0.01", "0.025", "0.05", "0.1", "0.25", "0.5", "1.0", "2.5", "5.0", "10.0"));
        expected.add("+Inf");

        assertThat(actual)
                .as("指标桶边界集合必须与 Go DefBuckets 逐个相同")
                .containsExactlyInAnyOrderElementsOf(expected);

        // ⚠️ 反向证据：若有人加了 publishPercentileHistogram()，
        //    Micrometer 会额外塞进几十个 HdrHistogram 边界 ⇒ 集合会变大 ⇒ 这条断言会红。
        assertThat(actual).hasSize(12);
    }

    /**
     * 从 Prometheus scrape 文本里抽出某个指标的 {@code le="..."} 边界集合。
     *
     * <p>⚠️ 用 scrape 文本（而不是 {@code Timer.takeSnapshot()}）——
     * 因为「暴露出来的边界」才是客户端真正看到的；快照 API 会漏掉未注册的分位数。
     *
     * <p>⚠️ scrape 文本有两个来源，<b>优先走 actuator 端点</b>（真 HTTP ⇒ 端到端）：
     * <ol>
     *   <li>{@code GET /actuator/prometheus} ⇒ 走 Boot 的 Prometheus 端点（真实暴露路径）；</li>
     *   <li>回退：直接 {@code PrometheusMeterRegistry#scrape()}。</li>
     * </ol>
     * ⇒ 两者都拿不到 ⇒ 断言失败并把原因写进消息（不是静默跳过）。
     */
    private Set<String> bucketBoundariesOf(String metricName) {
        Pattern pattern = Pattern.compile(
                metricName + "\\{[^}]*?le=\"([^\"]+)\"[^}]*}", Pattern.CASE_INSENSITIVE);

        String scrapeText = scrapeText();
        Set<String> found = new TreeSet<>();
        Matcher m = pattern.matcher(scrapeText);
        while (m.find()) {
            found.add(m.group(1));
        }
        assertThat(found)
                .as("指标 %s 必须已注册（scrape 里找不到任何 le= 边界）", metricName)
                .isNotEmpty();
        return found;
    }

    /** 取 Prometheus 文本：优先 actuator 端点，回退到 registry 直取。 */
    private String scrapeText() {
        try {
            ResponseEntity<String> resp = rest.getForEntity("/actuator/prometheus", String.class);
            if (resp.getStatusCode().value() == 200 && resp.getBody() != null) {
                return resp.getBody();
            }
        } catch (RuntimeException ignored) {
            // 回退到 registry 直取
        }
        PrometheusMeterRegistry registry = prometheusRegistry.getIfAvailable();
        assertThat(registry)
                .as("既拿不到 /actuator/prometheus 的 200，也找不到 PrometheusMeterRegistry bean "
                        + "⇒ 本域的指标根本没有以 Prometheus 文本暴露")
                .isNotNull();
        return registry.scrape();
    }
}
