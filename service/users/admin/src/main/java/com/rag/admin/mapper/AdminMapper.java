package com.rag.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.admin.domain.entity.AdminEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code admin_admin} 表的 MyBatis-Plus Mapper。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/mapper/AdminMapper.java}
 * ⇒ <b>本类为同构照抄</b>（含「本接口不声明任何方法」这条刻意的形态）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅包名 {@code rpc.mapper} → {@code mapper}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。接口<b>零方法声明</b>，全部 CRUD 来自 {@code BaseMapper}。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>方法真实归属（不要去本接口找）</b>：
 * <table border="1">
 *   <caption>B 侧 DataModel 方法 → 本仓实际位置</caption>
 *   <tr><th>逻辑</th><th>本仓位置</th></tr>
 *   <tr><td>{@code findOneAdminByUsername}</td>
 *       <td>{@code domain/service/AdminDomainService} 私有方法（{@code LambdaQueryWrapper}）</td></tr>
 *   <tr><td>{@code findOneAdminByUid}</td><td>同上</td></tr>
 *   <tr><td>{@code insertAdmin}</td><td>{@code adminMapper.insert(entity)}</td></tr>
 *   <tr><td>{@code updateAdminByUid}</td><td>{@code adminMapper.update(null, wrapper)}（wrapper-only 路径）</td></tr>
 *   <tr><td>CreateAdmin 4 步事务编排</td>
 *       <td>{@code domain/service/AdminCreateAtomicService#createAdminAtomic}；{@code @Transactional} 在该 Service 上</td></tr>
 * </table>
 *
 * <p><b>⚠️ 悲观锁（{@code SELECT ... FOR UPDATE}）不在本接口</b> —— 它在
 * {@code AdminCreateAtomicService} 里用 {@code LambdaQueryWrapper.last("FOR UPDATE")} 实现。
 */
@Mapper
public interface AdminMapper extends BaseMapper<AdminEntity> {

    // 标准 CRUD 全部由 BaseMapper 提供：
    //   selectById(id)   ← 主键是 id（BIGSERIAL 自增），**不是 uid**
    //   insert(admin) / updateById(admin) / deleteById(id)
    // 业务特殊查询（findOneAdminByUsername / findOneAdminByUid 等）在 AdminDomainService 里
    // 通过 LambdaQueryWrapper 实现，不需要写在 Mapper 里。
    //
    // 注：CreateAdmin 4 步事务在 AdminCreateAtomicService.createAdminAtomic 里编排，
    //     事务注解在那个 Service 上，不在本接口上。
}
