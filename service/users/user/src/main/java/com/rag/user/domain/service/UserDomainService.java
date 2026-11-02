package com.rag.user.domain.service;

import com.rag.common.logger.LoggerFactoryBean;
import com.rag.common.observability.DeadlineState;
import com.rag.common.security.PasswordUtil;
import com.rag.common.snowflake.Snowflake;
import com.rag.proto.user.CreateUserReq;
import com.rag.proto.user.CreateUserResp;
import com.rag.proto.user.DeleteUserReq;
import com.rag.proto.user.DeleteUserResp;
import com.rag.proto.user.GetAvatarHistoryReq;
import com.rag.proto.user.GetAvatarHistoryResp;
import com.rag.proto.user.GetUserReq;
import com.rag.proto.user.GetUserResp;
import com.rag.proto.user.LoginReq;
import com.rag.proto.user.LoginResp;
import com.rag.proto.user.LogoutReq;
import com.rag.proto.user.LogoutResp;
import com.rag.proto.user.SelectAvatarReq;
import com.rag.proto.user.SelectAvatarResp;
import com.rag.proto.user.UpdateUserReq;
import com.rag.proto.user.UpdateUserResp;
import com.rag.proto.user.UploadAvatarReq;
import com.rag.proto.user.UploadAvatarResp;
import com.rag.proto.user.UserInfo;
import com.rag.user.config.UserRpcProperties;
import com.rag.user.contract.ErrCodes;
import com.rag.user.contract.ErrMsg;
import com.rag.user.domain.entity.UserAvatarHistoryEntity;
import com.rag.user.domain.entity.UserEntity;
import com.rag.user.exception.UserExceptions.AvatarHistoryNotFoundException;
import com.rag.user.exception.UserExceptions.NotFoundException;
import com.rag.user.mapper.UserAvatarHistoryMapper;
import com.rag.user.mapper.UserMapper;
import com.rag.user.metrics.UserRpcMetrics;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p><b>用户域服务</b> —— 把 B 侧 9 个 Logic 类合并到一个类。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/service/UserDomainService.java}
 * —— 9 个方法体<b>一对一</b>从 B 侧翻译，保留 trace ＋ metrics ＋ 三态 status ＋ DB error code。
 * 异常映射：捕获 {@code NotFoundException} → gRPC {@code Status.NOT_FOUND}；
 * 其它 {@code Exception} → {@code Status.INTERNAL}。
 *
 * <h2>关键决策（沿用 B 侧 parity）</h2>
 * <ul>
 *   <li><b>D-001</b>：user not found → throw {@code NotFoundException}，调用方映射 NOT_FOUND</li>
 *   <li><b>D-002</b>：login 三态（status=0/1/2）：成功 / 用户名错或密码错 / 封禁</li>
 *   <li><b>D-005</b>：selectAvatar / uploadAvatar 多步原子（双写 user ＋ avatar_history）</li>
 *   <li><b>D-006 / D-007</b>：Logout Redis key 格式 ＋ TTL 用 {@code config.accessExpire}</li>
 *   <li><b>D-010</b>：listAvatarHistory limit clamp [0,30]</li>
 *   <li><b>D-013</b>：history_id 服务端 Snowflake 生成</li>
 *   <li><b>D-014</b>：双写 {@code user.extra_info["avatar"]}</li>
 *   <li><b>D-015</b>：history 查询必须带 uid 条件</li>
 *   <li><b>D-016</b>：metrics 只在 login/register 埋点（最小覆盖）</li>
 *   <li><b>D-017</b>：updateUserById 只更新 4 列</li>
 *   <li><b>D-018</b>：每次业务步骤前 checkDeadline，超时立即抛 DEADLINE_EXCEEDED</li>
 * </ul>
 *
 * <h2>🔴 B-1 放行分支的语义论证（本类 18 处同款 catch 注释的共同依据）</h2>
 * <pre>
 *   B 本类   ：catch (StatusRuntimeException e) { throw e; }  ⇒ DEADLINE_EXCEEDED 原样上抛
 *   Go logic  ：DB 错误一律 status.Error(codes.Internal, "DB查询失败")（共 10 处出口）
 *   Go 拦截器 ：service/common/observability/rpc.go:59-72 的 select
 *                case &lt;-timeoutCtx.Done(): ⇒ status.Error(codes.DeadlineExceeded, ...)
 * </pre>
 * ⇒ <b>源码层</b>两侧确实不同 —— B 保留了「超时」与「DB 真失败」的区分，Go 把它糊掉了。
 * ⇒ <b>客户端可见面：两侧一致</b>（双栈活体实测 2026-10-21：两侧同为 {@code {"code":1015,"msg":"服务繁忙"}}）——
 * Go 的 {@code codes.Internal} <b>被外层 interceptor 的超时分支掩盖</b>
 * ⇒ ⇒ <b>这 18 处 catch 不得改回 Go 行为</b>：改回去 ⇒ 渲染 5001（{@code ErrorServerCommon}），
 * 而 5001 在 Go 侧表示「<b>数据库故障</b>」不表示「超时」⇒ <b>离 Go 更远</b>。
 *
 * <h2>⚠️ L4 落点变化（表达层，零行为差异）</h2>
 * <ul>
 *   <li>proto 包：{@code com.rag.users.user.proto} → {@code com.rag.proto.user}（D7 集中化到根级 {@code proto/} 模块）；</li>
 *   <li>{@code PasswordUtil}：B 的域内 {@code com.rag.users.common.cryptx.PasswordUtil} → 共享层
 *       {@code com.rag.common.security.PasswordUtil}（由 {@code users-common} lane 上提，行为逐条相同）；</li>
 *   <li>{@code DeadlineState}：B 的 {@code com.rag.common.observability.RpcInterceptor.DeadlineState}
 *       → 共享层 {@code com.rag.common.observability.DeadlineState}（同一能力，独立类）；</li>
 *   <li>{@code LoggerFactoryBean}：B 的 {@code com.rag.common.spring.LoggerFactoryBean}
 *       → 共享层 {@code com.rag.common.logger.LoggerFactoryBean}；</li>
 *   <li>错误码：B 的 {@code com.rag.users.common.errmsg.ErrorCode}（enum，码＋文案同体）
 *       → 本域 {@code contract/ErrCodes} ＋ {@code contract/ErrMsg}（拆分形态，值与文案均转发自共享层真源）。</li>
 * </ul>
 */
