package com.rag.like.mapper;

import com.rag.like.domain.entity.LikeFactStreamEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p><b>事实流版本号数据访问</b> —— 对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/mapper/LikeFactStreamMapper.java:17-56}
 * 的 SQL（逐条照抄）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.like.like.rpc.mapper.LikeFactStreamMapper}
 * ⇒ C 侧 {@code com.rag.like.mapper.LikeFactStreamMapper}。
 *
 * <p>本表只有 {@code (aggregate_id, version)} 两列，但它是整个事实流的<b>版本权威</b>。
 */
@Mapper
public interface LikeFactStreamMapper {

    /**
     * 对应 B 侧 {@code appendLikeFact} 的版本取号：
     *
     * <pre>
     * INSERT INTO like_fact_stream (aggregate_id, version) VALUES (?, 1)
     * ON CONFLICT (aggregate_id) DO UPDATE SET version = like_fact_stream.version + 1
     * RETURNING aggregate_id, version
     * </pre>
     *
     * <p>整条语义（并发下严格递增、无空洞、首次为 1）都由这一条 SQL 保证，
     * <b>不要</b>拆成「先 select 再 update」—— 那会在并发下丢版本号，
     * 且后续 {@code AuthoritativeFact} 的「版本必须等于行序号」校验会整条失败。
     *
     * <p><b>为什么用 {@code @Select} 而不是 {@code @Insert} 承载 INSERT…RETURNING</b>：
     * MyBatis 的 {@code @Insert} 只映射「影响行数」，拿不到 RETURNING 的结果集；
     * 而 pgjdbc 对带 RETURNING 的 INSERT 执行 {@code executeQuery()} 是合法的，
     * {@code @Select} 恰好走这条路径并把结果集映射成实体。
     *
     * <p><b>关于 MyBatis 一级缓存</b>：{@code @Select} 是查询语句，结果会进 SqlSession 级缓存，
     * 而 {@code @Select} 注解（本版本）没有 {@code flushCache} 属性可关。
     * 这里安全的原因是本方法的<b>调用顺序被调用方强制</b>：{@code appendLikeFact} 里
     * {@code bumpFactStreamVersion} 之后<b>紧接着</b>就是 {@code insertFact}（写语句），
     * 而 MyBatis 在同一 Session 上执行任何写语句都会 clearLocalCache。
     * 且更外层每条消息开始前必有 {@code insertInboxIfAbsent}。
     * ⇒ 两次 bump 之间必然隔着写语句，缓存不会给出过期版本号。
     * 若将来把本方法挪到「两次 bump 之间没有写语句」的位置，必须重新审视这一点。
     */
    @Select(value = """
            INSERT INTO like_fact_stream (aggregate_id, version) VALUES (#{aggregateId}, 1)
            ON CONFLICT (aggregate_id) DO UPDATE SET version = like_fact_stream.version + 1
            RETURNING aggregate_id, version
            """)
    LikeFactStreamEntity bumpFactStreamVersion(@Param("aggregateId") String aggregateId);
}
