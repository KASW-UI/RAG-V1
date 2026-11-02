package com.rag.follow.service;

import com.rag.follow.mapper.BlockRelationMapper;
import com.rag.follow.mapper.FollowRelationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * <p><b>follow 域唯一一笔多语句事务</b> —— 对应 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/service/FollowBlockTransactionService.java:43-78}
 * ⇒ <b>逐条一致</b>（类形态 / 依赖 / 方法名 / 语句顺序 / 时间戳来源 / 异常处理）。
 *
 * <p><b>B 出处</b>：B 侧 {@code FollowBlockTransactionService.java:43-78}；
 * 对应 Go 端 {@code service/follow/rpc/internal/model/follow.go:33-54} 的 {@code CreateBlockAndCleanup}：
 * <pre>
 * m.conn.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
 *     if err := tx.Clauses(clause.OnConflict{Columns: [user_id,target_id], DoNothing: true}).
 *         Create(&amp;BlockRelation{UserId: userID, TargetId: targetID}).Error; err != nil { return err }
 *     return tx.Where("(user_id=? AND target_id=?) OR (user_id=? AND target_id=?)",
 *                     userID, targetID, targetID, userID).Delete(&amp;FollowRelation{}).Error
 * })
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>表达层</b> —— 包路径压平
 *       （B 侧 {@code com.rag.follow.follow.rpc.service} ⇒ 本仓 {@code com.rag.follow.service}，
 *       D7 域内单层包树，见 {@code docs/File_Tree/follow.md}）＋ 补 B 出处行号。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 方法体逐行相同（同一顺序、同一个 {@code now}、同样不吞异常），
 *       {@code @Transactional(rollbackFor = Exception.class)} 与 B 侧逐字一致。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么必须独立成 Bean（AGENTS.md §3.3 的典型形态）</h2>
 *
 * <p>Spring 的 {@code @Transactional} 靠 <b>AOP 代理</b>生效，代理<b>只拦截从容器外部进来的调用</b>。
 * 若把本方法挂在 {@link com.rag.follow.domain.service.FollowDomainService} 本类上、再由同类私有方法用
 * {@code this.} 调用，调用不经过代理 ⇒ 事务<b>静默失效</b>：代码看着有事务，运行时是 auto-commit，
 * 异常不回滚，<b>且不报任何错</b>。测试也会全绿。
 *
 * <p>本仓库已两次为此付出代价：{@code users/admin/admin-rpc} 的
 * {@code AdminCreateAtomicService}，以及 {@code message-rpc} 的 {@code MessageAtomicService}
 * （DRIFT-MSG-001，6 个方法全是 self-invocation ⇒ 事务全无）。
 *
 * <p><b>为什么这里确实需要事务</b>：Block 要同时「建立拉黑关系」与「清理双向关注」。
 * 若第二条 DELETE 失败而第一条已提交，用户会处在一个<b>既没拉黑、又还关注着</b>的中间态 ——
 * 而 {@code Follow} 的第一道闸门正是 {@code ExistsAnyBlock}。
 * 这条不变量必须靠事务保。
 *
 * <h2>⚠️ 语句顺序与 GORM 一致，不可调换</h2>
 * <p>Go 是「先 INSERT block、后 DELETE follow」。反向排列在单线程下结果相同，
 * 但<b>加锁顺序相反</b> ⇒ 并发 Block 同一对用户时是潜在死锁来源。照 Go 的顺序。
 */
@Service
public class FollowBlockTransactionService {

    private final BlockRelationMapper blockRelationMapper;
    private final FollowRelationMapper followRelationMapper;

    public FollowBlockTransactionService(BlockRelationMapper blockRelationMapper,
                                        FollowRelationMapper followRelationMapper) {
        this.blockRelationMapper = blockRelationMapper;
        this.followRelationMapper = followRelationMapper;
    }

    /**
     * 建立拉黑关系并清理双向关注关系（单事务）。
     *
     * <p><b>B 出处</b>：B 侧 {@code FollowBlockTransactionService.java:70-77}（方法体逐行一致）。
     *
     * <p><b>幂等</b>：重复拉黑时第一条走 {@code ON CONFLICT DO NOTHING}（0 行、不报错），
     * 第二条照常执行 —— 与 Go 完全一致：Go 的第二条 DELETE <b>不在</b>「冲突就不执行」的分支里。
     *
     * <p><b>时间戳</b>：{@code create_time} / {@code update_time} 在这里赋值，
     * 对应 Go 的 {@code autoCreateTime} / {@code autoUpdateTime} struct tag ——
     * 时间由<b>应用进程</b>产生，不是 DB 默认值。
     * ⚠️ 两张表的时间戳在<b>同一次调用里取同一个 {@code now}</b>，Go 侧也是（GORM 逐条 fill，
     * 但同一事务内两次 Create 相隔微秒级；这里取同一值更贴近「同一时刻」的语义，且无副作用差异）。
     *
     * <p><b>⚠️ 不吞异常</b>：任何一条语句抛异常都向上传播 ⇒ Spring 回滚 ⇒
     * 调用方 {@code FollowDomainService} 把它翻成 {@code INTERNAL + 5008}。
     * 若这里 catch 掉再返回 false，事务会<b>提交</b>半截状态 —— 那是静默的数据损坏。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createBlockAndCleanup(long userId, long targetId) {
        OffsetDateTime now = OffsetDateTime.now();

        blockRelationMapper.insertIgnoreConflict(userId, targetId, now, now);

        followRelationMapper.deleteFollowBothDirections(userId, targetId);
    }
}
