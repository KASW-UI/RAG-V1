package com.rag.comment.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.rag.comment.domain.entity.CommentFactStreamEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p><b>事实流版本号数据访问</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/mapper/CommentFactStreamMapper.java:13-58}
 * （B 侧对应 Go {@code service/comment/rpc/internal/model/domain_fact.go:115-120} 的那段裸 SQL）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.mapper.CommentFactStreamMapper}
 * ⇒ C 侧 {@code com.rag.comment.mapper.CommentFactStreamMapper}。
 *
 * <p>整张表只有一个方法，因为整张表只服务一个动作：<b>在业务事务内原子取号</b>。
 */
@Mapper
public interface CommentFactStreamMapper {

    /**
     * 对应 Go {@code appendCommentFact} 的取号语句（domain_fact.go:116-118），逐字照抄：
     * <pre>
     * INSERT INTO comment_fact_stream (aggregate_id, version) VALUES (?, 1)
     * ON CONFLICT (aggregate_id) DO UPDATE SET version = comment_fact_stream.version + 1
     * RETURNING aggregate_id, version
     * </pre>
     *
     * <p><b>三条必须保留的细节：</b>
     * <ol>
     *   <li><b>{@code version = comment_fact_stream.version + 1} 的表名限定前缀</b>：
     *       PG 的 {@code ON CONFLICT DO UPDATE} 里，写裸 {@code version + 1} 会报
     *       <i>column reference is ambiguous</i>（INSERT 的 VALUES 与既有行同名）。</li>
     *   <li><b>{@code ON CONFLICT (aggregate_id)}</b> 命中 DDL 里的主键，
     *       保证并发下同一 aggregate 只会有一条行、版本号严格递增且无空洞。</li>
     *   <li><b>{@code RETURNING} 是取回新值的唯一途径</b>：不能用「插入后另查一次」代替，
     *       那会把原子性拆成两步，在并发下拿到别人的版本号。</li>
     * </ol>
     *
     * <p><b>为什么用 {@code @Select} 而不是 {@code @Insert}</b>：
     * MyBatis 的 {@code @Insert} 走 {@code PreparedStatement.executeUpdate()}，
     * 拿不到 {@code RETURNING} 的结果集；而 {@code @Select} 走 {@code executeQuery()}，
     * PG 协议层对「返回行数的语句」一视同仁，{@code INSERT … RETURNING} 同样能映射成结果集。
     * 先例：{@code like-rpc/mapper/LikeFactStreamMapper}（同款写法，本仓已在真 PG 上验证过）。
     *
     * <p>本模块的 {@code CommentFactStreamContractTest} 在真 PG 上验证这条路径。
     */
    @Select("""
            INSERT INTO comment_fact_stream (aggregate_id, version) VALUES (#{aggregateId}, 1)
            ON CONFLICT (aggregate_id) DO UPDATE SET version = comment_fact_stream.version + 1
            RETURNING aggregate_id, version
            """)
    CommentFactStreamEntity bumpVersion(@Param("aggregateId") String aggregateId);
}
