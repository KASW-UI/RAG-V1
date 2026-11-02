package com.rag.admin.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.domain.entity.AdminInviteEntity;
import com.rag.admin.domain.entity.UserEntity;
import com.rag.admin.exception.AdminExceptions;
import com.rag.admin.exception.AdminExceptions.InviteUnavailableException;
import com.rag.admin.exception.AdminExceptions.NotFoundException;
import com.rag.admin.exception.AdminExceptions.UsernameTakenException;
import com.rag.admin.mapper.AdminInviteMapper;
import com.rag.admin.mapper.AdminMapper;
import com.rag.admin.mapper.UserMapper;
import com.rag.admin.metrics.AdminRpcMetrics;
import com.rag.admin.service.AdminHelpers;
import com.rag.admin.service.AdminSpans;
import com.rag.admin.config.AdminRpcProperties;
import com.rag.common.observability.DeadlineState;
import com.rag.common.security.PasswordUtil;
import com.rag.common.snowflake.Snowflake;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * <p><b>职责</b>：admin 域的<b>领域服务</b> —— 13 个用例（login / logout / getSelf / updateSelf /
 * createAdmin / createInviteCode / banUser / unBanUser / getUser / getUserList / updateUser /
 * deleteUser / resetUserPassword）合 1 个 {@code @Service}。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>包名与 import 重定向</b>，方法体逐字未动 ——
 *       ① {@code com.rag.users.admin.rpc.service} → {@code com.rag.admin.domain.service}（D7 包压平）；
 *       ② {@code com.rag.users.admin.rpc.domain.*} → {@code com.rag.admin.domain.entity.*}；
 *       ③ {@code com.rag.users.admin.rpc.mapper.*} → {@code com.rag.admin.mapper.*}；
 *       ④ {@code com.rag.users.admin.rpc.exception.*} → {@code com.rag.admin.exception.*}；
 *       ⑤ {@code com.rag.users.admin.rpc.metrics.*} → {@code com.rag.admin.metrics.*}；
 *       ⑥ {@code com.rag.users.admin.rpc.config.*} → {@code com.rag.admin.config.*}；
 *       ⑦ {@code com.rag.users.common.cryptx.PasswordUtil} → {@code com.rag.common.security.PasswordUtil}（D3 上提共享层）；
 *       ⑧ {@code com.rag.common.observability.RpcInterceptor.DeadlineState} → {@code com.rag.common.observability.DeadlineState}（嵌套类提为顶层类）；
 *       ⑨ {@code com.rag.common.snowflake.Snowflake} → 同名同包（路径未变）；
 *       ⑩ {@code AdminHelpers} / {@code AdminSpans} 由 B 侧的<b>同包</b>改为 {@code com.rag.admin.service.*}（D7）。
 *       <b>无任何行为性改写。</b></li>
 *   <li><b>语义还一样吗</b>：一样。控制流、异常类型、错误码、SQL 片段、指标埋点、span 名与属性
 *       全部逐条未动 ⇒ <b>行为零差异</b>。其中「业务层仍抛 gRPC 的 {@link StatusRuntimeException}」
 *       是 <b>D-A 已拍板</b>的决策（见下方「B-1 放行分支」段与
 *       {@code docs/Mapping_Table/users/admin/admin.md}）：status code + description 是
 *       gRPC wire 上可见的 L1 面，改抛 {@code BusinessException} 会被共享层改写成
 *       {@code UNKNOWN(2)} ⇒ <b>那才是漂移</b>。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
 *       （1373 行，本类<b>逐字照抄</b>）。
 *       ⚠️ 下文各方法 javadoc 里出现的「B 出处：…*.go」是 <b>B 侧自带的考古注记</b>，
 *       逐字保留 —— 它们记录了 B 侧当初据以对齐的原始出处；
 *       按 D16（本仓判定基准 = B 侧唯一真理之源）本仓<b>不再回溯第三方</b>，只继承 B 侧结论。</li>
 * </ol>
 *
 * <p>对应参考实现的 13 个 Logic 文件（合并为 1 个 {@code @Service}）：
 * <pre>
 *   loginlogic.go + logoutlogic.go + getselflogic.go + updateselflogic.go +
 *   createadminlogic.go + createinvitecodelogic.go + banuserlogic.go + unbanuserlogic.go +
 *   getuserlogic.go + getuserlistlogic.go + updateuserlogic.go + deleteuserlogic.go +
 *   resetuserpasswordlogic.go
 * </pre>
 *
 * <p><b>L4 实现机制差异（与参考侧 Logic + DataModel 两层结构对比）</b>：
 * <ul>
 *   <li>参考侧用 13 个 Logic 类 + 1 个 DataModel + 1 个 ServiceContext 装配类（共 15 文件）</li>
 *   <li>Java 用 1 个 AdminDomainService（含 13 业务方法）+ 1 个 AdminCreateAtomicService
 *       （CreateAdmin 的 4 步原子事务，<b>必须独立成 Bean</b>，否则 @Transactional
 *       因 self-invocation 静默失效）+ 3 个 Mapper（BaseMapper 标准 CRUD）+ 直接注入依赖（无 ServiceContext）</li>
 * </ul>
 *
 * <p>业务错误统一抛 {@link StatusRuntimeException}；HTTP 边界由
 * {@code advice/AdminApiExceptionHandler} 逐字照抄参考侧的 {@code mapRPCStatusToCode}
 * 做 status → 业务码映射。
 *
 * <p><b>🔴 B-1 放行分支：为什么 {@code catch (StatusRuntimeException e) { throw e; }} 必须排在宽 catch 之前
 * —— 完整论证（2026-10-26 订正<b>理由</b>，<b>行为零改动</b>）</b>
 *
 * <p><b>① 结论（2026-10-31 订正）</b>：客户端可见的 HTTP 业务码<b>按集合读</b> —— 参考侧集合 = {1015, 5001}
 * （活体实测 200000 轮）⇒ ⇒ <b>不再读成「两侧都是 1015」</b>。
 *
 * <p><b>② 但「参考侧的 logic 返回 1015」这个理由是错的</b>（2026-10-26 之前各站点注释里写的说法，已订正）：
 * <ul>
 *   <li><b>参考侧</b>：{@code service/user/admin/rpc/internal/logic/} 里<b>每一条 DB 错误都被改写成</b>
 *       {@code status.Error(codes.Internal, "DB查询失败")} ⇒
 *       <b>参考侧的 logic 层根本不返回 1015</b>
 *       （本域 <b>20 处</b> DB 错误出口，已逐一核过行号与文案）。</li>
 *   <li><b>1015 实际从哪来</b>：{@code service/common/observability/rpc.go} 那个 select ——
 *       {@code case <-done:}（handler 先返回，<b>要</b> {@code lock.Lock()}）
 *       <b>vs</b> {@code case <-timeoutCtx.Done():}（<b>不需要锁</b>）⇒
 *       <b>ctx 一取消，超时分支立刻就绪</b> ⇒ select 几乎总走超时分支
 *       ⇒ 客户端拿到 {@code DEADLINE_EXCEEDED} ⇒ admin-api 落
 *       {@code mapRPCStatusToCode} 的 <b>default</b> ⇒ {@code CODE_SERVER_BUSY = 1015}。</li>
 *   <li><b>RAG 侧</b>：{@code SqlTimeoutStatusTranslator} 把 PG 的 57014 {@code query_canceled}
 *       翻成 {@code DEADLINE_EXCEEDED}（{@code io.grpc.StatusRuntimeException}）
 *       ⇒ 被本类这些放行分支原样上抛 ⇒ admin-api 同样落 default ⇒ <b>1015</b>。</li>
 * </ul>
 *
 * <p><b>③ 反向风险（放行分支存在的真正理由）</b>：
 * 若照参考侧的 logic 把这些宽 catch 改成 {@code codes.Internal}，
 * RAG 就会渲染 <b>5001</b>（语义 =「数据库故障」），
 * 而参考侧在同一场景给的是 <b>1015</b>（语义 =「超时 / 服务繁忙」）
 * ⇒ ⇒ <b>那是离真源更远，不是更近</b>。
 *
 * <p><b>④ 残留（不声称 100%）</b>：参考侧那个 select 是<b>竞速</b> ⇒ 机制上存在
 * 「handler 先返回 ⇒ 客户端拿到 5001」的窗口；
 * E-4 的 4 组采样全命中 1015，但那是 <b>4 次采样、不是概率刻画</b>
 * ⇒ ⇒ <b>不声称「参考侧 100% 是 1015」</b>。
 *
 * <p><b>⑤ 分类</b>：源码层形态两侧不同（参考侧改写成 INTERNAL / RAG 保留 DEADLINE_EXCEEDED），
 * 但 <b>客户端可见面一致</b> ⇒ ⇒ <b>不是 wire 级漂移</b>
 * ⇒ 归「<b>表述差异</b>」类（<b>不是</b>「有意偏离」⇒ <b>不需要用户签字</b>）
 * ⇒ 但仍<b>留档防回退</b> ⇒ 见台账 {@code DRIFT-ARPC-011}。
 */
