package com.rag.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.admin.domain.entity.AdminInviteEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code admin_invite} 表的 MyBatis-Plus Mapper。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/mapper/AdminInviteMapper.java}
 * ⇒ <b>本类为同构照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅包名 {@code rpc.mapper} → {@code mapper}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>方法真实归属（不要去本接口找）</b>：{@code findAdminInviteByCodeForUpdate}
 * 与 {@code updateAdminInviteUsedInTx} 都在
 * {@code domain/service/AdminCreateAtomicService#createAdminAtomic} 里，
 * 用 {@code LambdaQueryWrapper.last("FOR UPDATE")} 与 {@code LambdaUpdateWrapper} 实现，
 * {@code @Transactional} 挂在<b>该 Service</b> 上。
 */
@Mapper
public interface AdminInviteMapper extends BaseMapper<AdminInviteEntity> {
    // 标准 CRUD 由 BaseMapper 提供
    // 复杂方法（findAdminInviteByCodeForUpdate / updateAdminInviteUsedInTx）
    // 在 AdminCreateAtomicService.createAdminAtomic 里编排（含 SELECT ... FOR UPDATE 悲观锁）。
}