@Service
public class UserDomainService {

    private final UserMapper userMapper;
    private final UserAvatarHistoryMapper avatarHistoryMapper;
    private final UserRpcMetrics metrics;
    private final StringRedisTemplate bizRedis;
    private final Tracer tracer;
    private final LoggerFactoryBean logger;
    private final UserRpcProperties properties;
    private final AvatarDomainService avatarDomainService;

    public UserDomainService(UserMapper userMapper,
                              UserAvatarHistoryMapper avatarHistoryMapper,
                              UserRpcMetrics metrics,
                              StringRedisTemplate bizRedis,
                              LoggerFactoryBean logger,
                              UserRpcProperties properties,
                              AvatarDomainService avatarDomainService) {
        this.userMapper = userMapper;
        this.avatarHistoryMapper = avatarHistoryMapper;
        this.metrics = metrics;
        this.bizRedis = bizRedis;
        this.logger = logger;
        this.properties = properties;
        this.avatarDomainService = avatarDomainService;
        // 对应 Go 端 otel.Tracer("user-rpc")
        this.tracer = GlobalOpenTelemetry.getTracer("user-rpc");
    }

    /**
     * DRIFT-018 修复：检查当前线程是否已超过 deadline；任一为 true 抛
     * {@code StatusRuntimeException(DEADLINE_EXCEEDED)}。
     *
     * <p>业务 Logic 在每次 DB / bcrypt / snowflake / 下游调用前调此方法。
     *
     * <p><b>deadline 的实际来源</b>：{@code DeadlineState#bindToCurrent(long)} 由 RPC 拦截器
     * 在<b>真正跑 handler 的线程</b>上调用，作用域随 handler 调用结束自动清理。
     * 未注册 deadline 时为 no-op（业务不受影响）。
     */
    private void checkDeadline() {
        if (Thread.currentThread().isInterrupted() || DeadlineState.isExpired()) {
            throw Status.DEADLINE_EXCEEDED
                    .withDescription("rpc deadline reached")
                    .asRuntimeException();
        }
    }

    // ============== 1. LoginLogic → login() ==============

