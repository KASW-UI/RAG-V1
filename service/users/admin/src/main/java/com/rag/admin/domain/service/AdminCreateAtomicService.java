package com.rag.admin.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.domain.entity.AdminInviteEntity;
import com.rag.admin.exception.AdminExceptions.InviteUnavailableException;
import com.rag.admin.exception.AdminExceptions.UsernameTakenException;
import com.rag.admin.mapper.AdminInviteMapper;
import com.rag.admin.mapper.AdminMapper;
import com.rag.common.security.PasswordUtil;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>职责</b>：CreateAdmin 的 4 步原子事务（W1 锁 invite → W2 查 username 冲突 → bcrypt →
 * W3 insert admin → W4 标记 invite 已用）—— <b>刻意独立成 Bean</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅包名与 import 重定向 ——
 *       {@code com.rag.users.admin.rpc.service} → {@code com.rag.admin.domain.service}（D7 包压平）；
 *       {@code com.rag.users.admin.rpc.domain.*} → {@code com.rag.admin.domain.entity.*}；
 *       {@code com.rag.users.admin.rpc.mapper.*} → {@code com.rag.admin.mapper.*}；
 *       {@code com.rag.users.admin.rpc.exception.AdminExceptions.*} → {@code com.rag.admin.exception.AdminExceptions.*}；
 *       {@code com.rag.users.common.cryptx.PasswordUtil} → {@code com.rag.common.security.PasswordUtil}（D3 上提共享层）。
 *       <b>方法体、步骤序、异常类型、事务边界逐字未动。</b></li>
 *   <li><b>语义还一样吗</b>：一样。W1 的 {@code SELECT ... FOR UPDATE} 悲观锁、W2 的 username 查重、
 *       bcrypt 的<b>步骤位置（W1/W2 之后）</b>、{@link OffsetDateTime} 的绝对时刻比较、
 *       W4 的 {@code used_by_uid}/{@code used_at} + {@code update_time} 推进，全部逐条未动
 *       ⇒ <b>行为零差异</b>。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminCreateAtomicService.java}
 *       （205 行，逐字照抄）。</li>
 * </ol>
 *
 * <p><b>为什么必须独立成 Bean</b>：Spring 的 {@code @Transactional} 靠 AOP 代理生效，
 * 而代理<b>只拦截从容器外部进来的调用</b>。若这 4 步写在 {@code AdminDomainService}
 * 自己的 public 方法里、再由同类的 {@code createAdmin(...)} 直接调用（self-invocation），
 * 调用不经过代理，{@code @Transactional} 会<b>静默失效</b>——代码看着有事务，运行时是
 * auto-commit，异常不回滚。独立 Bean 后由 {@code AdminDomainService} 跨 Bean 调用，
 * 代理才会生效。
 *
 * <p>这也与参考实现一致：参考侧 {@code DataModel.createAdminAtomic} 是独立 Bean，
 * 由 {@code CreateAdminLogic} 跨 Bean 调用。
 *
 * <p><b>为什么要悲观锁</b>：邀请码的业务不变量是「一次性使用」。只靠"事务内读 used_at 再更新"
 * 在 PostgreSQL 默认的 READ COMMITTED 下仍有竞态 —— 两个并发事务可以同时读到未使用、
 * 都通过校验、都插入 admin，最后才各自更新 invite，后写者覆盖先写者。
 * 对 invite 行加 {@code SELECT ... FOR UPDATE} 后，并发调用会被串行化，
 * 第二个调用在拿到锁时会读到已被标记 used 的行并被 {@link InviteUnavailableException} 拒绝。
 */
@Service
public class AdminCreateAtomicService {

    private final AdminMapper adminMapper;
    private final AdminInviteMapper adminInviteMapper;

    public AdminCreateAtomicService(AdminMapper adminMapper, AdminInviteMapper adminInviteMapper) {
        this.adminMapper = adminMapper;
        this.adminInviteMapper = adminInviteMapper;
    }

    /**
     * 4 步原子事务：
     * <pre>
     *   W1 查 invite（SELECT ... FOR UPDATE by code）—— 不存在 / 已用 / 已过期 → InviteUnavailableException
     *   W2 查 username 冲突 —— 命中 → UsernameTakenException
     *   W3 insert admin —— 唯一冲突 → 原异常透传（由 caller 用 AdminExceptions.isUniqueViolation 识别）
     *   W4 标记 invite used
     * </pre>
     *
     * @param passwordHash <b>已哈希</b>的密码。生产路径请用
     *                     {@link #createAdminAtomic(String, String, String, String, Map, Long, boolean)}
     *                     传明文，由本类在事务内按 B 侧 步骤序哈希。
     * @return 生成的 admin uid
     */
    @Transactional
    public Long createAdminAtomic(String username, String inviteCode, String passwordHash,
                                  String email, Map<String, String> extraInfo, Long generatedUid) {
        return createAdminAtomic(username, inviteCode, passwordHash, email, extraInfo,
                generatedUid, false);
    }

