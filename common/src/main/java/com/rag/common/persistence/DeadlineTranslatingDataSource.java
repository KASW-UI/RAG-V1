package com.rag.common.persistence;

import io.grpc.Status;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * <p><b>把「取连接超时」翻成 gRPC {@code DEADLINE_EXCEEDED}</b>（⇒ 域侧落业务码 <b>1015</b>，不是 DB 故障 5001）。
 *
 * <h2>为什么必须在 DataSource 这一层翻（而不是只靠 MyBatis 拦截器）</h2>
 * <p>连接获取有<b>两个</b>发生点，只有其中一个在 MyBatis 的拦截范围内：
 * <ol>
 *   <li><b>事务开启时</b>（{@code DataSourceTransactionManager#doBegin} →
 *       {@code DataSourceUtils.getConnection}）⇒ ⇒ <b>完全在 MyBatis {@code Executor} 之外</b>
 *       ⇒ ⇒ MyBatis 拦截器看不到它；</li>
 *   <li><b>非事务查询时</b>（{@code Executor.query} 内部）⇒ ⇒ 在拦截范围内。</li>
 * </ol>
 * ⇒ ⇒ 实测（2026-11-03 · admin 域 · PG 停）：异常栈顶是
 * {@code org.springframework.jdbc.datasource.DataSourceUtils.getConnection(DataSourceUtils.java:84)}
 * ⇒ ⇒ 落在<b>第 ① 种</b> ⇒ ⇒ 只改翻译器<b>不生效</b>（实测响应仍是 5001）。
 *
 * <h2>为什么翻成 {@code DEADLINE_EXCEEDED} 就是 B 的 1015</h2>
 * <p>B 侧 DB 调用的等待由 ctx 掐住 ⇒ 「等连接等不到」也是 {@code context.DeadlineExceeded}
 * ⇒ ⇒ 落 api logic 的 {@code default} 分支 ⇒ <b>1015「服务繁忙」</b>
 * （B 出处见 {@link SqlTimeoutStatusTranslator} 的类头逐条标注）。
 * ⇒ C 侧没有 ctx ⇒ 等价物是 Hikari 的 {@code connectionTimeout} 到点
 * ⇒ ⇒ 本类把它翻译成同一种 status ⇒ 两侧响应面同形。
 *
 * <h2>⚠️ 为什么继承 {@link DelegatingDataSource}（而不是自己写一个 {@code DataSource}）</h2>
 * <p>Spring Boot 3.5 的 {@code org.springframework.boot.jdbc.DataSourceUnwrapper}（池指标用）
 * <b>专门认识</b> {@code DelegatingDataSource} ⇒ ⇒ 包装后 Boot 仍能解包出底层
 * {@code HikariDataSource} ⇒ ⇒ <b>Hikari 的连接池指标不会丢</b>（否则是一次可观察面的回退）。
 *
 * <p><b>三段式留痕（AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧由 ctx 直接掐住 DB 调用；C 侧没有 ctx ⇒ 折成
 *       「Hikari 的 connectionTimeout 到点」＋「在 DataSource 边界把它翻成 DEADLINE_EXCEEDED」。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两者的可观察面都是「DB 调用在 2s 处失败且被判为超时」
 *       ⇒ ⇒ 客户端拿到 <b>1015</b>（不是 5001）。</li>
 *   <li><b>B 出处</b>：{@code service/common/.../persistence/SqlTimeoutStatusTranslator.java}
 *       的类头（「ctx 到期 ⇒ 落 default ⇒ 1015」）＋ B 侧 {@code zrpc.RpcServerConf.Timeout}。</li>
 * </ol>
 */
public class DeadlineTranslatingDataSource extends DelegatingDataSource {

    public DeadlineTranslatingDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            return super.getConnection();
        } catch (SQLException e) {
            RuntimeException translated = translateIfAcquisitionTimeout(e);
            if (translated != null) {
                throw translated;
            }
            throw e;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        try {
            return super.getConnection(username, password);
        } catch (SQLException e) {
            RuntimeException translated = translateIfAcquisitionTimeout(e);
            if (translated != null) {
                throw translated;
            }
            throw e;
        }
    }

    /**
     * 「取连接等超时」⇒ {@code DEADLINE_EXCEEDED}；其它异常 ⇒ 返回 {@code null}（原样抛）。
     *
     * <p>⚠️ <b>为什么只认「等超时」而不认「连不上」</b>：
     * Hikari 的「连接被拒」也是 {@code 08001}，但那是<b>快速失败</b>
     * ⇒ ⇒ B 侧连接被拒是<b>数据库故障</b>（不是 ctx 到期）⇒ ⇒ 不该翻成 1015
     * ⇒ ⇒ 必须再认准 Hikari 的<b>超时文案</b>（{@code "request timed out after"}）。
     *
     * <p>⚠️ <b>为什么翻出来的必须是 RuntimeException</b>：
     * {@code DataSourceUtils.getConnection} 只把 {@code SQLException} 包成
     * {@code CannotGetJdbcConnectionException} ⇒ 抛 RuntimeException 时它<b>原样穿透</b>
     * ⇒ ⇒ 异常以 {@code StatusRuntimeException} 的形态离开持久层
     * ⇒ ⇒ 域层的 {@code catch (StatusRuntimeException e) { throw e; }} 放行它
     * ⇒ ⇒ api 层的 {@code switch} 落 {@code default} ⇒ <b>1015</b>。
     */
    private RuntimeException translateIfAcquisitionTimeout(SQLException e) {
        if (SqlTimeoutStatusTranslator.isConnectionAcquisitionTimeout(e)) {
            return Status.DEADLINE_EXCEEDED
                    .withDescription("connection acquisition timed out at rpc deadline")
                    .asRuntimeException();
        }
        return null;
    }
}