    /**
     * 对应 Go 端 {@code internal/logic/loginlogic.go}。
     *
     * <p><b>三态返回（D-001 / D-002，必须保留）</b>：
     * <pre>
     *   Status=0 + uid    → 登录成功
     *   Status=1 + uid=0  → 用户不存在 或 密码错（故意不区分，避免用户名枚举）
     *   Status=2 + uid=0  → 用户被封禁（DB status=1）
     * </pre>
     * ⇒ 三态都返回 nil error（不是 gRPC 异常），调用方按 {@code LoginResp.status} 判断分支。
     */
    public LoginResp login(LoginReq req) {
        Span span = tracer.spanBuilder("Action-User-Login")
                .setAttribute("biz.username", req.getUsername())
                .startSpan();
        Context otelCtx = Context.current().with(span);
        try {
            checkDeadline();
            // W1 查用户
            UserEntity user;
            try {
                checkDeadline();
                user = userMapper.findOneByUserName(req.getUsername());
                // ⚠️ B 侧 DRIFT-USERRPC-V-012（已撤销的 fail-closed 守卫）：
                //   Go 侧把 status IS NULL 读成 0（gorm 的 setter 在 *data == nil 时不动作，
                //   database/sql 的 Pointer + src==nil 走 SetZero() 不报错）
                //   ⇒ **Go 把 status IS NULL 读成 0，然后 status==1 判 false ⇒ 放行登录**。
                //   ⇒ 「status 为 null ⇒ 抛 INTERNAL」的守卫被双侧活体对拍证伪 ⇒ 已删除。
                //   ⇒ 下面的 {@code != null && == 1L} 判定因此对 NULL 行与 Go 一样得到「未封禁」。
            } catch (NotFoundException e) {
                // 用户不存在 → Status=1（D-002：和密码错共用同一个状态码）
                metrics.getLoginUserNotFound().increment();
                logger.logInfo(otelCtx, "login failed : user not found,username : " + req.getUsername());
                return LoginResp.newBuilder().setStatus(1L).build();
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛，不能掉进下面的 db_error 分支被改写成 INTERNAL。
                throw e;
            } catch (Exception e) {
                metrics.getLoginDbError().increment();
                span.recordException(e);
                // ⚠️ **不置** span 状态：Go 的 span.RecordError(err) **不**改状态
                //    （otel-go SDK v1.44.0 sdk/trace/span.go:631-656 注释原文：
                //     "this method does not change the Span status"）⇒ Go 的 span 状态恒为 Unset
                //    ⇒ 这里多置 ERROR 就是遥测面漂移。
                logger.logBusinessErr(otelCtx, ErrCodes.ERROR_DB_SELECT, e);
                throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
            }

            // W2 密码比对
            checkDeadline();
            PasswordUtil.Outcome pwOutcome =
                    PasswordUtil.checkDetailed(req.getPassword(), user.getPassword());
            if (pwOutcome != PasswordUtil.Outcome.MATCHED) {
                // B 侧 DRIFT-004（已修，用户签字）：hash 格式非法时 Go 同样报密码错，对外行为逐字一致；
                // 但 B 侧额外留一道信号 —— Go 侧这一路完全静默。
                if (pwOutcome == PasswordUtil.Outcome.MALFORMED_HASH) {
                    span.setAttribute("auth.malformed_hash", true);
                    logger.logBusinessErr(otelCtx, ErrCodes.ERROR_REQUEST_PARAM,
                            new IllegalArgumentException(
                                    "stored bcrypt hash is malformed (username=" + req.getUsername() + ")"));
                }
                metrics.getLoginInternalError().increment();
                // 注：B 侧字符串是 "incorrect,username : "（逗号后没空格），保持原样
                logger.logInfo(otelCtx, "login failed: username or password incorrect,username : " + req.getUsername());
                return LoginResp.newBuilder().setStatus(1L).build();
            }

            // W3 封禁检查
            // ⚠️ `!= null` 这一半在实现里**恒为真**（UserEntity 的 `= 0L` 初始化值
            // + MyBatis callSettersOnNulls=false ⇒ NULL 行读出 0L 而非 null）。
            // 保留它是为了与 Go 的类型形态对齐。
            // 🔴 不要再把这行读成「status 为 NULL 就当没封禁」的 fail-open 缺陷：
            // Go 走的**正是**「NULL ⇒ 0 ⇒ status==1 判 false ⇒ 放行」这条路径
            // ⇒ 这里的 `== 1L` 判定与 Go 逐字一致，**NULL 行在两侧都放行**。
            if (user.getStatus() != null && user.getStatus() == 1L) {
                // 用户被封禁 → Status=2（无 metrics，与 Go 端一致）
                // 注：Go 字符串 username 前有空格、username 后有两个空格（typo），保持原样
                logger.logInfo(otelCtx, "login failed: user banned, username :  " + req.getUsername());
                return LoginResp.newBuilder().setStatus(2L).build();
            }

            // W4 成功
            metrics.getLoginSuccess().increment();
            logger.logInfo(otelCtx, "login success,username : " + req.getUsername());
            return LoginResp.newBuilder()
                    .setUid(user.getUid())
                    .setStatus(0L)
                    .build();
        } finally {
            span.end();
        }
    }

