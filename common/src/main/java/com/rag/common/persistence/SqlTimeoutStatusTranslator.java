package com.rag.common.persistence;

import io.grpc.Status;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.SQLException;
import java.util.Properties;

/**
 * <p>B-1 缓解（第二半）：把「SQL 被 deadline 取消」翻译成 gRPC 的 {@code DEADLINE_EXCEEDED}。
 *
 * <p><b>为什么必须有这一半</b>：{@link DeadlineQueryTimeoutInjector} 让 pgjdbc 发 CancelRequest 之后，
 * 驱动抛的是 {@code PSQLException: ERROR: canceling statement due to user request}，
 * SQLState = <b>57014</b>。而业务层的 DB 调用几乎都写成
 * {@code catch (Exception e) { throw Status.INTERNAL.withDescription("DB查询失败")... }} ——
 * 于是超时会被当成「数据库故障」上报。
 * 2026-10-21 的端到端实测证实了这个后果：只注入 {@code default-statement-timeout=2}
 * 而不做翻译时，RAG 的超时响应体是 {@code {"code":5001,"msg":"系统内部错误"}}，
 * 而 B 侧 是 {@code {"code":1015,"msg":"服务繁忙"}} —— <b>离 B 侧 更远了</b>。
 *
 * <p><b>B 出处（消费点，不是声明点）—— 1015 是谁产出的</b>：
 * <b>各 api logic 显式 {@code switch st.Code()} 的 {@code default} 分支</b>；
 * <b>不是</b> 框架层的错误映射，<b>也不是</b> deadline 映射：
 * <ul>
 *   <li>{@code service/user/admin/api/internal/logic/admin/getuserlistlogic.go:44-48}
 *       （{@code switch st.Code()} 44 · {@code case codes.Internal:} 45 → {@code errmsg.ErrorServerCommon} 46
 *       · {@code default:} 47 → {@code errmsg.CodeServerBusy} 48）
 *       ⇒ <b>同族共 13 个 admin logic</b>同款
 *       （{@code resetuserpasswordlogic.go:56} · {@code deletelogic.go:55} · {@code getuserlogic.go:48}
 *       · {@code unbanuserlogic.go:56} · {@code banuserlogic.go:56} · {@code updatelogic.go:66}
 *       · {@code getuserlistlogic.go:48} · {@code createinvitelogic.go:62} · {@code loginlogic.go:56}
 *       · {@code logoutlogic.go:52} · {@code createlogic.go:55} · {@code updateuserlogic.go:62}
 *       · {@code getselflogic.go:59}）；</li>
 *   <li>{@code service/user/user/api/internal/logic/user/selectavatarlogic.go:59-60}
 *       （{@code default:} → {@code errmsg.CodeServerBusy}）—— user 域同款；</li>
 *   <li>{@code service/user/common/errmsg/errmsg.go:6}（{@code CodeServerBusy = 1015}）
 *       ＋ {@code :41}（{@code "服务繁忙"}）；</li>
 * </ul>
 * ⇒ <b>这些 switch 只单列了 {@code codes.Internal}（→5001）与 {@code codes.NotFound}（→500）
 * ⇒ {@code DEADLINE_EXCEEDED} 未被单列 ⇒ 落 default ⇒ 1015</b>。
 *
 * <p><b>那 B 侧 ctx 到点是怎么变成 {@code DeadlineExceeded} 的</b>：
 * {@code service/common/src/main/java/com/rag/common/persistence/SqlTimeoutStatusTranslator.java:52:146-158}（{@code IsTimeoutError}：
 * {@code errors.Is(err, context.DeadlineExceeded)} 在 {@code :150}、
 * {@code codes.DeadlineExceeded} 在 {@code :153}、网络超时在 {@code :157}）
 * ⇒ 它<b>只做可观测性判定（判「是不是超时」），不产出任何业务码</b>
 * ⇒ ⇒ 「超时 ⇒ 1015」这一步<b>完全落在 api logic 的 default 分支</b>上。
 * ⚠️ （{@code knowledge} 域另有 4 处 {@code context.DeadlineExceeded}，但该域不在本项目范围。）
 *
 * <p><b>RAG 侧的对应消费点</b>（同样逐行）：
 * <ul>
 *   <li>{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/advice/GlobalExceptionHandler.java:83}
 *       （{@code mapRPCStatusToCode} 声明）＋ <b>:90</b>（default → {@code CODE_SERVER_BUSY} 1015）；</li>
 *   <li>{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/advice/GlobalExceptionHandler.java:153}
 *       ＋ <b>:162</b>（同款）；</li>
 * </ul>
 * ⇒ <b>因此本类只要产出正确的 DEADLINE_EXCEEDED，api 层无需任何改动即可与 B 侧 对齐</b>。
 *
 * <p>⚠️ <b>结论的适用范围</b>：「{@code DEADLINE_EXCEEDED} 未被单列 ⇒ 落 default ⇒ 1015」
 * <b>只对 users 域（admin + user 的 api）成立</b> ——
 * B 侧 <b>article 域单列了</b> {@code codes.DeadlineExceeded}
 * （{@code service/common/src/main/java/com/rag/common/persistence/SqlTimeoutStatusTranslator.java:71:66}
 * ⇒ 走 {@code errmsg.ErrorArticlePublishing}，<b>不走 1015</b>）
 * ⇒ <b>本条不适用于 article 域</b>，也不外推到其它域。
 *
 * <p><b>为什么不能翻译成 INTERNAL 再在 api 层改</b>：那是把「超时」和「数据库真的坏了」混成
 * 一个码，而 B 侧这两者本来就不同（超时 1015 / 数据库错 5001）。翻译点必须在
 * 异常离开数据访问层之前，也就是这里。
 *
 * <p><b>拦截位置选 {@link Executor} 而非 {@code StatementHandler}</b>：CancelRequest 引发的
 * {@code SQLException} 是在<b>执行</b>阶段抛出的，{@code prepare} 阶段根本看不到它。
 * {@code Executor} 是最靠近业务调用、又能兜住整次 query/update 的位置。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class,
                        ResultHandler.class, CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SqlTimeoutStatusTranslator implements Interceptor {

    /**
     * PostgreSQL 的 {@code query_canceled} SQLState。
     * 由 CancelRequest（{@code setQueryTimeout} 到点、或 pg 的 {@code pg_cancel_backend}）产生。
     */
    static final String PG_QUERY_CANCELED = "57014";

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        try {
            return invocation.proceed();
        } catch (Throwable t) {
            if (isStatementTimeout(t)) {
                throw Status.DEADLINE_EXCEEDED
                        .withDescription("sql statement canceled at rpc deadline")
                        .asRuntimeException();
            }
            throw t;
        }
    }

    /**
     * 判断异常链里是否含 SQLState 57014。
     *
     * <p><b>为什么必须遍历整条 cause 链、而不是直接 catch (SQLException)</b>：
     * MyBatis 的 {@link Invocation#proceed()} 声明的是
     * {@code throws InvocationTargetException, IllegalAccessException}，
     * 它是<b>反射调用</b>目标方法，真正的 {@code SQLException}（{@code Executor.query} 声明抛它）
     * 被包在 {@code InvocationTargetException.getTargetException()} 里。
     * 写成 {@code catch (SQLException e)} 会<b>永远匹配不上</b>，而且编译器不会报错
     * （catch 一个 Throwable 声明里可能出现的子类是合法的）——
     * 典型的 §3.3「不报错的失效」，翻译器会静默地什么都不做。
     *
     * <p>逐层遍历同时覆盖了 {@code BatchExecutor}、Spring {@code SqlSessionTemplate}
     * 等可能再裹一层的场景；防御 {@code cause == self} 的自引用环，避免异常链畸形时死循环。
     *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/persistence/SqlTimeoutStatusTranslator.java}
 * （映射表 §1.7 第 191 行 · 1:1）
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
    static boolean isStatementTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && PG_QUERY_CANCELED.equals(sql.getSQLState())) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 本拦截器无配置项：识别目标固定为 PostgreSQL 的 57014。
    }
}