package com.rag.common.persistence;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;

import java.sql.Connection;

/**
 * <p><b>把 {@link SqlSessionFactory} 产出的 session 换成 {@link StatusAwareSqlSession}</b>
 * ⇒ ⇒ MyBatis 包装掉的 {@code DEADLINE_EXCEEDED} 会被解包回 {@code StatusRuntimeException}
 * ⇒ ⇒ 域层看得见它 ⇒ ⇒ 落业务码 <b>1015</b>（而不是被改写成 5001）。
 *
 * <h2>为什么包在工厂这一层（而不是 mapper 代理 / SqlSessionTemplate）</h2>
 * <p>见 {@link StatusAwareSqlSession} 的类头「缝的选取」——本仓两条 mapper 调用路径
 * （常规路径 ＋ MP 的游标路径 {@code BaseMapper.selectOne(Wrapper, boolean)}）
 * <b>都</b>经过 {@link #openSession()} ⇒ ⇒ 包装工厂一处即可全覆盖。
 *
 * <h2>⚠️ 为什么 {@link #getConfiguration()} 必须原样透传</h2>
 * <p>{@code SqlSessionTemplate} 的构造器会读 {@code sqlSessionFactory.getConfiguration()}
 * 来建 {@code MyBatisExceptionTranslator}；MP 的 {@code MybatisUtils.getSqlSessionFactory}
 * 也靠 {@code Configuration} 反查工厂 ⇒ ⇒ 本类<b>不能</b>另造 Configuration。
 *
 * <p><b>三段式留痕（AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧的 gRPC 中间件原样传递 status（无包装层）
 *       ⇒ C 侧补一个等价的「不包装 status」层。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 只有「共享层自己造的 DEADLINE_EXCEEDED」被解包，
 *       其它异常<b>原样</b>抛出（不改变任何既有行为）。</li>
 *   <li><b>B 出处</b>：B 侧 {@code service/common/.../persistence/SqlTimeoutStatusTranslator.java}
 *       的类头（「ctx 到期 ⇒ 落 default ⇒ 1015」）。</li>
 * </ol>
 */
public class StatusAwareSqlSessionFactory implements SqlSessionFactory {

    private final SqlSessionFactory delegate;

    public StatusAwareSqlSessionFactory(SqlSessionFactory delegate) {
        this.delegate = delegate;
    }

    /** 被包装的原始工厂（排障用；<b>不是</b> Spring 容器里的那个 bean）。 */
    public SqlSessionFactory getDelegate() {
        return delegate;
    }

    @Override
    public SqlSession openSession() {
        return wrap(delegate.openSession());
    }

    @Override
    public SqlSession openSession(boolean autoCommit) {
        return wrap(delegate.openSession(autoCommit));
    }

    @Override
    public SqlSession openSession(Connection connection) {
        return wrap(delegate.openSession(connection));
    }

    @Override
    public SqlSession openSession(TransactionIsolationLevel level) {
        return wrap(delegate.openSession(level));
    }

    @Override
    public SqlSession openSession(ExecutorType execType) {
        return wrap(delegate.openSession(execType));
    }

    @Override
    public SqlSession openSession(ExecutorType execType, boolean autoCommit) {
        return wrap(delegate.openSession(execType, autoCommit));
    }

    @Override
    public SqlSession openSession(ExecutorType execType, TransactionIsolationLevel level) {
        return wrap(delegate.openSession(execType, level));
    }

    @Override
    public SqlSession openSession(ExecutorType execType, Connection connection) {
        return wrap(delegate.openSession(execType, connection));
    }

    @Override
    public Configuration getConfiguration() {
        return delegate.getConfiguration();
    }

    private SqlSession wrap(SqlSession session) {
        return new StatusAwareSqlSession(session, this);
    }
}
