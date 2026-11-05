package com.rag.favorite.metrics;

import com.rag.favorite.domain.service.FavoriteDomainService;
import com.rag.favorite.mapper.FavoriteFolderMapper;
import com.rag.favorite.mapper.FavoriteItemMapper;
import com.rag.favorite.service.FavoriteArticleSnapshotResolver;
import com.rag.favorite.service.FavoriteFactWriteService;
import com.rag.favorite.service.FavoriteUserGuard;
import com.rag.proto.favorite.CreateFavoriteFolderReq;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 守门：favorite-rpc 的 {@code result} 标签必须<b>按真实结果</b>记
 * （成功 {@code success} / 失败 {@code fail}）。
 *
 * <h2>为什么这条是 U5 撤销后的替代守门</h2>
 *
 * <p>2026-10-23 的台账把 Go 侧判成「21 处错误返回被记成 {@code success}」
 * （代表调用点 {@code service/favorite/rpc/internal/logic/create_favorite_folder_logic.go:52}），
 * 2026-10-25 用<b>实测</b>（{@code tools/metrics-probe/favorite/}）证伪：
 * Go 的 {@code return nil, favoritecommon.GRPCError(...)} <b>会</b>把值赋给命名返回值
 * {@code err} ⇒ defer 里的 {@code ObserveRPC} 记的是 {@code fail}。
 * ⇒ <b>Go 与 RAG 本来就一致</b>，U5 撤销。
 *
 * <p>⇒ 真正的风险转移到 <b>RAG 侧</b>：Java 没有「命名返回值 + defer」这套组合，
 * RAG 靠 try/catch/finally + <b>每个失败分支显式 {@code result = RESULT_FAIL}</b>
 * 来达成同一语义 ⇒ <b>新增失败分支时漏设，指标就会把失败记成 success</b>。
 *
 * <h2>⚠️ 为什么用<b>运行时</b>断言而不是读源码</h2>
 * <p>读源码找「每个 catch 块里是否设了 FAIL」在本次开发中被实测为<b>不可靠</b>：
 * 一个 catch 块可以覆盖多个 throw，而 {@code RESULT_FAIL} 只在块首设一次
 * ⇒ 朴素扫描会误报 7 处「漏设」（实测全是误报）。
 * ⇒ 本类改用<b>真跑一遍</b>：让 mapper 真的抛异常，看指标里到底记成什么。
 *
 * <h2>§3.2 三段式留痕</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>无</b>（用例、断言值、指标名逐条照搬 B 侧）。
 *       两处表达层差异：
 *       <ul>
 *         <li><b>指标类落点</b>：B 侧 {@code com.rag.favorite.favorite.rpc.metrics.FavoriteRpcMetrics}
 *             ⇒ C 侧 {@code com.rag.favorite.metrics.FavoriteRpcMetrics}（D7 包压平）；
 *             <b>方法签名逐条一致</b>（{@code observeRpc(module, action, started, result)} /
 *             {@code observeOp(module, action, result)} / {@code observeDbError(module, action, type)}）。</li>
 *         <li><b>雪花注入</b>：B 侧构造器注入 {@code SnowflakeBean}（用例里 mock 出 123L）；
 *             C 侧共享层把雪花做成<b>静态</b>（{@code com.rag.common.snowflake.Snowflake.nextId()}），
 *             构造器<b>少一个参数</b> ⇒ 本类<b>不再 mock 雪花</b>；
 *             <b>理由</b>：本类断言的是「指标被记成什么」，<b>与 ID 取值无关</b>
 *             ⇒ 静态雪花产出的真实 ID 不影响任何一条断言。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 被测的仍是「同一条 {@code createFavoriteFolder} 路径上，
 *       Micrometer registry 里 4 个 counter 的名字与标签取值」，
 *       指标名（{@code favorite_request_total} / {@code favorite_request_seconds_counter} /
 *       {@code favorite_ops_total} / {@code favorite_db_error_total}）<b>逐字沿用 B 侧</b>。</li>
 *   <li><b>B 出处</b>：B 侧测试
 *       {@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/metrics/FavoriteRpcMetricsResultTagTest.java:50-156}
 *       ＋ B 侧被测源
 *       {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/metrics/FavoriteRpcMetrics.java:19-131}
 *       ＋ Go 侧 {@code service/favorite/rpc/internal/logic/metrics.go:14-72}。</li>
 * </ol>
 */
class FavoriteRpcMetricsResultTagTest {

    private SimpleMeterRegistry registry;
    private FavoriteRpcMetrics metrics;
    private FavoriteFolderMapper folderMapper;
    private FavoriteDomainService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new FavoriteRpcMetrics(registry);
        folderMapper = mock(FavoriteFolderMapper.class);