    // ============== 2. RegisterLogic → register() ==============

    /**
     * 对应 Go 端 {@code internal/logic/registerlogic.go}。
     *
     * <p>执行序（W1~W5）：
     * <pre>
     *   W1 查重 FindOneByUserName → 命中返 AlreadyExists
     *   W2 PasswordUtil.encrypt(req.Password) → bcrypt hash（cost=10）
     *   W3 Snowflake.nextId() → uid
     *   W4 insert(User{...})
     *   W5 metrics 成功 / fail_exists / db_error / internal_error
     * </pre>
     */
    public CreateUserResp register(CreateUserReq req) {
        Span span = tracer.spanBuilder("Action-User-Register")
                .setAttribute("biz.username", req.getUsername())
                .startSpan();
        Context otelCtx = Context.current().with(span);
        checkDeadline();

        try {
            // W1 查重
            try {
                userMapper.findOneByUserName(req.getUsername());
                metrics.getRegisterFailExists().increment();
                logger.logBusinessErr(otelCtx, ErrCodes.ERROR_USER_EXIST,
                        new RuntimeException("username has existed"));
                throw Status.ALREADY_EXISTS.withDescription("用户名已存在").asRuntimeException();
            } catch (NotFoundException e) {
                // 用户名可用，继续
            } catch (StatusRuntimeException e) {
                // 上面刚抛的 ALREADY_EXISTS：原样上抛，不能掉进下面的 db_error 分支
                throw e;
            } catch (Exception e) {
                // B 侧 DRIFT-009：Go 出处 registerlogic.go:50-54 ——「非 ErrorNotFound」单独成一支，
                // 打 db_error 指标 + ErrorDbSelect 日志 + span.RecordError，再返 Internal。
                metrics.getRegisterDbError().increment();
                span.recordException(e);
                logger.logBusinessErr(otelCtx, ErrCodes.ERROR_DB_SELECT, e);
                throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
            }

            // W2 bcrypt
            String hash;
            try {
                hash = PasswordUtil.encrypt(req.getPassword());
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛
                throw e;
            } catch (Exception e) {
                metrics.getRegisterInternalError().increment();
                span.recordException(e);
                // ⚠️ 不置状态：同 login 分支的说明（Go 的 RecordError 不改 span 状态）
                logger.logBusinessErr(otelCtx, ErrCodes.ERROR_SERVER_COMMON, e);
                throw Status.INTERNAL.withDescription("密码生成失败").asRuntimeException();
            }

            // W3 snowflake
            long uid;
            try {
                uid = Snowflake.nextId();
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛
                throw e;
            } catch (Exception e) {
                metrics.getRegisterInternalError().increment();
                span.recordException(e);
                logger.logBusinessErr(otelCtx, ErrCodes.ERROR_SERVER_COMMON, e);
                throw Status.INTERNAL.withDescription("uid生成失败").asRuntimeException();
            }

            // W4 Insert
            UserEntity u = new UserEntity();
            u.setUid(uid);
            u.setUsername(req.getUsername());
            u.setPassword(hash);
            u.setEmail(req.getEmail());
            u.setScore(0);
            u.setStatus(0L);
            if (req.getExtraInfoCount() > 0) {
                Map<String, String> map = new HashMap<>();
                req.getExtraInfoMap().forEach(map::put);
                u.setExtraInfo(map);
            }
            try {
                userMapper.insert(u);
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛
                throw e;
            } catch (Exception e) {
                metrics.getRegisterDbError().increment();
                span.recordException(e);
                logger.logBusinessErr(otelCtx, ErrCodes.ERROR_DB_UPDATE, e);
                throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
            }

            // W5 成功
            metrics.getRegisterSuccess().increment();
            logger.logInfo(otelCtx, "register success,uid : " + uid);
            return CreateUserResp.newBuilder().setUid(uid).build();
        } finally {
            span.end();
        }
    }

    // ============== 3. GetUserLogic → getUser() ==============

