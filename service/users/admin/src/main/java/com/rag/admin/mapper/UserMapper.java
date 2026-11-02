package com.rag.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.admin.domain.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code users_user} 表的 MyBatis-Plus Mapper（admin 子域独立持有）。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/mapper/UserMapper.java}
 * ⇒ <b>本类为同构照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅包名 {@code rpc.mapper} → {@code mapper}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>分工</b>：admin 子域的 {@code UserMapper} 只写「管理员操作需要的方法」，
 * user 子域那份写「用户自助操作需要的方法」—— 两边不重复实现所有 CRUD。
 *
 * <p><b>方法真实归属</b>：分页查询与所有动态更新都在
 * {@code domain/service/AdminDomainService} 里，用
 * {@code LambdaQueryWrapper} / {@code LambdaUpdateWrapper} 实现；事务边界在 Service 上。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    // 动态更新 / 分页查询 / 单字段更新 全部在 AdminDomainService 里用
    // LambdaQueryWrapper / LambdaUpdateWrapper 实现（本接口不声明任何方法）。
    // 事务边界在 Service 层（AdminCreateAtomicService 的 @Transactional）。
}