    /**
     * 生产路径入口：传<b>明文</b>密码，哈希在事务内、W2 之后完成。
     *
     * <p><b>R5 修复（2026-10-22，用户签字「完全对齐 B 侧」）</b>：
     * 修复前 {@code AdminDomainService.createAdmin} 在<b>事务外</b>先做 bcrypt，
     * 于是 bcrypt 的 72 字节上限会抢在 invite / username 检查之前抛错，
     * 把本该报「邀请码错误(1016)」「用户名已存在(1001)」的请求报成 5001。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminCreateAtomicService.java}
     * 的事务闭包内步骤序为
     * <pre>
     *   FindAdminInviteByCodeForUpdate → not found/过期 → PermissionDenied
     *   FindOneAdminByUsernameTx        → 命中         → AlreadyExists
     *   cryptx.PasswordEncrypt          → &gt;72 字节    → err
     *   snowflake.GetID
     *   InsertOneAdminTx
     *   UpdateAdminInviteUsedTx
     * </pre>
     * 本方法逐条镜像该顺序：W1 → W2 → <b>bcrypt</b> → insert → 标记已用。
     *
     * <p><b>实测证据</b>（{@code tools/difftest/r5-order-probe.py}，全新 cluster）：
     * <pre>
     *   B 有效邀请码 + 超长密码 + 用户名已存在   B 侧=1001 / 修复前 RAG=5001
     *   C 无效邀请码 + 超长密码                 B 侧=1016 / 修复前 RAG=5001
     *   A 有效邀请码 + 超长密码 + 新用户名       B 侧=5001 / RAG=5001（本就一致）
     * </pre>
     *
     * <p><b>为什么哈希必须在事务内（代价已知并接受）</b>：bcrypt cost=10 约 60~100ms
     * （B 侧 用 {@code bcrypt.DefaultCost}=10，RAG 用 {@code STRENGTH}=10，同量级），
     * 会延长 invite 行 {@code SELECT ... FOR UPDATE} 的持锁时间。
     * 这是<b>刻意选择</b>：B 侧 本身就是这个顺序，而该锁只锁 invite 单行，
     * 换来的是「错误码归属」与真源逐条一致。
     *
     * <p>此前本方法的 javadoc 写着「bcrypt password（事务外，避免延长事务持有时间）」——
     * 那条注释描述的是一个<b>未经 B 出处论证</b>的优化，属 §3.2「无出处 idiomatization」，
     * 本次按用户裁决撤销。
     *
     * @param password 明文密码（仅当 {@code passwordIsPlaintext} 为 true 时才会被哈希）
     */
    @Transactional
    public Long createAdminAtomic(String username, String inviteCode, String password,
                                  String email, Map<String, String> extraInfo, Long generatedUid,
                                  boolean passwordIsPlaintext) {
        // W1 查 invite：悲观锁 SELECT ... FOR UPDATE —— 必须在事务内做，
        // 锁到本事务结束，保证同一邀请码不会被两个并发事务同时消费。
        AdminInviteEntity invite = adminInviteMapper.selectOne(
                new LambdaQueryWrapper<AdminInviteEntity>()
                        .eq(AdminInviteEntity::getCode, inviteCode)
                        .last("FOR UPDATE")
        );
        if (invite == null) {
            throw new InviteUnavailableException("invite code not found: " + inviteCode);
        }
        // 🔴 OffsetDateTime，不是 LocalDateTime（2026-10-23，用户拍板修 DRIFT-ARPC-004②）。
        // B 出处：AdminCreateAtomicService.java（admin-rpc）—— 事务闭包内
        //     now := time.Now()
        //     if invite.UsedAt != nil || !invite.ExpiresAt.After(now)
        // B 侧的 invite.ExpiresAt 来自 **timestamptz** 列 ⇒ 带 location 的绝对时刻，
        // `.After(now)` 比的是绝对时刻。
        // 原 RAG 的 LocalDateTime 版本比的是**墙钟**：单实例同区恰好自洽，
        // 跨时区多实例部署时（A 区建码、B 区消费）邀请码会刚生成就判为已过期 ⇒ 与 B 侧 行为分叉。
        OffsetDateTime now = OffsetDateTime.now();
        if (invite.getUsedAt() != null || !invite.getExpiresAt().isAfter(now)) {
            throw new InviteUnavailableException("invite code expired or used: " + inviteCode);
        }

        // W2 查 username 冲突（事务内）
        AdminEntity existing = adminMapper.selectOne(
                new LambdaQueryWrapper<AdminEntity>().eq(AdminEntity::getUsername, username)
        );
        if (existing != null) {
            throw new UsernameTakenException("admin already exists with username: " + username);
        }

        // R5（2026-10-22，用户签字「完全对齐 B 侧」）：bcrypt 必须在 W1/W2 之后。
        //
        // 两端 bcrypt 实现相同（B 侧 bcrypt.DefaultCost=10 / RAG PasswordUtil STRENGTH=10），
        // 都拒 >72 字节的密码；分歧<b>纯粹来自步骤顺序</b>：
        // 修复前 RAG 在事务外先哈希 → 72 字节上限抢在 invite / username 检查之前抛错，
        // 把本该报 1016 / 1001 的请求报成 5001。
        // B 出处：AdminCreateAtomicService.java（admin-rpc）事务闭包内 PasswordEncrypt 在
        //           FindAdminInviteByCodeForUpdate 与 FindOneAdminByUsernameTx 之后。
        String effectiveHash = password;
        if (passwordIsPlaintext) {
            try {
                effectiveHash = PasswordUtil.encrypt(password);
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛，不能被改写成 INTERNAL。
                //       （客户端可见值两侧都是 1015；**理由**见 AdminDomainService 类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
                throw e;
            } catch (Exception e) {
                // bcrypt 对 >72 字节抛 IllegalArgumentException。
                // B 侧这里同样是裸 err → 由 CreateAdminLogic 外层兜成 Internal(1015)，
                // 这里保持同一归属：冒泡给 AdminDomainService 的 db_error 分支。
                throw e;
            }
        }

        // W3 插入 admin（事务内）
        AdminEntity admin = new AdminEntity();
        admin.setUid(generatedUid);
        admin.setUsername(username);
        admin.setPassword(effectiveHash);
        admin.setEmail(email);   // nullable，null 表示不填
        if (extraInfo != null && !extraInfo.isEmpty()) {
            admin.setExtraInfo(new HashMap<>(extraInfo));
        }
        adminMapper.insert(admin);

        // W4 标记 invite used（事务内）
        LambdaUpdateWrapper<AdminInviteEntity> inviteWrapper = new LambdaUpdateWrapper<>();
        inviteWrapper.eq(AdminInviteEntity::getId, invite.getId())
                .set(AdminInviteEntity::getUsedByUid, generatedUid)
                .set(AdminInviteEntity::getUsedAt, now)
                // 🔴 2026-10-23「台账 vs 实际」复核新发现并补上（此前台账与代码都漏了这条 UPDATE）：
                //   B 出处：admin-rpc 的 admin.go:141-146
                //     tx.Model(&AdminInvite{}).Where("id = ?", id).Updates(map[string]any{
                //         "used_by_uid": …, "used_at": … })
                //   **消费点**（不是看 struct tag）：gorm.io/gorm@v1.31.1/callbacks/update.go:235-255
                //     `if !stmt.SkipHooks && stmt.Schema != nil { for _, dbName := range … {
                //          if field.AutoUpdateTime > 0 && value[…] == nil { … set = append(set, …now) } } }`
                //     该段位于 map 更新分支内（`default:` 结构体分支**之前**），
                //     且本调用无 Select ⇒ selectColumns 空、restricted=false ⇒ 条件成立。
                //   ⇒ B 侧 用 **map** 更新时**同样会补写 update_time**，不是只有结构体更新才推进。
                // ⚠️ 此前 AdminDomainService 的注释与 DRIFT-ARPC-001 都写「5 处 UPDATE 入口」，
                //   那是**只数了 AdminDomainService 自己**、漏了本类这条。
                //   正确的总数是 **6 处**（AdminDomainService 5 + 本类 1）。
                //   可观察性：`admin_invite.update_time` 不在 admin.proto 的任何 message 里、
                //   业务代码也从不读它 ⇒ 补它**不改变任何可观察行为**，
                //   属「让 DDL 行为与 B 侧 同构」的纯对齐（台账 §9 复核记录）。
                .setSql("update_time = CURRENT_TIMESTAMP");
        adminInviteMapper.update(null, inviteWrapper);

        return generatedUid;
    }
}