    /**
     * 对应 Go 端 {@code internal/logic/getuserlogic.go}。
     *
     * <p>出口：
     * <ul>
     *   <li>成功 → {@code GetUserResp{User:{Uid, Username, Email, ExtraInfo, Status}, Found=true}}</li>
     *   <li>NotFound → {@code StatusRuntimeException(NOT_FOUND, "用户不存在")}</li>
     *   <li>DB 异常 → {@code StatusRuntimeException(INTERNAL, "DB查询失败")}</li>
     * </ul>
     *
     * <p><b>关键决策</b>：D-003：NotFound → throw（不构造半成品 resp）；D-011：Status 字段必须填。
     * <p>没有 metrics / OTel span（Go 端 {@code getuserlogic.go} 也没埋点）。
     */
    public GetUserResp getUser(GetUserReq req) {
        checkDeadline();
        Context otelCtx = Context.current();
        UserEntity user;
        try {
            user = userMapper.findOneByUid(req.getUid());
        } catch (NotFoundException e) {
            logger.logBusinessErr(otelCtx, ErrCodes.ERROR_USER_NOT_EXIST, e);
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛
            throw e;
        } catch (Exception e) {
            logger.logBusinessErr(otelCtx, ErrCodes.ERROR_DB_SELECT, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        // ⚠️ B 侧 DRIFT-USERRPC-V-012：此处曾有一道「status 为 null ⇒ INTERNAL」的守卫，
        // 理由（GORM 把 NULL 扫进 int64 会报错）**已被证伪并撤销**
        // ⇒ 守卫已删除，行为与 Go 逐字一致。
        // ⇒ 下面 `setStatus(user.getStatus())` 的拆箱 NPE 风险已由 UserEntity 的 `= 0L` 初始化值
        //   + MyBatis callSettersOnNulls=false 消除：status IS NULL 时 setter 被跳过，
        //   getStatus() 返回 0L，不为 null。

        UserInfo.Builder b = UserInfo.newBuilder()
                .setUid(user.getUid())
                .setUsername(user.getUsername() == null ? "" : user.getUsername())
                .setEmail(user.getEmail() == null ? "" : user.getEmail())
                .setStatus(user.getStatus()); // D-011: Long（非 null）
        Map<String, String> extra = user.getExtraInfo();
        if (extra != null && !extra.isEmpty()) {
            b.putAllExtraInfo(extra);
        } else {
            b.putAllExtraInfo(new HashMap<>());
        }

        logger.logInfo(otelCtx, "search user success,uid : " + req.getUid());
        return GetUserResp.newBuilder()
                .setUser(b.build())
                .setFound(true)
                .build();
    }

    // ============== 4. UpdateUserLogic → updateUser() ==============

    /**
     * 对应 Go 端 {@code internal/logic/updateuserlogic.go}。
     *
     * <p>部分更新 —— 只更新「显式传入且非空」的字段：
     * <pre>
     *   W1 Username 重名检查（仅 in.Username 非空时）
     *   W2 按需 bcrypt（仅 in.Password 非空时）
     *   W3 Email 覆盖（仅非空时）
     *   W4 ExtraInfo 覆盖（仅非 nil 时）
     *   W5 updateUserById(uid, toUpdate) → 仅更新非空字段（D-017）
     *   W6 重读 user 构造 resp
     * </pre>
     *
     * <p>D-004：Username 重名检查<b>不排除自己</b> —— 若用户把自己的 Username 改成现值也会 AlreadyExists。
     * 没有 metrics / OTel span。
     */
    public UpdateUserResp updateUser(UpdateUserReq req) {
        checkDeadline();
        UserEntity toUpdate = new UserEntity();

        // W1 Username 重名检查
        if (!req.getUsername().isEmpty()) {
            UserEntity existing;
            try {
                existing = userMapper.findOneByUserName(req.getUsername());
            } catch (NotFoundException e) {
                existing = null;
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛
                throw e;
            } catch (Exception e) {
                // B 侧 DRIFT-009：Go 出处 updateuserlogic.go:41-44 —— 查库故障单独成一支
                logger.logBusinessErr(Context.current(), ErrCodes.ERROR_DB_SELECT, e);
                throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
            }
            if (existing != null) {
                // D-004：不排除自己
                logger.logBusinessErr(Context.current(), ErrCodes.ERROR_USER_EXIST,
                        new RuntimeException("username already exists: " + req.getUsername()));
                throw Status.ALREADY_EXISTS.withDescription("用户名已存在").asRuntimeException();
            }
            toUpdate.setUsername(req.getUsername());
        }

        // W2 bcrypt
        if (!req.getPassword().isEmpty()) {
            try {
                toUpdate.setPassword(PasswordUtil.encrypt(req.getPassword()));
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛
                throw e;
            } catch (Exception e) {
                // Go 出处 updateuserlogic.go:50
                logger.logBusinessErr(Context.current(), ErrCodes.ERROR_SERVER_COMMON, e);
                throw Status.INTERNAL.withDescription("密码生成失败").asRuntimeException();
            }
        }

        // W3 Email
        if (!req.getEmail().isEmpty()) {
            toUpdate.setEmail(req.getEmail());
        }

        // W4 ExtraInfo
        if (req.getExtraInfoCount() > 0) {
            Map<String, String> map = new HashMap<>();
            req.getExtraInfoMap().forEach(map::put);
            toUpdate.setExtraInfo(map);
        }

        // W5 Update（D-017：动态 JPQL 只写非空字段）
        try {
            userMapper.updateUserById(req.getUid(), toUpdate);
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛
            throw e;
        } catch (Exception e) {
            // Go 出处 updateuserlogic.go:65
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_DB_UPDATE, e);
            throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
        }

        // W6 重读
        UserEntity fresh;
        try {
            fresh = userMapper.findOneByUid(req.getUid());
        } catch (NotFoundException e) {
            // Go 出处 updateuserlogic.go:73
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_USER_NOT_EXIST, e);
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛
            throw e;
        } catch (Exception e) {
            // Go 出处 updateuserlogic.go:76
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_DB_SELECT, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        UserInfo.Builder b = UserInfo.newBuilder()
                .setUid(fresh.getUid())
                .setUsername(fresh.getUsername() == null ? "" : fresh.getUsername())
                .setEmail(fresh.getEmail() == null ? "" : fresh.getEmail());
        Map<String, String> extra = fresh.getExtraInfo();
        if (extra != null && !extra.isEmpty()) {
            b.putAllExtraInfo(extra);
        } else {
            b.putAllExtraInfo(new HashMap<>());
        }
        // 注：resp 不含 Status 字段，对齐 Go 端 UpdateUserResp.UserInfo 只填 Uid/Username/Email/ExtraInfo

        return UpdateUserResp.newBuilder().setUser(b.build()).build();
    }

    // ============== 5. DeleteUserLogic → deleteUser() ==============

    /**
     * 对应 Go 端 {@code internal/logic/deleteuserlogic.go}。
     *
     * <pre>
     *   W1 OTel span "Action-User-Delete"，属性 biz-uid=in.Uid
     *   W2 FindOneByUid(uid) → 必须存在
     *   W3 deleteUserByUid(uid)
     * </pre>
     *
     * <p>D-003：NotFound → StatusRuntimeException。
     * UN-1：Go 端 DeleteUser 不级联删 user_avatar_history；是否级联由 Postgres FK ON DELETE CASCADE 决定。
     */
    public DeleteUserResp deleteUser(DeleteUserReq req) {
        Span span = tracer.spanBuilder("Action-User-Delete")
                .setAttribute("biz-uid", req.getUid())
                .startSpan();
        // Go 出处 deleteuserlogic.go:35-36 —— spanCtx = tracer.Start(l.ctx, ...)，日志全部走 spanCtx，
        // 这样 LogBusinessErr 里的 trace_id 才能和这个 span 对上。
        Context otelCtx = Context.current().with(span);
        checkDeadline();

        try {
            // W2
            try {
                userMapper.findOneByUid(req.getUid());
            } catch (NotFoundException e) {
                // Go 出处 deleteuserlogic.go:43
                logger.logBusinessErr(otelCtx, ErrCodes.ERROR_USER_NOT_EXIST, e);
                throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛
                throw e;
            } catch (Exception e) {
                // Go 出处 deleteuserlogic.go:48-49（span.RecordError + ErrorDbSelect）
                span.recordException(e);
                logger.logBusinessErr(otelCtx, ErrCodes.ERROR_DB_SELECT, e);
                throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
            }

            // W3
            try {
                userMapper.deleteUserByUid(req.getUid());
            } catch (StatusRuntimeException e) {
                // B-1：DEADLINE_EXCEEDED 必须上抛
                throw e;
            } catch (Exception e) {
                // Go 出处 deleteuserlogic.go:57
                span.recordException(e);
                logger.logBusinessErr(otelCtx, ErrCodes.ERROR_DB_UPDATE, e);
                throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
            }

            return DeleteUserResp.newBuilder().setSuccess(true).build();
        } finally {
            span.end();
        }
    }

    // ============== 6. SelectAvatarLogic → selectAvatar() ==============

    /**
     * 对应 Go 端 {@code internal/logic/selectavatarlogic.go}。
     *
     * <pre>
     *   W1 校验 history_id trim 后非空
     *   W2 selectAvatarHistory(uid, historyId) —— 5 步原子事务（D-005）
     *   W3 构造 resp
     * </pre>
     *
     * <p>D-005 / D-014 / D-015：必须原子；history 查询必须带 uid 条件（防越权）。
     */
    public SelectAvatarResp selectAvatar(SelectAvatarReq req) {
        checkDeadline();
        // W1
        String historyId = avatarDomainService.normalizeHistoryId(req.getHistoryId());
        if (historyId.isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("history_id is required").asRuntimeException();
        }

        // W2
        UserAvatarHistoryEntity history;
        try {
            history = avatarHistoryMapper.selectAvatarHistory(userMapper, req.getUid(), historyId);
        } catch (NotFoundException e) {
            // Go 出处 selectavatarlogic.go:42（ErrorUserNotExist）
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_USER_NOT_EXIST, e);
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (AvatarHistoryNotFoundException e) {
            // Go 出处 selectavatarlogic.go:45 —— 它这里传的是 errmsg.Error(=500)，
            // 显然是漏填（错误码表里有 ErrorAvatarHistoryNotExist=1018）。
            // 日志码不参与 wire，用语义正确的 1018；响应码仍由 api 层按 Go 映射。
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_AVATAR_HISTORY_NOT_EXIST, e);
            throw Status.NOT_FOUND.withDescription("头像历史不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛
            throw e;
        } catch (Exception e) {
            // Go 出处 selectavatarlogic.go:48（ErrorDbUpdate）
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_DB_UPDATE, e);
            throw Status.INTERNAL.withDescription("DB更新失败").asRuntimeException();
        }

        // W3
        return SelectAvatarResp.newBuilder()
                .setAvatarUrl(history.getAvatarUrl() == null ? "" : history.getAvatarUrl())
                .setHistory(avatarDomainService.fromModel(history))
                .build();
    }

