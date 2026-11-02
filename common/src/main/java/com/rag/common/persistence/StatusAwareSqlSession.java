package com.rag.common.persistence;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <p><b>把「持久层翻译出来的 {@code DEADLINE_EXCEEDED}」从 MyBatis 的包装层里<b>解包</b>出来</b>，
 * 让它以 {@link StatusRuntimeException} 的形态<b>直达域层</b>。
 *
 * <h2>为什么必须解包（实测 · 2026-11-03 · admin 域 · PG 停）</h2>
 * <p>MyBatis 的 {@code DefaultSqlSession} 对<b>每一个</b>从 mapper 调用里冒出来的异常
 * <b>无条件</b>包一层 {@code PersistenceException}（源码实测，MyBatis 3.5.19）：
 * <pre>
 *   org.apache.ibatis.session.defaults.DefaultSqlSession
 *       .selectCursor / .selectList / .insert / .update / .delete / ...
 *       → catch (Exception e) → throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
 * </pre>
 * ⇒ ⇒ 共享层的翻译器（{@link SqlTimeoutStatusTranslator}）与
 * {@link DeadlineTranslatingDataSource} 抛出的 {@link StatusRuntimeException}
 * <b>在离开 mapper 调用之前就被吃掉一层</b> ⇒ ⇒
 * 域层（{@code catch (StatusRuntimeException e) { throw e; }}）<b>永远看不见它</b>
 * ⇒ ⇒ 落进 {@code catch (Exception e)} 分支 ⇒ ⇒ <b>被改写成 {@code INTERNAL}（5001）</b>
 * ⇒ ⇒ 与 B 侧的 <b>1015「服务繁忙」</b>不一致。
 *
 * <p><b>实测证据</b>（admin 域 · {@code POST /admin/v1/getself} · PG 停）：
 * <pre>
 *   org.apache.ibatis.exceptions.PersistenceException:
 *     ### Cause: io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED: connection acquisition timed out at rpc deadline
 *     at org.apache.ibatis.exceptions.ExceptionFactory.wrapException(ExceptionFactory.java:30)
 *     at org.apache.ibatis.session.defaults.DefaultSqlSession.selectCursor(DefaultSqlSession.java:129)
 *   ...
 *   Caused by: io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED: ...
 *     at com.rag.common.persistence.DeadlineTranslatingDataSource.translateIfAcquisitionTimeout(...)
 * </pre>
 * ⇒ ⇒ 翻译<b>确实发生了</b>，只是被 MyBatis 重新包了一层 ⇒ ⇒ 域层放行分支失配。
 *
 * <h2>为什么落在这里（缝的选取 · 逐条读源码确定）</h2>
 * <p>本仓的两条 mapper 调用路径<b>都</b>经过 {@link SqlSessionFactory#openSession()}：
 * <ol>
 *   <li><b>常规路径</b>：{@code MybatisMapperMethod.execute(sqlSession, args)}
 *       → {@code sqlSession.selectOne/selectList/insert/...}
 *       → （mapper 持有的 session 是 {@code SqlSessionTemplate}）
 *       → {@code SqlSessionTemplate.SqlSessionInterceptor.invoke}
 *       → {@code SqlSessionUtils.getSqlSession(factory, ...)} → {@code factory.openSession(execType)}
 *       ⇒ ⇒ <b>经过工厂</b>；</li>
 *   <li><b>游标路径</b>（{@code BaseMapper.selectOne(Wrapper, boolean)} —— MP 3.5.17 实测源码）：
 *       → {@code MybatisUtils.getSqlSessionFactory(sqlSession)}
 *       → {@code SqlSessionTemplate.getSqlSessionFactory()}（<b>public getter</b>）
 *       → {@code factory.openSession()}
 *       ⇒ ⇒ <b>同样经过工厂</b>。</li>
 * </ol>
 * ⇒ ⇒ <b>包装 {@link SqlSessionFactory} 这一个 bean 就能同时覆盖两条路径</b>
 * ⇒ ⇒ 不需要碰 mapper 代理（碰了会破坏 MP 的 {@code MybatisUtils.getMapperProxy(proxy)}
 * 的代理身份识别 ⇒ <b>不可行</b>）。
 *
 * <h2>为什么只解包 {@code DEADLINE_EXCEEDED}（不放宽到「任意 status」）</h2>
 * <p>本仓的业务域有<b>46 处</b> {@code catch (StatusRuntimeException e) { throw e; }}
 * ⇒ ⇒ 放行任意 status 会<b>连带改变</b>那些分支的既有行为（例如域自己造的
 * {@code NOT_FOUND} 会被改由 advice 的通用表映射 ⇒ 业务码可能变）
 * ⇒ ⇒ 只解包<b>共享层自己造的</b>那一个码（超时）⇒ 爆炸半径最小。
 *
 * <p><b>三段式留痕（AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧的 gRPC 中间件把 {@code context.DeadlineExceeded}
 *       <b>原样</b>交给 api 层（中间件不做异常包装）⇒ C 侧没有那一跳 ⇒
 *       MyBatis 会把 status 包进 {@code PersistenceException} ⇒ ⇒ 本类把这一层<b>补回来</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 解包后域层看到的就是 B 侧 api 层会看到的那一个
 *       {@code StatusRuntimeException(DEADLINE_EXCEEDED)} ⇒ ⇒ 落 api 层的
 *       {@code mapRPCStatusToCode} 的 {@code default} 分支 ⇒ <b>1015</b>。</li>
 *   <li><b>B 出处</b>：B 侧 {@code service/common/.../persistence/SqlTimeoutStatusTranslator.java}
 *       的类头（「ctx 到期 ⇒ 落 default ⇒ 1015」）＋ 本仓
 *       {@code docs/GORM-MP-语义对照.md} 第 157 行（「57014 必须被翻译成 DEADLINE_EXCEEDED，
 *       且<b>不能被 {@code catch (Exception e)} 吞成 INTERNAL</b>」）。</li>
 * </ol>
 */
public class StatusAwareSqlSession implements SqlSession {

    private final SqlSession delegate;
    private final SqlSessionFactory factory;

    public StatusAwareSqlSession(SqlSession delegate, SqlSessionFactory factory) {
        this.delegate = delegate;
        this.factory = factory;
    }

    // ── 查询 ──────────────────────────────────────────────────────────────

    @Override
    public <T> T selectOne(String statement) {
        return unwrap(() -> delegate.selectOne(statement));
    }

    @Override
    public <T> T selectOne(String statement, Object parameter) {
        return unwrap(() -> delegate.selectOne(statement, parameter));
    }

    @Override
    public <E> List<E> selectList(String statement) {
        return unwrap(() -> delegate.selectList(statement));
    }

    @Override
    public <E> List<E> selectList(String statement, Object parameter) {
        return unwrap(() -> delegate.selectList(statement, parameter));
    }

    @Override
    public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
        return unwrap(() -> delegate.selectList(statement, parameter, rowBounds));
    }

    @Override
    public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
        return unwrap(() -> delegate.selectMap(statement, mapKey));
    }

    @Override
    public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
        return unwrap(() -> delegate.selectMap(statement, parameter, mapKey));
    }

    @Override
    public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey, RowBounds rowBounds) {
        return unwrap(() -> delegate.selectMap(statement, parameter, mapKey, rowBounds));
    }

    @Override
    public <T> Cursor<T> selectCursor(String statement) {
        return unwrap(() -> delegate.selectCursor(statement));
    }

    @Override
    public <T> Cursor<T> selectCursor(String statement, Object parameter) {
        return unwrap(() -> delegate.selectCursor(statement, parameter));
    }

    @Override
    public <T> Cursor<T> selectCursor(String statement, Object parameter, RowBounds rowBounds) {
        return unwrap(() -> delegate.selectCursor(statement, parameter, rowBounds));
    }

    @Override
    public void select(String statement, Object parameter, ResultHandler handler) {
        unwrap(() -> {
            delegate.select(statement, parameter, handler);
            return null;
        });
    }

    @Override
    public void select(String statement, ResultHandler handler) {
        unwrap(() -> {
            delegate.select(statement, handler);
            return null;
        });
    }

    @Override
    public void select(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {
        unwrap(() -> {
            delegate.select(statement, parameter, rowBounds, handler);
            return null;
        });
    }

    // ── 写入 ──────────────────────────────────────────────────────────────

    @Override
    public int insert(String statement) {
        return unwrap(() -> delegate.insert(statement));
    }

    @Override
    public int insert(String statement, Object parameter) {
        return unwrap(() -> delegate.insert(statement, parameter));
    }

    @Override
    public int update(String statement) {
        return unwrap(() -> delegate.update(statement));
    }

    @Override
    public int update(String statement, Object parameter) {
        return unwrap(() -> delegate.update(statement, parameter));
    }

    @Override
    public int delete(String statement) {
        return unwrap(() -> delegate.delete(statement));
    }

    @Override
    public int delete(String statement, Object parameter) {
        return unwrap(() -> delegate.delete(statement, parameter));
    }

    @Override
    public List<BatchResult> flushStatements() {
        return unwrap(delegate::flushStatements);
    }

    // ── 事务 ──────────────────────────────────────────────────────────────

    @Override
    public void commit() {
        unwrap(() -> {
            delegate.commit();
            return null;
        });
    }

    @Override
    public void commit(boolean force) {
        unwrap(() -> {
            delegate.commit(force);
            return null;
        });
    }

    @Override
    public void rollback() {
        unwrap(() -> {
            delegate.rollback();
            return null;
        });
    }

    @Override
    public void rollback(boolean force) {
        unwrap(() -> {
            delegate.rollback(force);
            return null;
        });
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void clearCache() {
        delegate.clearCache();
    }

    // ── 元信息 ────────────────────────────────────────────────────────────

    @Override
    public Configuration getConfiguration() {
        return delegate.getConfiguration();
    }

    /**
     * <b>刻意让 mapper 绑定到「本包装器」</b>（而不是被包装的 {@code DefaultSqlSession}）。
     *
     * <p>⚠️ <b>为什么</b>：{@code DefaultSqlSession.getMapper(type)}
     * ⇒ {@code configuration.getMapper(type, this)} ⇒ mapper 代理会<b>持有</b>
     * {@code this}（原始 session）⇒ ⇒ 它后续的调用<b>不经过</b>本包装器
     * ⇒ ⇒ 解包失效 ⇒ ⇒ 必须传 {@code this}。
     */
    @Override
    public <T> T getMapper(Class<T> type) {
        return getConfiguration().getMapper(type, this);
    }

    @Override
    public Connection getConnection() {
        return unwrap(delegate::getConnection);
    }

    // ── 内部：解包 ────────────────────────────────────────────────────────

    /**
     * 执行一次 session 调用；若 MyBatis 把 {@code DEADLINE_EXCEEDED} 包进了
     * {@code PersistenceException}，则<b>把它解包回 {@link StatusRuntimeException}</b> 再抛。
     *
     * <p>⚠️ <b>为什么只解包这一种</b>：见类头「为什么只解包 DEADLINE_EXCEEDED」。
     */
    private static <T> T unwrap(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            StatusRuntimeException deadline = deadlineExceededOf(e);
            if (deadline != null) {
                throw deadline;
            }
            throw e;
        }
    }

    /**
     * 从异常链里找出 {@link Status#Code#DEADLINE_EXCEEDED} 的 {@link StatusRuntimeException}。
     *
     * <p>⚠️ <b>为什么要遍历整条 cause 链</b>：MyBatis 的 {@code ExceptionFactory.wrapException}
     * 产生的 {@code PersistenceException} 把原异常放在 <b>cause</b> 上 ⇒ ⇒
     * 只看最外层永远看不到它。
     */
    private static StatusRuntimeException deadlineExceededOf(Throwable t) {
        int guard = 0;
        for (Throwable c = t; c != null && guard++ < 16; c = c.getCause()) {
            if (c instanceof StatusRuntimeException sre
                    && sre.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                return sre;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }
}
