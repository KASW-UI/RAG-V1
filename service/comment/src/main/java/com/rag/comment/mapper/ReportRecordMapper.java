package com.rag.comment.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.rag.comment.domain.entity.ReportRecordEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * <p><b>举报记录数据访问</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/mapper/ReportRecordMapper.java:15-88}
 * （B 侧对应 Go {@code service/comment/rpc/internal/model/comment.go:242-250} 的 {@code InsertReport}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.mapper.ReportRecordMapper}
 * ⇒ C 侧 {@code com.rag.comment.mapper.ReportRecordMapper}。
 *
 * <p>Go 侧这一条<b>没有事务</b>（是 {@code comment.go:157,184,242} 三个无事务方法之一），
 * 只是一次 {@code INSERT … ON CONFLICT DO NOTHING}。
 *
 * <h2>✅ 2026-10-24 用户已签字：「{@code report_record} 表在 RAG 侧存在」是<b>有意偏离</b></h2>
 * <p><b>Go 侧根本没有这张表</b>：{@code rpc/internal/model/db.go:15} 的 {@code AutoMigrate} 列表
 * （{@code Subject / CommentContent / CommentIndex / CommentLike / CommentDomainFactOutbox /
 * CommentFactStream}）<b>不含 {@code ReportRecord}</b>，3 个手写迁移脚本也不建它
 * ⇒ Go 侧全新部署上 {@code ReportComment} <b>必然 5004</b>
 * （实测：{@code comment.go:243 ERROR: relation "report_record" does not exist}）。
 * <p>⇒ RAG 侧用 {@code V5__create_comment_report_record.sql} 建表 ⇒ <b>举报功能可用</b>。
 * <p>⇒ 签字记录：{@code docs/Intentional_Deviations/comment/rpc.md} 的
 * {@code SD-2026-10-24-CAND-02}（签字人 Jason，2026-10-24）。
 * <p>⚠️ <b>V5 迁移文件的字节不可再改</b>（连注释也不行）—— 改了会让
 * <b>已应用过 V5 的环境</b>在启动时 Flyway checksum 校验失败而直接起不来。
 * ⇒ 本类的说明写在这里（Java 文件不影响 checksum），
 * <b>不要</b>为了更新签字状态去改 V5 的注释。
 *
 * <h3>⚠️ C 侧定性</h3>
 * <p>C 侧逐字继承 B 侧已签字的偏离（{@code V5} 迁移 + 本 mapper）
 * ⇒ <b>C ≡ B</b> ⇒ 这<b>不是</b> C 侧自创的偏离，而是「继承 B 侧已签字决策」
 * ⇒ C 侧台账登记为「继承项」，不重复请求签字。
 */
@Mapper
public interface ReportRecordMapper {

    /**
     * 对应 Go {@code InsertReport}：
     * <pre>
     * err := m.conn.WithContext(ctx).Clauses(clause.OnConflict{DoNothing: true}).Create(report).Error
     * </pre>
     *
     * <p><b>⚠️ {@code ON CONFLICT DO NOTHING} 不带 Columns 子句，必须逐字保留。</b>
     * PG 在不带冲突目标时会退化为「任意唯一约束冲突即忽略」，
     * 命中的是 {@code idx_user_comment_report (user_id, comment_id)}
     * ⇒ 同一人重复举报被<b>静默吞掉</b>，而接口仍返回 {@code success = true}
     * （report_comment_logic.go:97-99）。
     *
     * <p>⚠️ 若这里补上 {@code ON CONFLICT (user_id, comment_id) DO NOTHING}，
     * 行为在当前 DDL 下<b>看起来等价</b>，但它会把「将来新增的其它唯一约束」从
     * 「静默忽略」变成「报错」—— 那是新增的可观察差异。
     *
     * <p><b>⚠️ 另一处 Go 的不对称</b>：{@code InsertReport} 只在
     * {@code err != nil} 时打点 {@code CommentPostgresErrorCounterMetric{type="insert"}}，
     * 冲突被吞掉时<b>既不报错也不打点</b>。Java 侧照此实现（返回 0 行不抛异常、不打点）。
     *
     * <p>⚠️ {@code reason} 落库为 int32 原值；proto 枚举的未知值用
     * {@code getReasonValue()} 取，{@code getReason()} 会返回 {@code null}。
     */
    @Insert("""
            INSERT INTO report_record (user_id, comment_id, target_type, target_id, reason, detail, created_at)
            VALUES (#{userId}, #{commentId}, #{targetType}, #{targetId}, #{reason}, #{detail},
                    CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """)
    int insertIgnoreConflict(@Param("userId") long userId,
                             @Param("commentId") long commentId,
                             @Param("targetType") String targetType,
                             @Param("targetId") String targetId,
                             @Param("reason") int reason,
                             @Param("detail") String detail);
}