@Service
public class AdminDomainService {

    private static final Logger log = LoggerFactory.getLogger(AdminDomainService.class);

    /** 邀请码有效期 10 分钟，与参考侧 inviteCodeTTL 常量一致 */
    private static final Duration INVITE_CODE_TTL = Duration.ofMinutes(10);

    /** 最大重试次数，与参考侧 inviteCodeMaxRetry 常量一致 */
    private static final int INVITE_CODE_MAX_RETRY = 3;

    /** 邀请码前缀，与参考侧 "ADM-" 字面量一致 */
    private static final String INVITE_CODE_PREFIX = "ADM-";

    /** 单次随机字节数（8 字节 → 16 字符 hex） */
    private static final int INVITE_CODE_RANDOM_BYTES = 8;

    /** 静态 SecureRandom 实例（线程安全），替代参考侧 crypto/rand.Reader */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Redis blacklist key 字面量前缀（D-006：必须与 user-rpc 的 "user:jwt_blacklist:" 区分） */
    private static final String BLACKLIST_KEY_PREFIX = "admin:jwt_blacklist:";

    /** 默认 admin 用户名（与参考侧 svc/servicecontext.go 硬编码常量 defaultAdminUsername 一致） */
    public static final String DEFAULT_ADMIN_USERNAME = "admin";

    /**
     * 默认 admin 密码 —— 对应参考侧 {@code svc/servicecontext.go:18} 的包内常量
     * {@code defaultAdminPassword = "admin"}。
     *
     * <p><b>不是</b> {@code AdminRpcProperties.System.DefaultPassword}：参考侧该配置项
     * （yaml 里 {@code "123456"}）只供 {@code ResetUserPasswordLogic} 重置<i>普通用户</i>密码，
     * 与本常量无关。DRIFT-001 即由此处误接配置引发。
     */
    public static final String DEFAULT_ADMIN_PASSWORD = "admin";

    private final AdminMapper adminMapper;
    private final AdminInviteMapper adminInviteMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final AdminRpcProperties properties;
    private final AdminRpcMetrics metrics;
    /** CreateAdmin 的 4 步原子事务，独立 Bean 以保证 @Transactional 代理生效（见该类注释）。 */
    private final AdminCreateAtomicService createAtomicService;

    public AdminDomainService(AdminMapper adminMapper,
                              AdminInviteMapper adminInviteMapper,
                              UserMapper userMapper,
                              StringRedisTemplate stringRedisTemplate,
                              AdminRpcProperties properties,
                              AdminRpcMetrics metrics,
                              AdminCreateAtomicService createAtomicService) {
        this.adminMapper = adminMapper;
        this.adminInviteMapper = adminInviteMapper;
        this.userMapper = userMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.metrics = metrics;
        this.createAtomicService = createAtomicService;
    }

    /**
     * DRIFT-018 对齐：admin-rpc 侧此前【零处】调用，而用户域已有 11 处
     * （2026-10-21 补齐，见 AGENTS.md §3.4.1 B-1）。
     *
     * <p>B 出处：每个 Logic 入口处的 {@code ctx.Done()} 判定
     * （如 user 域 loginlogic.go 在 W1/W2 之间检查）。
     *
     * <p><b>能做 / 不能做</b>（E-4 实测后必须分清，否则会高估它的作用）：
     * <ul>
     *   <li><b>能做</b>：在<b>发起</b> DB / Redis 调用之前感知 deadline 已到、提前退出，
     *       不让请求继续往下跑。</li>
     *   <li><b>不能做</b>：取消<b>在途</b> IO。E-4 实测
     *       （{@code tools/difftest/exp-b1-timeout-cancel.sh}，2026-10-21）显示
     *       RAG 侧客户端已返回超时后，PG 里的阻塞查询<b>还存活了 4455ms</b>，
     *       而参考侧只有 378ms —— JDBC 驱动不响应线程 interrupt，本方法对此无能为力。</li>
     * </ul>
     *
     * <p>未注册 deadline 时为 no-op（业务不受影响）。
     */
    private void checkDeadline() {
        if (Thread.currentThread().isInterrupted() || DeadlineState.isExpired()) {
            throw Status.DEADLINE_EXCEEDED
                    .withDescription("rpc deadline reached")
                    .asRuntimeException();
        }
    }

    // ============== 1. Login ==============

    /**
     * 对应参考侧 internal/logic/admin/loginlogic.go 的 LoginLogic.Login。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（4 状态机）：
     * <ol>
     *   <li>FindOneAdminByUsername → NotFoundException / Exception / 命中</li>
     *   <li>PasswordUtil.check(admin.Password, in.Password) → wrong_answer / 命中</li>
     *   <li>metrics loginSuccess + LoginResp{Uid=admin.Uid}</li>
     * </ol>
     */
    public Long login(String username, String password) {
        // 🔴 DRIFT-ARPC-006：参考侧 loginlogic.go 在方法开头 otel.Tracer("admin-rpc").Start(ctx,"Action-Admin-Login")
        //    并打 audit.admin_username = in.Username（**原始入参，未 trim**）
        return AdminSpans.traced(AdminSpans.LOGIN,
                span -> span.setAttribute(AdminSpans.ATTR_ADMIN_USERNAME, username),
                () -> doLogin(username, password));
    }