        // 🔴 C 侧构造器**不接雪花**（共享层静态生成，见类注释「雪花注入」条）⇒ 6 个参数。
        service = new FavoriteDomainService(
                folderMapper,
                mock(FavoriteItemMapper.class),
                mock(FavoriteFactWriteService.class),
                mock(FavoriteUserGuard.class),
                mock(FavoriteArticleSnapshotResolver.class),
                metrics);
    }

    @Test
    @DisplayName("入参非法（userId<=0）⇒ 记 fail，不是 success")
    void invalidParamIsRecordedAsFail() {
        CreateFavoriteFolderReq req = CreateFavoriteFolderReq.newBuilder()
                .setUserId(0L)
                .setName("x")
                .build();

        assertThrows(RuntimeException.class, () -> service.createFavoriteFolder(req));

        assertEquals(1.0, counter("favorite_request_total", "result", "fail"),
                "失败路径必须记 fail —— Go 侧 defer 里的 ObserveRPC 看到的是非 nil 的 err");
        assertNull(counterOrNull("favorite_request_total", "result", "success"),
                "失败路径**不能**同时记 success（否则同一个请求被计两次）");
    }

    @Test
    @DisplayName("DB 查询失败（重名预查抛 DataAccessException）⇒ 记 fail，且 db_error 也记上")
    void dbFailureIsRecordedAsFail() {
        when(folderMapper.findByUserIdAndName(anyLong(), anyString()))
                .thenThrow(mock(DataAccessException.class));

        CreateFavoriteFolderReq req = CreateFavoriteFolderReq.newBuilder()
                .setUserId(1L)
                .setName("x")
                .build();

        assertThrows(RuntimeException.class, () -> service.createFavoriteFolder(req));

        assertEquals(1.0, counter("favorite_request_total", "result", "fail"),
                "DB 失败必须记 fail");
        assertEquals(1.0, counter("favorite_db_error_total", "type", "db"),
                "Go create_favorite_folder_logic.go:83 的 ObserveDBError(folderModule, \"select\", \"db\")");
    }

    @Test
    @DisplayName("成功路径 ⇒ 记 success，且不记 fail")
    void successIsRecordedAsSuccess() {
        when(folderMapper.findByUserIdAndName(anyLong(), anyString())).thenReturn(null);

        CreateFavoriteFolderReq req = CreateFavoriteFolderReq.newBuilder()
                .setUserId(1L)
                .setName("x")
                .build();

        service.createFavoriteFolder(req);

        assertEquals(1.0, counter("favorite_request_total", "result", "success"),
                "成功路径必须记 success（Go 侧 err 保持 nil）");
        assertNull(counterOrNull("favorite_request_total", "result", "fail"),
                "成功路径不应产生 fail 计数");
    }

    @Test
    @DisplayName("指标名与 Go 逐条一致（Micrometer 侧的 counter 名）")
    void metricNamesMatchGo() {
        // ⚠️ observeRpc 是**唯一**会创建 favorite_request_seconds_counter 的出口
        //    （Go metrics.go:63）⇒ 必须先调它，否则断言的是一个从未注册的 meter。
        metrics.observeRpc("favorite_folder", "create", java.time.Instant.now(), "success");
        metrics.observeOp("favorite_folder", "create", "success");
        metrics.observeDbError("favorite_folder", "select", "db");

        assertNotNull(registry.find("favorite_request_total").counter(),
                "Go metrics.go:14-19 ⇒ favorite_request_total");
        assertNotNull(registry.find("favorite_ops_total").counter(), "Go metrics.go:30-35 ⇒ favorite_ops_total");
        assertNotNull(registry.find("favorite_db_error_total").counter(),
                "Go metrics.go:38-43 ⇒ favorite_db_error_total");
        assertNotNull(registry.find("favorite_request_seconds_counter").counter(),
                "Go metrics.go:22-27 ⇒ favorite_request_seconds_counter"
                        + "（⚠️ Prometheus 渲染时 Micrometer 会追加 _total —— 这是已登记的 DEV-FAV-RPC-001）");
    }

    private double counter(String name, String tagKey, String tagValue) {
        Counter c = counterOrNull(name, tagKey, tagValue);
        assertNotNull(c, "指标不存在：" + name + "{" + tagKey + "=" + tagValue + "}");
        return c.count();
    }

    private Counter counterOrNull(String name, String tagKey, String tagValue) {
        return registry.find(name).tag(tagKey, tagValue).counter();
    }
}