    // ============== 7. UploadAvatarLogic → uploadAvatar() ==============

    /**
     * 对应 Go 端 {@code internal/logic/uploadavatarlogic.go}。
     *
     * <pre>
     *   W1 校验 avatar_url trim 后非空
     *   W2 Snowflake.nextId() → history_id（D-013）
     *   W3 createAvatarHistory(uid, historyId, avatarURL, normalizedCT, sizeBytes) —— 4 步原子（D-005）
     *   W4 构造 resp
     * </pre>
     */
    public UploadAvatarResp uploadAvatar(UploadAvatarReq req) {
        checkDeadline();
        // W1
        String avatarUrl = avatarDomainService.normalizeAvatarUrl(req.getAvatarUrl());
        if (avatarUrl.isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("avatar_url is required").asRuntimeException();
        }

        // W2
        String historyId;
        try {
            historyId = String.valueOf(Snowflake.nextId());
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛
            throw e;
        } catch (Exception e) {
            // Go 出处 uploadavatarlogic.go:41（ErrorSnowflakeID）
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_SNOWFLAKE_ID, e);
            throw Status.INTERNAL.withDescription("history_id生成失败").asRuntimeException();
        }

        // W3
        String contentType = avatarDomainService.normalizeContentType(req.getContentType());
        UserAvatarHistoryEntity history;
        try {
            history = avatarHistoryMapper.createAvatarHistory(
                    userMapper, req.getUid(), historyId, avatarUrl, contentType, req.getSizeBytes());
        } catch (NotFoundException e) {
            // Go 出处 uploadavatarlogic.go:56（ErrorUserNotExist）
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_USER_NOT_EXIST, e);
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (AvatarHistoryNotFoundException e) {
            // createAvatarHistory 不会抛这个，保留兜底以免落进下面的 db_error 分支
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_AVATAR_HISTORY_NOT_EXIST, e);
            throw Status.NOT_FOUND.withDescription("头像历史不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛
            throw e;
        } catch (Exception e) {
            // Go 出处 uploadavatarlogic.go:59（ErrorDbInsert）
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_DB_INSERT, e);
            throw Status.INTERNAL.withDescription("DB插入失败").asRuntimeException();
        }

        // W4
        return UploadAvatarResp.newBuilder()
                .setAvatarUrl(avatarUrl)
                .setHistory(avatarDomainService.fromModel(history))
                .build();
    }

    // ============== 8. GetAvatarHistoryLogic → getAvatarHistory() ==============

    /**
     * 对应 Go 端 {@code internal/logic/getavatarhistorylogic.go}。
     *
     * <pre>
     *   W1 FindOneByUid(uid) → 用户必须存在
     *   W2 listAvatarHistory(uid, limit) → ORDER BY create_time DESC
     * </pre>
     *
     * <p>D-010：limit &lt;= 0 || limit &gt; 30 静默 clamp 到 30（mapper 内部已实现）。
     */
    public GetAvatarHistoryResp getAvatarHistory(GetAvatarHistoryReq req) {
        checkDeadline();
        // W1
        try {
            userMapper.findOneByUid(req.getUid());
        } catch (NotFoundException e) {
            // Go 出处 getavatarhistorylogic.go:34（ErrorUserNotExist）
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_USER_NOT_EXIST, e);
            throw Status.NOT_FOUND.withDescription("用户不存在").asRuntimeException();
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛
            throw e;
        } catch (Exception e) {
            // Go 出处 getavatarhistorylogic.go:37（ErrorDbSelect）
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_DB_SELECT, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        // W2（D-010：limit clamp 在 mapper.listAvatarHistory 内部完成）
        List<UserAvatarHistoryEntity> histories;
        try {
            histories = avatarHistoryMapper.listAvatarHistory(req.getUid(), req.getLimit());
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛
            throw e;
        } catch (Exception e) {
            // Go 出处 getavatarhistorylogic.go:43（ErrorDbSelect）
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_DB_SELECT, e);
            throw Status.INTERNAL.withDescription("DB查询失败").asRuntimeException();
        }

        List<com.rag.proto.user.AvatarHistoryItem> items = new ArrayList<>(histories.size());
        for (UserAvatarHistoryEntity h : histories) {
            items.add(avatarDomainService.fromModel(h));
        }

        return GetAvatarHistoryResp.newBuilder().addAllList(items).build();
    }

    // ============== 9. LogoutLogic → logout() ==============

    /**
     * 对应 Go 端 {@code internal/logic/logoutlogic.go}（全文 44 行 · 2026-10-31 逐行读过）。
     *
     * <p><b>Go 侧无 ctx 预检</b>：{@code SetexCtx(l.ctx,…)} 把 ctx 写绑进 Redis ⇒ ctx 过期 ⇒ 命令不下发（不写）
     * ⇒ 写失败落 ⇒ {@code codes.Internal}「注销失败,Redis写入异常」⇒ <b>handler 级码 = 5001</b>。
     *
     * <pre>
     *   W1 校验 token 非空（Go ⇒ codes.InvalidArgument「Token不能为空」）
     *   W2 Redis Setex("user:jwt_blacklist:%s", "banned", AccessExpire)（key 拼法 + TTL）
     * </pre>
     *
     * <p>D-006：Redis key 必须是字面量 {@code "user:jwt_blacklist:%s"}（HTTP 中间件用同一格式去查）；
     * D-007：TTL 必须等于 JWT 过期秒数（{@code UserAuth.AccessExpire=2592000}），不能写死 24h。
     */
    public LogoutResp logout(LogoutReq req) {
        // ⚠️ 入口**不设** deadline 闸门 —— Go logoutlogic.go 亦无 ctx 预检
        // ⇒ 是「让写去失败」不是「入口拒」⇒ 期限由共享层 Redis 期限代理感知 ⇒ handler 级码 = 5001（与 Go 同形）
        // W1
        if (req.getToken().isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("Token不能为空").asRuntimeException();
        }

        // W2（D-006 key + D-007 TTL）
        String key = "user:jwt_blacklist:" + req.getToken();
        long ttlSeconds = properties.getUserAuth().getAccessExpire();
        try {
            bizRedis.opsForValue().set(key, "banned", Duration.ofSeconds(ttlSeconds));
        } catch (StatusRuntimeException e) {
            // B-1：DEADLINE_EXCEEDED 必须上抛。
            // ⚠️ 本 catch **只**拦 grpc 的 StatusRuntimeException；共享层 Redis 期限代理抛的是
            //   **org.springframework.dao.QueryTimeoutException**（Spring 的，不是 grpc 的）⇒ 它**不**被本分支拦
            //   ⇒ 落进下面的 catch (Exception) ⇒ INTERNAL「注销失败,Redis写入异常」⇒ 与 Go 逐字同形。
            throw e;
        } catch (Exception e) {
            // 🔴 B 侧 DRIFT-USERRPC-V-012：原始异常必须进日志。
            //   修前：asRuntimeException() 造出的 StatusRuntimeException **cause=null**
            //        ⇒ 底层 Lettuce 异常（超时 / 连接失败）**完全看不见** ⇒ 排障被指向错误方向。
            //   ⚠️ **status 与 description 一个字都不改**（仍与 Go 逐字一致）⇒ 纯增可观测性，wire 面零影响。
            logger.logBusinessErr(Context.current(), ErrCodes.ERROR_DB_SELECT, e);
            throw Status.INTERNAL.withDescription("注销失败,Redis写入异常").asRuntimeException();
        }

        return LogoutResp.newBuilder().build();
    }
}