    private Long doLogin(String username, String password) {
        checkDeadline();
        AdminEntity admin;
        try {
            admin = findOneAdminByUsername(username);
        } catch (NotFoundException e) {
            metrics.getLoginUserNotFound().increment();
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getLoginDbError().increment();
            log.error("admin login DB error, username={}", username, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        // DRIFT-004（已修，2026-10-21 用户签字）：参考侧的 cryptx.CheckPassword 对任何 bcrypt
        // 错误都返回 false，即「hash 格式非法」与「密码错」都表现为 UNAUTHENTICATED。
        // 本实现对外行为与之逐字一致（不冒泡成 500），但额外打一条 error 日志留信号 ——
        // admin 侧没有 span 上下文（见本类字段：只有 log + metrics），故只落日志。
        // 见 shared 层 PasswordUtil#checkDetailed 的实测行为谱。
        PasswordUtil.Outcome pwOutcome =
                PasswordUtil.checkDetailed(password, admin.getPassword());
        if (pwOutcome != PasswordUtil.Outcome.MATCHED) {
            if (pwOutcome == PasswordUtil.Outcome.MALFORMED_HASH) {
                log.error("admin login FAILED due to malformed stored bcrypt hash, "
                        + "username={} —— 报密码错以对齐参考侧，但这条数据需要修", username);
            }
            metrics.getLoginWrongAnswer().increment();
            throw Status.UNAUTHENTICATED.withDescription("密码错误").asRuntimeException();
        }

        metrics.getLoginSuccess().increment();
        log.info("admin login success, username={}", username);
        return admin.getUid();
    }

    // ============== 2. Logout ==============

    /**
     * 对应参考侧 internal/logic/admin/logoutlogic.go 的 LogoutLogic.Logout。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（3 状态机）：
     * <ol>
     *   <li>校验 token 非空 → INVALID_ARGUMENT "Token不能为空"</li>
     *   <li>Redis Setex("admin:jwt_blacklist:%s", "banned", AdminAuth.AccessExpire)</li>
     *   <li>成功 → LogoutResp（Success 字段保持 proto3 默认 false）</li>
     * </ol>
     *
     * <p>D-007：TTL 必须等于 JWT 过期秒数，不能写死 24h。
     *
     * <p><b>L4 期限感知上移到共享层（2026-10-30 订正 · 域内入口闸门已撤）</b>：
     * <ul>
     *   <li><b>参考侧出处</b>：{@code service/user/admin/rpc/internal/logic/logoutlogic.go}
     *       （<b>全文 46 行</b>）—— <b>没有 ctx 预检</b>；{@code :38}
     *       {@code SetexCtx(l.ctx, …)} <b>写绑 ctx</b>；{@code :39-42} 写失败 ⇒
     *       {@code status.Error(codes.Internal, "注销失败,Redis写入异常")}
     *       ⇒ <b>handler 级码 = INTERNAL(5001)</b>。</li>
     *   <li><b>本实现</b>：<b>入口不设 deadline 闸门</b> ⇒ 期限耗尽时请求<b>照样走到 Redis 写</b>
     *       ⇒ 与参考侧同形（参考侧也不是在入口拒，而是让写去失败）。</li>
     *   <li><b>期限由谁感知</b>：共享层（{@code service/common}）的 Redis 期限代理
     *       （{@code GoStyleRedisDeadlineConnection} ⇒ {@code GoStyleRedisDeadline.assertNotExhausted}
     *       抛 {@code org.springframework.dao.QueryTimeoutException}）
     *       ⇒ 该异常<b>不是</b> grpc 的 {@code StatusRuntimeException}
     *       ⇒ 落进下面的 {@code catch (Exception e)} ⇒ {@code INTERNAL}「注销失败,Redis写入异常」
     *       ⇒ <b>与参考侧的 handler 级码逐字同形</b>。</li>
     *   <li><b>客户端可见层不变</b>：期限耗尽时由服务端拦截器的超时调度器<b>先</b> close
     *       ⇒ 客户端仍是 {@code DEADLINE_EXCEEDED}(1015) ⇒ 与参考侧的
     *       {@code service/common/observability/rpc.go:59-72} 的 select 同形。</li>
     * </ul>
     *
     * <p>🔴 <b>合并约束（必须与共享层代理同一个提交）</b>：若共享层代理<b>未同时</b>在位，
     * 本方法就<b>没有任何</b>期限感知 ⇒ 期限已耗尽时<b>仍会把黑名单写进 Redis</b>
     * ⇒ <b>RAG 写、参考侧不写</b> ⇒ 构成 {@code E66} 的暴露面 ⇒ ⇒ <b>禁止单独合入主干</b>。
     */
    public boolean logout(String token) {
        if (token == null || token.isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("Token不能为空").asRuntimeException();
        }

        String key = BLACKLIST_KEY_PREFIX + token;
        long ttlSeconds = properties.getAdminAuth().getAccessExpire();
        try {
            stringRedisTemplate.opsForValue().set(key, "banned", Duration.ofSeconds(ttlSeconds));
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码**集合** = {1015, 5001}（参考侧探针活体实测 200000 轮）；本域删掉抢先 checkDeadline() 后 RAG 同集合 ⇒ 同形；⚠️ RAG 侧为机制推导，尚未做双侧活体对拍）
            // ⚠️ 2026-10-30：本 catch **只**拦 grpc 的 StatusRuntimeException（PG 57014 走
            //   SqlTimeoutStatusTranslator 翻成 DEADLINE_EXCEEDED 的那条路）；共享层 Redis 期限代理
            //   抛的是 **org.springframework.dao.QueryTimeoutException**（Spring 的，不是 grpc 的）
            //   ⇒ 它**不**被本分支拦 ⇒ 落进下面的 catch (Exception) ⇒ INTERNAL「注销失败,Redis写入异常」
            //   ⇒ 与参考侧的 codes.Internal 逐字同形（**这是有意的顺序，不是漏网**）。
            throw e;
        } catch (Exception e) {
            log.error("admin logout Redis setex failed", e);
            throw Status.INTERNAL.withDescription("注销失败,Redis写入异常").asRuntimeException();
        }
        return true;
    }

    // ============== 3. GetSelf ==============

    /**
     * 对应参考侧 internal/logic/admin/getselflogic.go 的 GetSelfLogic.GetSelf。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（2 状态机）：
     * <ol>
     *   <li>findOneAdminByUid → NotFoundException / Exception</li>
     *   <li>构造 AdminInfo{uid, username, email=stringValue(admin.Email), extra_info}</li>
     * </ol>
     */
    public AdminEntity getSelf(long uid) {
        checkDeadline();
        AdminEntity admin;
        try {
            admin = findOneAdminByUid(uid);
        } catch (NotFoundException e) {
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            log.error("admin getSelf DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }
        return admin;
    }

    // ============== 4. UpdateSelf ==============

    /**
     * 对应参考侧 internal/logic/admin/updateselflogic.go 的 UpdateSelfLogic.UpdateSelf。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（5 状态机）：
     * <ol>
     *   <li>findOneAdminByUid → NotFound / Internal</li>
     *   <li>if username 非空 → 查 username 冲突（exist.Uid != self.Uid → AlreadyExists）</li>
     *   <li>构造 toUpdate（跳零值；email nullable 用 optionalString）</li>
     *   <li>updateAdminByUid（LambdaUpdateWrapper 动态 set）</li>
     *   <li>重读 admin</li>
     * </ol>
     */
    public AdminEntity updateSelf(long uid,
                                  String username,
                                  String password,
                                  String email,
                                  Map<String, String> extraInfo) {
        checkDeadline();
        AdminEntity self;
        try {
            self = findOneAdminByUid(uid);
        } catch (NotFoundException e) {
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            log.error("admin updateSelf findOneAdminByUid DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        // username 冲突检查（仅在客户端显式传 username 时）
        if (username != null && !username.isEmpty()) {
            try {
                AdminEntity existing = findOneAdminByUsername(username);
                if (existing != null && !existing.getUid().equals(uid)) {
                    throw Status.ALREADY_EXISTS.withDescription("该用户名已被使用").asRuntimeException();
                }
            } catch (NotFoundException ignored) {
                // 未被占用，名字可用
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
                //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
                throw e;
            } catch (Exception e) {
                log.error("admin updateSelf findOneAdminByUsername DB error", e);
                throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
            }
        }

        // 动态 set：username / password / email / extra_info 任一非空才 set
        LambdaUpdateWrapper<AdminEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AdminEntity::getUid, uid);
        if (username != null && !username.isEmpty()) {
            wrapper.set(AdminEntity::getUsername, username);
        }
        if (password != null && !password.isEmpty()) {
            try {
                wrapper.set(AdminEntity::getPassword, PasswordUtil.encrypt(password));
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
                //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
                throw e;
            } catch (Exception e) {
                log.error("admin updateSelf PasswordUtil.encrypt failed", e);
                throw Status.INTERNAL.withDescription("密码加密失败").asRuntimeException();
            }
        }
        if (email != null && !email.trim().isEmpty()) {
            // UpdateSelf 是部分更新：proto3 未设置 email 时线上的默认值是空串 "",
            // 直接 wrapper.set(..., null) 会把列写成 SQL NULL —— 管理员只改密码就会
            // 静默丢掉邮箱。与参考侧 UpdateSelfLogic 的 `if (!req.getEmail().isEmpty())`
            // 以及本类 updateUser（`email != null && !email.isEmpty()`）保持一致：
            // 空串 / 纯空白一律视为"本次不修改该字段"。
            wrapper.set(AdminEntity::getEmail, email.trim());
        }
        if (extraInfo != null && !extraInfo.isEmpty()) {
            // 不能用 wrapper.set(AdminEntity::getExtraInfo, copy)：
            //   LambdaUpdateWrapper 的参数以 Object 形式塞进 ew.paramNameValuePairs，
            //   不走字段上的 @TableField(typeHandler=...)，MyBatis 只能按 javaType=Object
            //   猜 handler ⇒ 结果不可控。
            // 改为直接写 SQL 片段 + 参数按 String 绑定。
            //
            // 🔴 2026-10-23：**去掉了 CAST(... AS jsonb)**（DRIFT-ARPC-004①）。
            //   admin_admin.extra_info 已由 jsonb 改为 **text**（对齐参考侧的 serializer:json），
            //   显式 CAST 成 jsonb 会把 text 强制转回 jsonb、重新引入 jsonb 那套校验 ——
            //   而参考侧根本没有这层校验（实测：含 U+0000 的 extra_info，
            //   参考侧落库成功 / RAG 被 jsonb 以 22P05 拒收 —— ⚠️ 是 22P05
            //   （json_bad_unicode_escape），**不是** 22P02；2026-10-25 真 PG 16 实测订正）。
            //   ⚠️ 2026-10-25 订正：同文件 updateUser 里那句「CAST 必须保留」**也已作废** ——
            //   users_user.extra_info **同样是 text**（01_create_user.sql:41 + :103）
            //   ⇒ 现在**两个调用点都没有 CAST**。
            wrapper.setSql("extra_info = {0}", toJson(extraInfo));
        }
        // DRIFT-ADMIN-RPC-003：对齐 GORM autoUpdateTime（见本类末尾 updateTimeAdvance 注释块）
        wrapper.setSql("update_time = CURRENT_TIMESTAMP");

        try {
            adminMapper.update(null, wrapper);
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            log.error("admin updateSelf update DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
        }

        // 重读
        AdminEntity newAdmin;
        try {
            newAdmin = findOneAdminByUid(uid);
        } catch (NotFoundException e) {
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            log.error("admin updateSelf re-findOneAdminByUid DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }
        return newAdmin;
    }

    // ============== 5. CreateAdmin ==============

    /**
     * 对应参考侧 service/user/admin/rpc/internal/logic/createadminlogic.go 的 CreateAdminLogic.CreateAdmin。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（5 状态机）：
     * <ol>
     *   <li>校验 inviteCode 非空 → PERMISSION_DENIED "管理员邀请码错误"</li>
     *   <li>trim username + inviteCode</li>
     *   <li>bcrypt password（事务内）+ Snowflake uid（事务外）</li>
     *   <li>DataModel.createAdminAtomic(...) 4 步事务（W1 查 invite 悲观锁 / W2 校验 invite 未用未过期 /
     *       W3 查 username 冲突 / <b>bcrypt 哈希（R5：按参考侧步骤序排在 W2 之后）</b> /
     *       W4 insert admin / W5 标记 invite used）</li>
     *   <li>错误映射：
     *      - InviteUnavailableException → PERMISSION_DENIED "管理员邀请码错误"
     *      - UsernameTakenException → ALREADY_EXISTS "用户名已存在"
     *      - 唯一冲突（race condition 兜底） → ALREADY_EXISTS "用户名已存在"
     *      - 其他 Exception → INTERNAL "DB添加失败"</li>
     *   <li>成功 → CreateAdminResp{Uid=uid}</li>
     * </ol>
     */
    public long createAdmin(String username, String password, String email,
                            Map<String, String> extraInfo, String inviteCode) {
        // 🔴 DRIFT-ARPC-006：参考侧 createadminlogic.go 的顺序是
        //     tracer.Start(ctx, "Action-Admin-CreateAdmin")
        //     username := strings.TrimSpace(in.Username)      ← 先 trim
        //     span.SetAttributes(attribute.String("audit.admin_username", username))  ← 再打属性
        // ⇒ 属性必须取**trim 之后**的值，与参考侧逐字一致（不是原始入参）。
        return AdminSpans.traced(AdminSpans.CREATE_ADMIN,
                span -> span.setAttribute(AdminSpans.ATTR_ADMIN_USERNAME,
                        username == null ? "" : username.trim()),
                () -> doCreateAdmin(username, password, email, extraInfo, inviteCode));
    }

    private long doCreateAdmin(String username, String password, String email,
                               Map<String, String> extraInfo, String inviteCode) {
        checkDeadline();
        // inviteCode 非空
        String trimmedInvite = inviteCode == null ? "" : inviteCode.trim();
        if (trimmedInvite.isEmpty()) {
            throw Status.PERMISSION_DENIED.withDescription("管理员邀请码错误").asRuntimeException();
        }

        // DRIFT-008（已修）：此处<b>不</b>校验 username 是否为空。
        // 参考侧 createadminlogic.go 没有该校验——username 为空串时会照常进事务、
        // 查重（通常 not found）并 INSERT 成功，返回 uid。
        // 本实现此前多加了一道 INVALID_ARGUMENT「用户名不能为空」，而
        // AdminCreateService 的 switch 没有 INVALID_ARGUMENT case，会落到 default
        // 被报成 1015「服务繁忙」——把一个本来能成功的建号请求报成服务端故障。
        String trimmedUsername = username == null ? "" : username.trim();

        // R5（2026-10-22，用户签字「完全对齐参考侧」）：**密码哈希已移进事务**，
        // 由 AdminCreateAtomicService 在 W1 查 invite / W2 查 username 之后完成。
        //
        // 修复前这里在事务外先做 bcrypt，导致 bcrypt 的 72 字节上限抢在
        // invite / username 检查之前抛错，把本该报 1016 / 1001 的请求报成 5001。
        // 实测（tools/difftest/r5-order-probe.py，全新 cluster）：
        //   B 有效邀请码 + 超长密码 + 用户名已存在   参考侧=1001 / 修复前 RAG=5001
        //   C 无效邀请码 + 超长密码                 参考侧=1016 / 修复前 RAG=5001
        // 参考侧出处：createadminlogic.go 事务闭包内 PasswordEncrypt 排在
        //           FindAdminInviteByCodeForUpdate 与 FindOneAdminByUsernameTx 之后。
        //
        // ⚠️ 下方原先那条注释「bcrypt password（事务外，避免延长事务持有时间）」
        // 描述的是一个**未经参考侧出处论证**的优化（§3.2 无出处 idiomatization），
        // 本次按用户裁决撤销。代价已知并接受：cost=10 约 60~100ms，
        // 会延长 invite 行 FOR UPDATE 的持锁时间；但该锁只锁单行。
        //
        // Snowflake uid 仍留在事务外（见下）：它不触碰 invite 锁，也不参与错误码归属，
        // 与参考侧放在哈希之后相比不产生可观测差异（参考侧 GetID 失败同样是 Internal）。

        // Snowflake uid（事务外）
        long uid;
        try {
            uid = Snowflake.nextId();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            log.error("admin createAdmin Snowflake.nextId failed", e);
            throw Status.INTERNAL.withDescription("DB添加失败").asRuntimeException();
        }

        // 4 步事务
        String optionalEmail = AdminHelpers.optionalString(email);
        Map<String, String> safeExtraInfo = (extraInfo == null || extraInfo.isEmpty())
                ? null
                : new HashMap<>(extraInfo);
        try {
            createAtomicService.createAdminAtomic(
                    trimmedUsername, trimmedInvite, password, optionalEmail, safeExtraInfo, uid, true);
        } catch (InviteUnavailableException e) {
            throw Status.PERMISSION_DENIED.withDescription("管理员邀请码错误").asRuntimeException();
        } catch (UsernameTakenException e) {
            throw Status.ALREADY_EXISTS.withDescription("用户名已存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            if (AdminExceptions.isUniqueViolation(e)) {
                throw Status.ALREADY_EXISTS.withDescription("用户名已存在").asRuntimeException();
            }
            log.error("admin createAdmin 4-step transaction failed", e);
            throw Status.INTERNAL.withDescription("DB添加失败").asRuntimeException();
        }

        log.info("admin createAdmin success, username={}", trimmedUsername);
        return uid;
    }

    /**
     * CreateAdmin 的 4 步原子事务已移到 {@link AdminCreateAtomicService}。
     *
     * <p>原先它作为本类的 {@code @Transactional} 方法、由 {@code createAdmin(...)} 同类直接调用，
     * 属于 self-invocation：Spring 的事务代理不拦截同类内部调用，
     * {@code @Transactional} <b>静默失效</b>，4 步退化成 4 条独立 auto-commit 语句。
     * 独立成 Bean 后由 {@code createAdmin} 跨 Bean 调用，代理才真正生效。
     *
     * <p>同时补上了 invite 行的 {@code SELECT ... FOR UPDATE} 悲观锁 —— 注释里写了"悲观锁"
     * 但查询语句并没有锁，并发下同一个邀请码可以被消费多次。
     */

    // ============== 6. CreateInviteCode ==============

    /**
     * 对应参考侧 internal/logic/admin/createinvitecodelogic.go 的 CreateInviteCodeLogic.CreateInviteCode。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（3 状态机）：
     * <ol>
     *   <li>findOneAdminByUid(operator_id) → NotFound / Internal</li>
     *   <li>循环重试最多 3 次：SecureRandom.nextBytes(8) → "ADM-" + HexFormat → insertAdminInvite;
     *      unique violation 继续；其他 DB 错 → Internal</li>
     *   <li>成功 → CreateInviteCodeResp{invite_code, expires_at=unix}</li>
     * </ol>
     */
    public InviteCodeResult createInviteCode(long operatorId) {
        // 🔴 DRIFT-ARPC-006：参考侧 createinvitecodelogic.go span="Action-Admin-CreateInviteCode"，
        //    属性只有 audit.operator_id
        return AdminSpans.traced(AdminSpans.CREATE_INVITE_CODE,
                span -> span.setAttribute(AdminSpans.ATTR_OPERATOR_ID, operatorId),
                () -> doCreateInviteCode(operatorId));
    }

    private InviteCodeResult doCreateInviteCode(long operatorId) {
        checkDeadline();
        // 校验 operator 是有效 admin
        try {
            findOneAdminByUid(operatorId);
        } catch (NotFoundException e) {
            metrics.getCreateInviteUserNotFound().increment();
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getCreateInviteDbError().increment();
            log.error("admin createInviteCode findOneAdminByUid DB error, operator_id={}", operatorId, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        // 🔴 OffsetDateTime，不是 LocalDateTime（2026-10-23，用户拍板修 DRIFT-ARPC-004②）
        // 参考侧出处：createinvitecodelogic.go:63  expiresAt := time.Now().Add(inviteCodeTTL)
        //          createinvitecodelogic.go:83  ExpiresAt: expiresAt.Unix()
        // 参考侧的 time.Time 带 location，timestamptz 列存的是**绝对时刻**，Unix() 跨时区恒定。
        // LocalDateTime 只剩墙钟：单实例同区恰好自洽，跨时区多实例会让 expires_at 偏移一个时区差
        // ⇒ 消费方 AdminCreateAtomicService 按墙钟比较时，邀请码会刚生成就判为已过期。
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plus(INVITE_CODE_TTL);

        for (int attempt = 0; attempt < INVITE_CODE_MAX_RETRY; attempt++) {
            String code;
            try {
                code = generateInviteCode();
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
                //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
                throw e;
            } catch (Exception e) {
                metrics.getCreateInviteInternalError().increment();
                log.error("admin createInviteCode SecureRandom failed", e);
                throw Status.INTERNAL.withDescription("管理员邀请码生成失败").asRuntimeException();
            }

            AdminInviteEntity invite = new AdminInviteEntity();
            invite.setCode(code);
            invite.setInviterUid(operatorId);
            invite.setExpiresAt(expiresAt);

            try {
                adminInviteMapper.insert(invite);
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
                //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
                throw e;
            } catch (Exception e) {
                if (AdminExceptions.isUniqueViolation(e)) {
                    // unique violation → 重试（不埋点，对应参考侧 continue）
                    continue;
                }
                metrics.getCreateInviteDbError().increment();
                log.error("admin createInviteCode insert DB error", e);
                throw Status.INTERNAL.withDescription("DB写入失败").asRuntimeException();
            }

            metrics.getCreateInviteSuccess().increment();
            // 参考侧出处：createinvitecodelogic.go:83  ExpiresAt: expiresAt.Unix()
            // ⚠️ 注意是**内存里算**、不是回读 DB 后再算 —— 所以 wire 值与列类型无关，
            // 但**列类型决定下游能不能把它当成同一个绝对时刻**（见 createAdminAtomic 的比较）。
            long expiresAtUnix = expiresAt.toEpochSecond();
            log.info("admin createInviteCode success, operator_id={}", operatorId);
            return new InviteCodeResult(code, expiresAtUnix);
        }

        // 重试超过 3 次仍 unique violation
        metrics.getCreateInviteInternalError().increment();
        throw Status.INTERNAL.withDescription("管理员邀请码生成失败").asRuntimeException();
    }

    private static String generateInviteCode() {
        byte[] buf = new byte[INVITE_CODE_RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(buf);
        return INVITE_CODE_PREFIX + HexFormat.of().formatHex(buf).toUpperCase();
    }

    /** Invite code (string code + unix timestamp) —— 对应参考侧 CreateInviteCodeResp 字段 */
    public record InviteCodeResult(String code, long expiresAt) {}

    // ============== 7. BanUser ==============

    /**
     * 对应参考侧 internal/logic/admin/banuserlogic.go 的 BanUserLogic.BanUser。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义：把 user.status 改成 1（封禁）。
     */
    public boolean banUser(long uid, long operatorId) {
        // 🔴 DRIFT-ARPC-006：参考侧 Action-Admin-BanUser，属性 audit.operator_id + audit.target_uid
        return AdminSpans.traced(AdminSpans.BAN_USER, span -> {
            span.setAttribute(AdminSpans.ATTR_OPERATOR_ID, operatorId);
            span.setAttribute(AdminSpans.ATTR_TARGET_UID, uid);
        }, () -> doBanUser(uid, operatorId));
    }

    private boolean doBanUser(long uid, long operatorId) {
        checkDeadline();
        int rows;
        try {
            LambdaUpdateWrapper<UserEntity> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(UserEntity::getUid, uid)
                    .set(UserEntity::getStatus, 1L)
                    .setSql("update_time = CURRENT_TIMESTAMP");
            rows = userMapper.update(null, wrapper);
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getBanDbError().increment();
            log.error("admin banUser update DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
        }
        if (rows == 0) {
            metrics.getBanUserNotFound().increment();
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        }
        metrics.getBanSuccess().increment();
        log.info("admin banUser success, operator_id={} target_uid={}", operatorId, uid);
        return true;
    }

    // ============== 8. UnBanUser ==============

    /**
     * 对应参考侧 internal/logic/admin/unbanuserlogic.go 的 UnBanUserLogic.UnBanUser。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义：把 user.status 改回 0（解封）。
     */
    public boolean unBanUser(long uid, long operatorId) {
        // 🔴 DRIFT-ARPC-006：参考侧 Action-Admin-UnBanUser，属性 audit.operator_id + audit.target_uid
        return AdminSpans.traced(AdminSpans.UNBAN_USER, span -> {
            span.setAttribute(AdminSpans.ATTR_OPERATOR_ID, operatorId);
            span.setAttribute(AdminSpans.ATTR_TARGET_UID, uid);
        }, () -> doUnBanUser(uid, operatorId));
    }

    private boolean doUnBanUser(long uid, long operatorId) {
        checkDeadline();
        int rows;
        try {
            LambdaUpdateWrapper<UserEntity> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(UserEntity::getUid, uid)
                    .set(UserEntity::getStatus, 0L)
                    .setSql("update_time = CURRENT_TIMESTAMP");
            rows = userMapper.update(null, wrapper);
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getUnbanDbError().increment();
            log.error("admin unBanUser update DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
        }
        if (rows == 0) {
            metrics.getUnbanUserNotFound().increment();
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        }
        metrics.getUnbanSuccess().increment();
        log.info("admin unBanUser success, operator_id={} target_uid={}", operatorId, uid);
        return true;
    }

    // ============== 9. GetUser ==============

    /**
     * 对应参考侧 internal/logic/admin/getuserlogic.go 的 GetUserLogic.GetUser。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（2 状态机）：findOneUserByUid → NotFound / Internal / 命中。
     *
     * <p>注意：admin-rpc UserInfo 比 user-rpc UserInfo 多一个 score 字段。
     * 参考侧 getuserlogic.go 的 pb.UserInfo 构造没有设置 score 字段（proto3 默认 0），1:1 保留：
     * Java 端不显式 setScore（默认 0）。
     */
    public UserEntity getUser(long uid) {
        checkDeadline();
        UserEntity user;
        try {
            user = findOneUserByUid(uid);
        } catch (NotFoundException e) {
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            log.error("admin getUser DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }
        return user;
    }

    // ============== 10. GetUserList ==============

    /**
     * 对应参考侧 internal/logic/admin/getuserlistlogic.go 的 GetUserListLogic.GetUserList。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（2 状态机）：
     * <ol>
     *   <li>findUserListByKeyword(page, pageSize, keyword) → (users, total)</li>
     *   <li>逐行 User → pb.UserInfo 转换（不设 score）</li>
     * </ol>
     *
     * <p>keyword 非空时按 username/email LIKE 查询，否则全量。
     *
     * <p><b>参数用 {@code long} 而不是 {@code int}</b>：参考侧的 {@code GetUserListReq.Page}
     * 是 {@code int64}，从 proto 到 model 全程 int64 运算。此前的
     * {@code (int) request.getPage()} 会把 {@code page=2^32+1} 悄悄变成 1 ——
     * 算出的 OFFSET 与参考侧完全不同，而症状是「翻页结果不对」，极难归因。
     */
    public UserListResult getUserList(long page, long pageSize, String keyword) {
        checkDeadline();
        // DRIFT-006（已修）：keyword <b>不</b> trim。参考侧 model/admin.go:152-155 是
        //   if len(keyword) > 0 { keyWord := "%" + keyword + "%" }
        // 空白 keyword（" "）在参考侧 len>0 成立 → 走 LIKE '%  %' 过滤；
        // 本实现此前用 keyword.trim().isEmpty() 判空并把 pattern 也 trim 过，
        // 导致 keyword=" " 返回全量、keyword=" a " 匹配到不同的行。
        boolean hasKeyword = keyword != null && !keyword.isEmpty();
        String pattern = hasKeyword ? "%" + keyword + "%" : null;

        // ── E-3 根因 R4：offset := (page-1)*pageSize，但**负值不会下推到 PG** ──
        //
        // 参考侧 model/admin.go:159-160：
        //     offset := (page - 1) * pageSize
        //     db.Order("id desc").Offset(int(offset)).Limit(int(pageSize)).Find(&users)
        // 看起来「无任何保护」，**但 GORM 在生成 SQL 时有一层静默抑制** ——
        // gorm.io/gorm@v1.31.1 clause/limit.go 的 Limit.Build：
        //     if limit.Limit != nil && *limit.Limit >= 0 { 写 "LIMIT n" }
        //     if limit.Offset > 0 { 写 "OFFSET n" }
        // 于是：
        //     page=0        → offset=-20  负 → 不生成 OFFSET → 返回首页（不报错！）
        //     page=-1       → offset=-40  负 → 不生成 OFFSET
        //     page_size=-5  → LIMIT 被抑制、offset=-5 也被抑制 → 返回**全量**
        //     page=int64max → (page-1)*pageSize 在 int64 下回绕成 -64 → 不生成 OFFSET
        //
        // 实测（参考侧 admin-api 18889，curl 直打）以上四种**全部 200 + code 200**。
        //
        // ⚠️ 本实现此前的注释写的是「page=0 会算出负 offset 并让 PG 抛错，最终映射成
        // INTERNAL」—— **那句是错的**，且它引用的 model/admin.go 是真实文件，
        // 只是把 GORM 的行为读反了。与 §3.4.4 DRIFT-009 同型（引用真文件、读反内外关系）。
        // 本实现也确实照着这句错误结论把负值原样拼进了 SQL：
        //     listWrapper.last("LIMIT " + pageSize + " OFFSET " + ((page-1)*pageSize));
        // ⇒ PG 报 22023: OFFSET must not be negative → 5001，而参考侧是 200。
        //
        // offset 的算术刻意用 Java long：它与参考侧的 int64 同样是 64 位二进制补码回绕，
        // page=int64max 时两边都得到 -64，抑制行为因此一致。
        long offset = (page - 1) * pageSize;
        String pagination = goStylePagination(pageSize, offset);

        // count
        long total;
        try {
            LambdaQueryWrapper<UserEntity> countWrapper = new LambdaQueryWrapper<>();
            if (hasKeyword) {
                countWrapper.and(w -> w.like(UserEntity::getUsername, pattern)
                        .or().like(UserEntity::getEmail, pattern));
            }
            Long totalLong = userMapper.selectCount(countWrapper);
            total = totalLong == null ? 0L : totalLong;
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            log.error("admin getUserList count DB error", e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        // list
        List<UserEntity> rows;
        try {
            LambdaQueryWrapper<UserEntity> listWrapper = new LambdaQueryWrapper<>();
            if (hasKeyword) {
                listWrapper.and(w -> w.like(UserEntity::getUsername, pattern)
                        .or().like(UserEntity::getEmail, pattern));
            }
            listWrapper.orderByDesc(UserEntity::getId);
            // R4：负值按 GORM 的规则抑制，不是原样下推给 PG（见上方注释）
            if (!pagination.isEmpty()) {
                listWrapper.last(pagination);
            }
            rows = userMapper.selectList(listWrapper);
            if (rows == null) rows = Collections.emptyList();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            log.error("admin getUserList DB error", e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }
        return new UserListResult(rows, total);
    }

    /**
     * E-3 根因 **R4**：把 GORM 的 LIMIT/OFFSET <b>静默抑制</b>规则逐字镜像成 SQL 片段。
     *
     * <p><b>权威出处</b>（gorm.io/gorm@v1.31.1，{@code clause/limit.go} 的 {@code Limit.Build}）：
     * <pre>
     *   if limit.Limit != nil &amp;&amp; *limit.Limit &gt;= 0 { builder.WriteString("LIMIT ");  ... }
     *   if limit.Offset &gt; 0 {
     *       if limit.Limit != nil &amp;&amp; *limit.Limit &gt;= 0 { builder.WriteByte(' ') }
     *       builder.WriteString("OFFSET "); ...
     *   }
     * </pre>
     * 即：**LIMIT 只在 pageSize ≥ 0 时生成；OFFSET 只在 offset &gt; 0 时生成**，
     * 且两者之间那个空格也只在 LIMIT 真的生成了时才写。
     *
     * <p><b>为什么必须照抄而不是「顺手保护一下」</b>：参考侧对 {@code page=0} /
     * {@code page_size=-5} 的回答是 <b>200 + 返回首页/全量</b>，不是 400。
     * RAG 侧一旦自作主张 clamp 成 400 或悄悄兜底成第 1 页，
     * 就把「与参考侧一致」改成了「与参考侧不一致」——
     * 这正是 §3.4 里那 11 处回归的共同成因。
     *
     * @param pageSize 已算好的 LIMIT 值（参考侧 {@code int(pageSize)}）
     * @param offset   已算好的 OFFSET 值（参考侧 {@code int(offset)}，int64 回绕后）
     * @return 可直接喂给 {@code LambdaQueryWrapper.last()} 的片段；两者都被抑制时返回 {@code ""}
     */
    public static String goStylePagination(long pageSize, long offset) {
        boolean hasLimit = pageSize >= 0;
        boolean hasOffset = offset > 0;
        if (!hasLimit && !hasOffset) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (hasLimit) {
            sb.append("LIMIT ").append(pageSize);
        }
        if (hasOffset) {
            if (hasLimit) {
                sb.append(' ');
            }
            sb.append("OFFSET ").append(offset);
        }
        return sb.toString();
    }

    /** GetUserList 返回值 record —— 对应参考侧 ([]*User, int64) 二元组 */
    public record UserListResult(List<UserEntity> users, long total) {}

    // ============== 11. UpdateUser ==============

    /**
     * 对应参考侧 internal/logic/admin/updateuserlogic.go 的 UpdateUserLogic.UpdateUser。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（5 状态机）：
     * <ol>
     *   <li>findOneUserByUid → NotFound / Internal</li>
     *   <li>if username 非空 → 查 username 冲突（existUser.Uid != in.Uid → AlreadyExists）</li>
     *   <li>构造 toUpdate（跳零值；User.Email 非 null，用普通赋值）</li>
     *   <li>UpdateUserByUid（LambdaUpdateWrapper 动态 set）</li>
     *   <li>重读 user</li>
     * </ol>
     */
    public UserEntity updateUser(long uid, long operatorId,
                              String username, String password, String email,
                              Map<String, String> extraInfo) {
        // 🔴 DRIFT-ARPC-006：参考侧 updateuserlogic.go span="Action-Admin-UpdateUser"，
        //    属性 audit.operator_id + audit.target_uid
        return AdminSpans.traced(AdminSpans.UPDATE_USER, span -> {
            span.setAttribute(AdminSpans.ATTR_OPERATOR_ID, operatorId);
            span.setAttribute(AdminSpans.ATTR_TARGET_UID, uid);
        }, () -> doUpdateUser(uid, operatorId, username, password, email, extraInfo));
    }

    private UserEntity doUpdateUser(long uid, long operatorId,
                                    String username, String password, String email,
                                    Map<String, String> extraInfo) {
        checkDeadline();
        // W2 查 user
        UserEntity target;
        try {
            target = findOneUserByUid(uid);
        } catch (NotFoundException e) {
            metrics.getUpdateUserUserNotFound().increment();
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getUpdateUserDbError().increment();
            log.error("admin updateUser findOneUserByUid DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        // W3 username 冲突检查
        LambdaUpdateWrapper<UserEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(UserEntity::getUid, uid);
        if (username != null && !username.isEmpty()) {
            UserEntity existing;
            try {
                existing = findOneUserByUsername(username);
            } catch (NotFoundException e) {
                existing = null;
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
                //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
                throw e;
            } catch (Exception e) {
                metrics.getUpdateUserDbError().increment();
                log.error("admin updateUser findOneUserByUsername DB error", e);
                throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
            }
            // D-004 1:1 保留：不排除自己
            if (existing != null && !existing.getUid().equals(uid)) {
                throw Status.ALREADY_EXISTS.withDescription("用户名已存在").asRuntimeException();
            }
            wrapper.set(UserEntity::getUsername, username);
        }

        // W4 bcrypt password
        if (password != null && !password.isEmpty()) {
            try {
                wrapper.set(UserEntity::getPassword, PasswordUtil.encrypt(password));
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
                //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
                throw e;
            } catch (Exception e) {
                metrics.getUpdateUserInternalError().increment();
                log.error("admin updateUser PasswordUtil.encrypt failed", e);
                throw Status.INTERNAL.withDescription("密码加密失败").asRuntimeException();
            }
        }
        // email 非 null（User 表定义）：不进 null，仅 trim
        if (email != null && !email.isEmpty()) {
            wrapper.set(UserEntity::getEmail, email);
        }
        if (extraInfo != null && !extraInfo.isEmpty()) {
            // 同 updateSelf 的 setSql + 参数绑定（不能用 wrapper.set，走 Object 猜 handler 不可控）。
            //
            // 🔴 2026-10-25（第十八轮 · global B10）：**去掉了 CAST(... AS jsonb)**。
            //   原注释写「users_user.extra_info 这一列仍是 jsonb，CAST 必须保留」——
            //   **那个前提早已失效**：
            //     users-common/.../db/01_create_user.sql:41  建表就是 extra_info TEXT
            //     users-common/.../db/01_create_user.sql:103 ALTER ... TYPE text USING extra_info::text
            //       （SchemaInitializer 每次启动原样重放 ⇒ 幂等自愈 ⇒ 这一列不可能是 jsonb）
            //   ⇒ 留着 CAST 等于**强行把 text 转回 jsonb**，重新引入 jsonb 那层校验 ——
            //     而参考侧根本没有这层校验（参考侧出处：admin-rpc 的 data_model.go:43
            //     的 `serializer:json` ⇒ 产出字符串 ⇒ 落进 text 列）。
            //   ⇒ 真 PG 16 实测差异（同一份含 U+0000 的 payload）：
            //       参考侧 → 落库成功，存下 14 字节的 {"k":"\u0000"}
            //       RAG    → PG 拒收：ERROR: unsupported Unicode escape sequence
            //              DETAIL: \u0000 cannot be converted to text.  SQLSTATE = 22P05
            //   ⇒ 且 jsonb 会**规范化** JSON（键序 + 冒号后加空格）⇒ 落库字节与参考侧不同。
            //   ⇒ 守护见 AdminRpcColumnTypeParityTest 的两条新用例。
            wrapper.setSql("extra_info = {0}", toJson(extraInfo));
        }
        // DRIFT-ADMIN-RPC-003：对齐 GORM autoUpdateTime
        wrapper.setSql("update_time = CURRENT_TIMESTAMP");

        // W5 update
        try {
            userMapper.update(null, wrapper);
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getUpdateUserDbError().increment();
            log.error("admin updateUser update DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
        }

        // W6 重读
        UserEntity newUser;
        try {
            newUser = findOneUserByUid(uid);
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getUpdateUserDbError().increment();
            log.error("admin updateUser re-findOneUserByUid DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }
        metrics.getUpdateUserSuccess().increment();
        log.info("admin updateUser success, operator_id={} target_uid={}", operatorId, uid);
        return newUser;
    }

    // ============== 12. DeleteUser ==============

    /**
     * 对应参考侧 internal/logic/admin/deleteuserlogic.go 的 DeleteUserLogic.DeleteUser。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义：deleteUserByUid → RowsAffected=0 抛 NotFound / 其它 DB 错抛 Internal / 成功 → resp{Success=true}。
     */
    public boolean deleteUser(long uid, long operatorId) {
        // 🔴 DRIFT-ARPC-006：参考侧 Action-Admin-DeleteUser，属性 audit.operator_id + audit.target_uid
        return AdminSpans.traced(AdminSpans.DELETE_USER, span -> {
            span.setAttribute(AdminSpans.ATTR_OPERATOR_ID, operatorId);
            span.setAttribute(AdminSpans.ATTR_TARGET_UID, uid);
        }, () -> doDeleteUser(uid, operatorId));
    }

    private boolean doDeleteUser(long uid, long operatorId) {
        checkDeadline();
        int rows;
        try {
            rows = userMapper.delete(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUid, uid));
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getDeleteUserDbError().increment();
            log.error("admin deleteUser DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
        }
        if (rows == 0) {
            metrics.getDeleteUserUserNotFound().increment();
            throw Status.NOT_FOUND.withDescription(String.format("用户%d不存在", uid)).asRuntimeException();
        }
        metrics.getDeleteUserSuccess().increment();
        log.info("admin deleteUser success, operator_id={} target_uid={}", operatorId, uid);
        return true;
    }

    // ============== 13. ResetUserPassword ==============

    /**
     * 对应参考侧 internal/logic/admin/resetuserpasswordlogic.go 的 ResetUserPasswordLogic.ResetUserPassword。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminDomainService.java}
     *
     * <p>L2 业务语义（5 状态机）：
     * <ol>
     *   <li>findOneUserByUid → NotFound / Internal</li>
     *   <li>bcrypt DefaultPassword → Internal</li>
     *   <li>updateUserPasswordByUid（LambdaUpdateWrapper set password）</li>
     *   <li>成功 → ResetUserPasswordResp{Success=true}</li>
     * </ol>
     *
     * <p>参考侧 UpdateUserPasswordByUid 不检查 RowsAffected（不会抛 NotFound），
     * 所以 ResetUserPassword 即使 user 在 W2 和 W4 之间被并发删了，仍会返 success。这是参考侧的语义，1:1 保留。
     */
    public boolean resetUserPassword(long uid, long operatorId) {
        // 🔴 DRIFT-ARPC-006：参考侧 Action-Admin-ResetUserPassword，属性 audit.operator_id + audit.target_uid
        return AdminSpans.traced(AdminSpans.RESET_USER_PASSWORD, span -> {
            span.setAttribute(AdminSpans.ATTR_OPERATOR_ID, operatorId);
            span.setAttribute(AdminSpans.ATTR_TARGET_UID, uid);
        }, () -> doResetUserPassword(uid, operatorId));
    }

    private boolean doResetUserPassword(long uid, long operatorId) {
        checkDeadline();
        // W2 查 user
        try {
            findOneUserByUid(uid);
        } catch (NotFoundException e) {
            metrics.getResetPasswordUserNotFound().increment();
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getResetPasswordDbError().increment();
            log.error("admin resetUserPassword findOneUserByUid DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        // W3 bcrypt DefaultPassword
        String defaultPassword = properties.getSystem().getDefaultPassword();
        if (defaultPassword == null || defaultPassword.isEmpty()) {
            // 🔴 **有意偏离，不是待修漂移**（DRIFT-ARPC-003，用户 2026-10-23 22:0x 拍板签字）。
            //
            // 参考侧出处：resetuserpasswordlogic.go:55-62
            //     var password string
            //     password, err = cryptx.PasswordEncrypt(l.svcCtx.Config.System.DefaultPassword)
            //   参考侧**没有空值守卫**：空串照样进 bcrypt。
            //   而 x/crypto/bcrypt 的 GenerateFromPassword 只在 len > 72 时报错
            //   ⇒ 配置为空时参考侧**成功**，并把该用户的密码重置成「空串的哈希」
            //     —— 之后可用**空密码登录**。
            //
            // 签字结论：**保留 RAG 的严格拒绝**，不引入空密码可登录这一路径。
            //   与 SD-2026-10-23-01/02/03 同型：「RAG 做了更严/更安全的那一侧」。
            // ⚠️ 代价（必须知情）：显式把 system.default-password 配成空串时，
            //   参考侧返回成功、RAG 返回 INTERNAL「密码加密失败」→ admin-api 报 5001。
            //   对拍在这一条上**必然为红，属预期差异，不是回归**。
            // ⚠️ 不得做的事：不要为了「让对拍变绿」而删掉这道守卫。
            //   若日后参考侧也补上空值校验，应反向收敛 RAG，而不是保持差异。
            // 守门：AdminSemanticDriftRegressionTest 里的空 default-password 用例。
            metrics.getResetPasswordInternalError().increment();
            throw Status.INTERNAL.withDescription("密码加密失败").asRuntimeException();
        }
        String hash;
        try {
            hash = PasswordUtil.encrypt(defaultPassword);
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getResetPasswordInternalError().increment();
            log.error("admin resetUserPassword PasswordUtil.encrypt failed", e);
            throw Status.INTERNAL.withDescription("密码加密失败").asRuntimeException();
        }

        // W4 update（不检查 RowsAffected，1:1 跟随参考侧语义）
        try {
            LambdaUpdateWrapper<UserEntity> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(UserEntity::getUid, uid).set(UserEntity::getPassword, hash)
                    // DRIFT-ADMIN-RPC-003：对齐 GORM autoUpdateTime
                    .setSql("update_time = CURRENT_TIMESTAMP");
            userMapper.update(null, wrapper);
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            metrics.getResetPasswordDbError().increment();
            log.error("admin resetUserPassword update DB error, uid={}", uid, e);
            throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
        }

        metrics.getResetPasswordSuccess().increment();
        log.info("admin resetUserPassword success, operator_id={} target_uid={}", operatorId, uid);
        return true;
    }

    // ============== 内部 helper：单表查询（给 service 13 个方法共享）==============

    /**
     * 对应参考侧 AdminModel.findOneAdminByUid。
     * 找不到 → 抛 NotFoundException。
     */
    private AdminEntity findOneAdminByUid(long uid) {
        AdminEntity admin = adminMapper.selectOne(
                new LambdaQueryWrapper<AdminEntity>().eq(AdminEntity::getUid, uid)
        );
        if (admin == null) {
            throw new NotFoundException("admin not found by uid: " + uid);
        }
        return admin;
    }

    /**
     * 对应参考侧 AdminModel.findOneAdminByUsername。
     * 找不到 → 抛 NotFoundException。
     */
    private AdminEntity findOneAdminByUsername(String username) {
        AdminEntity admin = adminMapper.selectOne(
                new LambdaQueryWrapper<AdminEntity>().eq(AdminEntity::getUsername, username)
        );
        if (admin == null) {
            throw new NotFoundException("admin not found by username: " + username);
        }
        return admin;
    }

    /**
     * 对应参考侧 AdminModel.findOneUserByUid。
     */
    private UserEntity findOneUserByUid(long uid) {
        UserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUid, uid)
        );
        if (user == null) {
            throw new NotFoundException("user not found by uid: " + uid);
        }
        return user;
    }

    /**
     * 对应参考侧 AdminModel.findOneUserByUsername。
     */
    private UserEntity findOneUserByUsername(String username) {
        UserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username)
        );
        if (user == null) {
            throw new NotFoundException("user not found by username: " + username);
        }
        return user;
    }

    /**
     * Map → JSON 文本，供两处 {@code wrapper.setSql("extra_info = …", …)} 使用。
     * <p><b>两个调用点的列类型<b>相同</b>（都是 text）⇒ 两处都不再 CAST</b>：
     * <ul>
     *   <li>{@code updateSelf} → {@code admin_admin.extra_info}（text，2026-10-23 起不再 CAST）</li>
     *   <li>{@code updateUser}  → {@code users_user.extra_info}（text，2026-10-25 起也不再 CAST）</li>
     * </ul>
     * ⚠️ 2026-10-23 当时写的是「两处列类型不同 ⇒ 只有 admin_admin 能去 CAST」——
     * <b>那个前提是错的</b>（users_user.extra_info 建表就是 TEXT）⇒ 第十八轮一并去掉。
     * 序列化失败按内部错误处理——extraInfo 是 Map&lt;String,String&gt;，正常不会失败。
     */
    private static String toJson(Map<String, String> map) {
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
            //       （客户端可见码按**集合**读：参考侧集合含 1015 与 5001（窄窗口），本条**只主张集合里有 1015**；**理由**见类级 javadoc「B-1 放行分支」段 + DRIFT-ARPC-011）
            throw e;
        } catch (Exception e) {
            log.error("admin extraInfo JSON serialize failed", e);
            throw Status.INTERNAL.withDescription("JSON序列化失败").asRuntimeException();
        }
    }

    // ==================================================================
    // DRIFT-ADMIN-RPC-003：update_time 自动推进 —— 本类所有 UPDATE 收口点的统一说明
    // ==================================================================

    /*
     * 🔴 <b>修掉的是什么</b>：{@code UserEntity.updateTime} / {@code AdminEntity.updateTime}
     * 标的是 {@code updateStrategy = FieldStrategy.NEVER}（见 {@code domain/entity/UserEntity.java}），
     * 本意是<b>不让实体自动写这一列</b>。但 MyBatis-Plus <b>没有 GORM 的 autoUpdateTime</b> ——
     * {@code NEVER} 的效果是「<b>永远不更新</b>」，而不是「<b>只在我没赋值时不更新</b>」。
     * 结果：参考侧每次 {@code Updates} 都推进的 update_time，在本类<b>全部 5 个 UPDATE 入口</b>
     * 上<b>永远停在建行那一刻</b>。不报错、不告警、SQL 全部成功。
     *
     * ⚠️ <b>「5 个」是本类的口径，不是全组件的口径</b>（2026-10-23「台账 vs 实际」复核订正）：
     * {@link AdminCreateAtomicService} 的 W4（标记 invite 已用）<b>也是一条 UPDATE</b>，
     * 此前同样漏了 —— 该轮复核才发现并补上。
     * ⇒ <b>全组件正确总数是 6 处</b>：本类 5 + {@code AdminCreateAtomicService} 1。
     * <b>凡引用「5 处 UPDATE」的地方（含 DRIFT-ARPC-001 / FIX-ARPC-001）都应读作 6 处。</b>
     *
     * <h3>参考侧出处（声明点 + 消费点）</h3>
     * <ul>
     *   <li><b>声明点</b>：{@code service/user/admin/rpc/internal/model/data_model.go:12-13}
     *       （Admin）与 {@code :27-28}、{@code :44-45}（User）：
     *       {@code CreateTime time.Time `gorm:"column:create_time;autoCreateTime"`} /
     *       {@code UpdateTime time.Time `gorm:"column:update_time;autoUpdateTime"`}；</li>
     *   <li><b>消费点</b>：{@code gorm@v1.31.1/callbacks/update.go:238-254}
     *       （{@code Updates(map)} / {@code Update(col, val)} 路径）与 {@code gorm@v1.31.1/callbacks/update.go:274-276}
     *       （{@code Updates(struct)} 路径）。</li>
     * </ul>
     *
     * <h3>为什么用 setSql 而不是 MetaObjectHandler（与 article-rpc 的做法不同，这是对的）</h3>
     * 本类 5 处写路径<b>全部</b>是 {@code update(null, wrapper)} —— <b>第一个参数是 null</b>。
     * MyBatis-Plus 的 {@code MetaObjectHandler.updateFill} 需要一个<b>实体</b>去填字段，
     * 实体为 null 时<b>根本不会被调用</b>（实测：无参 {@code update(null, wrapper)} 是 wrapper-only 路径）。
     * ⇒ 在本类加 handler 是<b>无效功</b>，必须走 {@code setSql}。
     * <br>article-rpc 那边 8 处写路径<b>全部</b>是 {@code updateById(entity)}，实体非 null ⇒ 反过来，
     * 那里用 handler 才是对的。<b>两处修法不同不是不一致，是各自路径形态决定的</b>。
     *
     * <h3>放置位置的纪律</h3>
     * user-rpc 的同款修法（{@code UserMapper#updateUserById}）把 setSql 放在
     * 「无变更则不发 UPDATE」判定<b>之后</b>：{@code if (!any) return 0;}。
     * 本类 5 处<b>没有</b>这个判定（admin 侧部分更新直接发 UPDATE），
     * 所以 setSql 无条件跟在其后 —— 每一次真正发出的 UPDATE 都会推进时间戳。
     *
     * <p>⚠️ <b>已知残留分歧（未修，另立条目跟踪）</b>：参考侧的
     * {@code UpdateOneAdminByUid}/{@code UpdateOneUserByUid}（{@code internal/model/admin.go:58}/{@code :96}）
     * 用 {@code Updates(struct)}，GORM 会<b>跳过零值字段</b>，全零时<b>根本不发出 UPDATE</b> ⇒
     * update_time 不变。本类对应两处在<b>参数全空</b>时仍会发出一条只更新 update_time 的 UPDATE。
     * 这与本次修复无关（修复前它们连 SET 子句都是空的），且补齐它需要先确定
     * 「空更新该不该短路」的返回语义 ⇒ <b>不在本条目内猜</b>。
     *
     * <p>⚠️ <b>本段说明对应 5 处</b>：{@code updateSelf}（Admin 表）、
     * {@code banUser}、{@code unBanUser}、{@code updateUser}、{@code resetUserPassword}。
     * 参考侧对应：{@code internal/model/admin.go:104}（status）、
     * {@code :112}（password）、{@code :58}/{@code :96}（结构体部分更新）。
     * admin-rpc 侧 <b>零 {@code UpdateColumn}</b>（参考侧同样实测为零）
     * ⇒ 全部落在 GORM 会推进时间戳的那一侧，无需像 article-rpc 那样排除 SkipHooks 路径。
     */
}
