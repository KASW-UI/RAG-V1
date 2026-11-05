package com.rag.favorite.service;

import com.rag.common.exception.BusinessException;
import com.rag.common.logger.GoStyleLogger;
import com.rag.common.logger.LogOption;
import com.rag.common.observability.GoStyleRequestDeadline;
import com.rag.common.protection.ProtectionProperties;
import com.rag.favorite.contract.ErrCodes;
import com.rag.favorite.contract.ErrMsg;
import com.rag.favorite.contract.GrpcErrorMapper;
import com.rag.favorite.dto.favorite.FavoriteItem;
import com.rag.favorite.dto.folder.FavoriteFolder;
import com.rag.favorite.exception.FavoriteExceptions;
import com.rag.favorite.interceptor.FavoriteJwtAuthInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.context.Context;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Map;
import java.util.OptionalLong;

/**
 * <p><b>favorite-api 的共享支撑</b>：身份解析 + proto→DTO 映射 + 业务日志 + RPC 失败还原。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/service/FavoriteApiSupport.java}
 * （逐条照抄）。
 *
 * <p><b>Go 出处</b>：{@code service/favorite/api/internal/logic/favorite/helper.go:14-42}
 * <pre>
 * func extractUserID(ctx context.Context) (int64, error) {
 *     raw := ctx.Value("userId")
 *     if raw == nil { return 0, fmt.Errorf("ctx userId is nil") }
 *     switch v := raw.(type) {
 *     case json.Number: return v.Int64()
 *     case string:     return strconv.ParseInt(v, 10, 64)
 *     case int64:      return v, nil
 *     case int:        return int64(v), nil
 *     case float64:    return int64(v), nil
 *     default:         return 0, fmt.Errorf("ctx userId has unexpected type %T", raw)
 *     }
 * }
 * </pre>
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.api.service.FavoriteApiSupport}
 * ⇒ C 侧 {@code com.rag.favorite.service.FavoriteApiSupport}。
 *
 * <p><b>L4 差异（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧注入 {@code com.rag.common.spring.LoggerFactoryBean}
 *       并调 {@code logger.logInfo(Context.current(), ...)}；
 *       C 侧共享层把 logger 收成 {@link GoStyleLogger} 的<b>静态</b>入口
 *       （{@code GoStyleLogger.logInfo(Context, String, LogOption...)}）⇒ 本类改为静态调用。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 共享层的静态入口与 B 侧的 {@code LoggerFactoryBean}
 *       是<b>同一份 logger 状态</b>（共享层 {@code LoggerFactoryBean} 就是它的装配载体）
 *       ⇒ 日志的 service 标识、trace 关联、字段集合逐条一致。</li>
 *   <li><b>B 出处</b>：B 侧 {@code service/favorite/favorite-api/.../service/FavoriteApiSupport.java:57-59}
 *       （构造器注入）＋ 共享层 {@code com.rag.common.logger.GoStyleLogger:212,222}（静态入口）。</li>
 * </ol>
 */
@Component
public class FavoriteApiSupport {

    private static final Logger log = LoggerFactory.getLogger(FavoriteApiSupport.class);

    /**
     * B 侧 7 个 logic 在 {@code extractUserID} 失败时统一返回的码：
     * {@code favoritecommon.ErrorUnauthorized = 5004}
     * （如 {@code create_favorite_folder_logic.go:36-40}）。
     */
    private static final int ERROR_UNAUTHORIZED = ErrCodes.ERROR_UNAUTHORIZED;

    /**
     * <b>api 面自己这一跳的出向上界（毫秒）</b> —— 复刻 B 侧 {@code favorite-api → favorite-rpc}
     * <b>那一跳</b>的客户端硬上界。
     *
     * <h3>🔴 为什么 api 面要自持一个上界（D2 域内直连的结构性代价）</h3>
     * <p>B 侧的 api 面调 rpc 是<b>真的 gRPC 往返</b>，那一跳<b>自带 2s 客户端 cap</b>
     * （{@code service/favorite/favorite-api/src/main/resources/application.yml:196-197}）。
     * ⇒ ⇒ 慢下游时，<b>api 那一跳的 cap 先到</b>（它在 t=0 起算，rpc 侧那一跳在 t=ε 起算 ⇒
     * 同一个 2s 值 ⇒ api 的更早）⇒ ⇒ api 收到的是<b>自己那一次的 DEADLINE_EXCEEDED</b>
     * （<b>没有业务 description</b>）⇒ ⇒ {@code bizCodeFromError} 反查不出业务码 ⇒ 落
     * <b>1015（CODE_SERVER_BUSY / 「服务繁忙」）</b>。
     * <p>B 侧<b>自己写明了</b>这就是预期行为：
     * {@code favorite-api/.../client/FavoriteRpcClient.java:108-115}
     * 「Go 侧下游挂死时是 <b>2s 先到</b> ⇒ api 返回 <b>200 + 业务码 1015</b>」。
     *
     * <p>⚠️ <b>D2 把这一跳取消之后，2s cap 只剩「service→user-rpc」那一处</b>
     * ⇒ ⇒ 超时被<b>领域层</b>接住 ⇒ 按领域层自己的规则翻成 <b>5001</b>
     * ⇒ ⇒ <b>同一个故障，两侧业务码不同</b>（双侧活体对拍实测 ⇒ DRIFT-FAV-014）。
     *
     * <h3>🔴 为什么不「改领域层的映射」</h3>
     * <p>那会让 <b>gRPC 面</b>也变成 1015，而 B 侧 gRPC 面（rpc→rpc）本来就是 <b>5001</b>
     * ⇒ ⇒ <b>用一个面换另一个面</b> ⇒ ⇒ 不取。
     *
     * <h3>🔴 为什么值取自共享层的同一个配置键（不是新键）</h3>
     * <p>B 侧<b>两个面</b>各自一份 yml，但<b>值相同</b>（api {@code :196-197} = 2000 /
     * rpc {@code :220-221} = 2000）⇒ ⇒ D1 合并成<b>一个</b>键
     * （{@code rag.protection.outbound-deadline.cap-millis}）后**仍然是同一个数**
     * ⇒ ⇒ 本层<b>复用同一个键</b>（<b>不是</b>「第二个 cap 来源」）⇒ ⇒ 零新增 L1 配置面。
     * <p>共享层的那个键是<b>客户端硬上界</b>（装在<b>出向调用</b>上）；
     * 本层用它做<b>api→领域层这一跳</b>的预算 ⇒ ⇒ <b>同一个键、两个作用点</b>
     * ⇒ ⇒ 出向调用的实际 deadline = min(api 本层预算, 客户端 cap)
     * ⇒ ⇒ <b>api 本层的预算起点更早 ⇒ 恒定更紧 ⇒ 恒定先到</b>（与 B 侧同形）。
     *
     * <h3>§3.2 三段式（AGENTS.md §3.2）</h3>
     * <ol>
     *   <li><b>比 B 侧多做了什么</b>：B 侧的 api 面<b>不需要</b>自持上界 —— 它那一跳是真的 gRPC，
     *       客户端 cap <b>天然</b>装在那一跳上；D2 取消那一跳后，本层<b>补上</b>一个同值预算，
     *       以复原「api 自己那一跳先到、超时落 1015」这条语义。</li>
     *   <li><b>语义还一样吗</b>：<b>一样</b> —— ① 预算值与 B 侧同值（2000）② 生效点同为
     *       「api→下游」这一跳 ③ 超时后的业务码同为 1015（文案「服务繁忙」逐字一致）
     *       ④ <b>gRPC 面完全不受影响</b>（它不走本类 ⇒ 仍是 5001 ⇒ 与 B 侧 gRPC 面同形）
     *       ⑤ <b>没有换线程</b> ⇒ 出向 deadline 传播、OTel 上下文、事务边界<b>都不受影响</b>
     *       （共享层 {@code OutboundDeadlineInterceptor} 的类头明确写着「业务自己换线程 ⇒
     *       读不到载体 ⇒ 本类不生效」⇒ ⇒ 换线程方案<b>会打断</b> deadline 传播 ⇒ ⇒ 不取）。</li>
     *   <li><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/resources/application.yml:196-197}
     *       （api 面 cap = 2000）＋ {@code service/favorite/favorite-rpc/src/main/resources/application.yml:220-221}
     *       （rpc 面 cap = 2000）＋ {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/client/FavoriteRpcClient.java:108-115}
     *       （oracle 自述「2s 先到 ⇒ 1015」）＋ {@code service/favorite/favorite-common/src/main/java/com/rag/favorite/favorite/common/errmsg/FavoriteErrorCode.java:47}
     *       （CODE_SERVER_BUSY = 1015）。</li>
     * </ol>
     */
    private final long apiHopCapMillis;

    /**
     * 构造器注入 —— 本类原本<b>没有</b>任何依赖（全是静态方法 + 无参构造），
     * 为承载 api 面的出向上界而新增这一个依赖。
     *
     * @param protectionProperties 共享层的保护层配置（{@code rag.protection.outbound-deadline.*}）
     */
    public FavoriteApiSupport(ProtectionProperties protectionProperties) {
        this.apiHopCapMillis = protectionProperties.getOutboundDeadline().getCapMillis();
    }

    /**
     * 解析当前请求的 userId —— 对应 B 侧的 {@code extractUserID(ctx)}。
     *
     * <p><b>⚠️ 本层刻意【不】校验 claim 的取值</b>：B 侧
     * {@code handler.Authorize}（{@code rest/handler/authhandler.go:71-79}）只验签名与 exp，
     * 随后把 claim <b>原样</b>塞进 ctx；claim 能不能变成 int64 是<b>业务层</b>的判定。
     * ⇒ 解析失败走的是 <b>HTTP 200 + {code:5004}</b>，
     * <b>不是</b> 401（401 只由鉴权层的验签/黑名单失败产生）。
     *
     * <h3>为什么 {@code Double} 判为失败</h3>
     * <p>B 侧 go-zero 的 token 解析器是
     * {@code rest/token/tokenparser.go:122-124} 的
     * {@code jwt.NewParser(jwt.WithJSONNumber())} ⇒ JWT 里的数字 claim 一律是
     * {@code json.Number}（<b>string 的命名类型</b>），<b>永远不是 float64</b>。
     * ⇒ B 侧走的是 {@code case json.Number: return v.Int64()}，
     * 而 {@code json.Number("1.5").Int64()} 会<b>失败</b>（{@code strconv.ParseInt("1.5")} 报错）。
     * <p>所以 B 侧对 {@code "userId": 1.5} 的行为是<b>失败 → 5004</b>，
     * <b>不是</b>「截断成 1」。本实现保持一致：{@link Double} / {@link Float} 一律判失败。
     * （B 侧那个 {@code case float64} 分支在 JWT 路径上<b>不可达</b>，保留只是历史兼容。）
     */
    public long userId(HttpServletRequest request) {
        Object raw = request.getAttribute(FavoriteJwtAuthInterceptor.CLAIM_ATTRIBUTE);
        if (raw == null) {
            // B 侧: ctx userId is nil（service/favorite/api/internal/logic/favorite/helper.go:16-18）
            log.warn("ctx userId is nil, uri={}", request.getRequestURI());
            throw unauthorized();
        }
        if (raw instanceof String s) {
            // B 侧: strconv.ParseInt(v, 10, 64)（service/favorite/api/internal/logic/favorite/helper.go:24）
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.warn("ctx userId is not a valid int64 : {}", s);
                throw unauthorized();
            }
        }
        if (raw instanceof BigInteger bi) {
            // B 侧: json.Number("...").Int64() ⇒ strconv.ParseInt 越界即失败。（helper.go:21-22）
            // BigInteger.bitLength() >= 64 ⟺ 越出 int64 值域。
            if (bi.bitLength() >= 64) {
                log.warn("ctx userId is out of int64 range : {}", bi);
                throw unauthorized();
            }
            return bi.longValue();
        }
        if (raw instanceof Byte || raw instanceof Short
                || raw instanceof Integer || raw instanceof Long) {
            // B 侧: json.Number("123").Int64() —— 整数字面量精确等价（helper.go:21-22）
            return ((Number) raw).longValue();
        }
        if (raw instanceof Double || raw instanceof Float) {
            // B 侧: json.Number("1.5").Int64() 失败 ⇒ 5004（见类注释）
            log.warn("ctx userId is a non-integer number : {}", raw);
            throw unauthorized();
        }
        // B 侧: default 分支 —— unexpected type（helper.go:31-32）
        log.warn("ctx userId has unexpected type : {}", raw.getClass().getName());
        throw unauthorized();
    }

    /**
     * proto 的 {@code favorite.FavoriteFolder} → HTTP 出参 DTO。
     *
     * <p>B 侧出处：{@code get_favorite_folders_logic.go:58-66}。
     * ⚠️ <b>刻意不映射 {@code user_id}</b>：B 侧的 {@code types.FavoriteFolder}
     * （{@code types.go:42-47}）也没有这个字段 —— 它只存在于 rpc 层。
     *
     * <p>⚠️ <b>id 出参是字符串</b>：proto 的 {@code folder_id} 是 {@code int64}
     * （{@code proto/src/main/proto/favorite.proto:6}）⇒ Java 侧 getter 返回
     * {@code long}；而 B 侧的 {@code types.FavoriteFolder.FolderId} 是 {@code *int64}、
     * 它的 getter 返回 {@code String}（{@code favorite-api/.../dto/FavoriteFolder.java:56-58}，
     * 注释明写「出参为字符串（对齐 Go 的 {@code json:"folderId,string"}）」）
     * ⇒ ⇒ 这里必须显式转成字符串，否则 JSON 上会出现数字（与 B 侧不同形）。
     */
    public FavoriteFolder toFolderDto(com.rag.proto.favorite.FavoriteFolder proto) {
        return new FavoriteFolder(
                String.valueOf(proto.getFolderId()),
                proto.getName(),
                proto.getCreatedAt(),
                proto.getUpdatedAt());
    }

    /**
     * proto 的 {@code favorite.FavoriteItem} → HTTP 出参 DTO。
     *
     * <p>B 侧出处：{@code get_favorites_by_folder_logic.go:64-75}。
     * ⚠️ <b>刻意不映射 {@code user_id}</b>：同 {@link #toFolderDto}。
     * ⚠️ <b>两个 id 出参都是字符串</b>：同 {@link #toFolderDto} 的理由
     * （B 侧 {@code favorite-api/.../dto/FavoriteItem.java:72-74,82-84}）。
     */
    public FavoriteItem toItemDto(com.rag.proto.favorite.FavoriteItem proto) {
        return new FavoriteItem(
                String.valueOf(proto.getFavoriteId()),
                String.valueOf(proto.getFolderId()),
                proto.getTargetId(),
                proto.getTargetType(),
                proto.getTitle(),
                proto.getCover(),
                proto.getCreatedAt());
    }

    /**
     * 业务日志 —— 对应 B 侧的 {@code logger.LogInfo(ctx, msg, userLogOption(userID), ...)}
     * （{@code service/common/logger/logger.go:85}）。
     *
     * <p>⚠️ <b>日志失败不得影响业务</b>：B 侧的 logger 写不出去不会让请求失败，
     * 本实现用 try/catch 包住，保证语义一致。
     */
    public void logInfo(String msg, Long userId, String articleId) {
        try {
            GoStyleLogger.logInfo(Context.current(), msg, options(userId, articleId));
        } catch (Throwable t) {
            log.warn("logInfo failed : {}", t.getMessage());
        }
    }

    /**
     * 业务错误日志 —— 对应 B 侧的
     * {@code logger.LogBusinessErr(ctx, code, err, userLogOption(userID), articleLogOption(...))}
     * （{@code service/common/logger/logger.go:77}）。
     */
    public void logBusinessErr(int code, Throwable err, Long userId, String articleId) {
        try {
            GoStyleLogger.logBusinessErr(Context.current(), code, err, options(userId, articleId));
        } catch (Throwable t) {
            log.warn("logBusinessErr failed : {}", t.getMessage());
        }
    }

    /**
     * 构造 B 侧的两个 log option：
     * {@code userLogOption} = {@code logger.WithUserID(strconv.FormatInt(userID, 10))}
     * （{@code service/common/logger/logger.go:35}）、
     * {@code articleLogOption} = {@code logger.WithArticleID(strings.TrimSpace(articleID))}
     * （{@code service/common/logger/logger.go:42}）。
     *
     * <p><b>L4 差异</b>：B 侧的具名类 {@code WithUserID} / {@code WithArticleID}
     * ⇒ C 侧共享层 {@link LogOption#withUserId(String)} / {@link LogOption#withArticleId(String)}
     * （共享层 {@code common/logger/LogOption.java} 的静态工厂，
     * 其 javadoc 明写「对应 RAG 的 {@code WithUserID} 类 ⇒ 本方法即其 {@code of(userId)} 静态工厂」）。
     */
    private static LogOption[] options(Long userId, String articleId) {
        LogOption[] opts = new LogOption[2];
        opts[0] = LogOption.withUserId(userId == null ? "" : String.valueOf(userId));
        opts[1] = LogOption.withArticleId(articleId == null ? "" : articleId.trim());
        return opts;
    }

    /**
     * 调用下游，并把失败<b>还原成 B 侧的业务码</b>。
     *
     * <p>B 侧出处（7 个 logic 完全同构，以 {@code create_favorite_logic.go:58-63} 为例）：
     * <pre>
     *   rpcResp, rpcErr := l.svcCtx.FavoriteRpc.CreateFavorite(ctx, &amp;favoriteservice.CreateFavoriteReq{...})
     *   if rpcErr != nil {
     *       span.RecordError(rpcErr)
     *       code = codeFromRPCError(rpcErr)          // ← favoritecommon.BizCodeFromError
     *       logger.LogBusinessErr(ctx, code, rpcErr, userLogOption(userID), articleLogOption(req.TargetId))
     *       return nil, code
     *   }
     * </pre>
     *
     * <p>⇒ 失败一律是 <b>HTTP 200 + {code, msg, data:null}</b>，
     * <b>不是</b> 5xx。<b>任何</b>异常都走这条路径（含非 gRPC 状态的普通异常 ——
     * B 侧 {@code status.FromError} 对普通 error 返回 {@code ok=false} ⇒ 1015）。
     *
     * <p><b>L4 差异（D2 · 域内直连）</b>：B 侧此处是真的 gRPC 往返
     * （{@code @GrpcClient} + Nacos 寻址）；C 侧域内<b>直连</b>（D2）
     * ⇒ 本方法仍然保留 —— 它承载的是「<b>把任意异常还原成 B 侧业务码</b>」这条语义，
     * 而非「走 gRPC」这件事本身 ⇒ 直连路径照样经它还原。
     */
    public <T> T invokeRpc(RpcCall<T> call, Long userId, String articleId) {
        // 🔴🔴 D2 的结构性代价 ⇒ api 面**自持**这一跳的出向上界（复刻 B 侧 api→rpc 那一跳的 cap）。
        //    详见 {@link #apiHopCapMillis} 的 javadoc。
        OptionalLong hopDeadline = apiHopDeadlineNanos();
        try (GoStyleRequestDeadline.Scope scope = hopDeadline.isPresent()
                ? GoStyleRequestDeadline.bindAbsoluteNanos(hopDeadline.getAsLong())
                : GoStyleRequestDeadline.Scope.NOOP) {
            return call.invoke();
        } catch (Exception e) {
            // 🔴🔴 D2（域内直连）的关键适配：**先认领域异常，再走 gRPC 反查**。
            //
            // B 侧这一层收到的是 **gRPC status 异常**（api → FavoriteRpcClient → gRPC
            // → favorite-rpc 进程），业务码靠 description 文案反查还原
            // （favoritecommon.BizCodeFromError，favorite-common/errmsg.go:108-137）。
            //
            // D1 把 api + rpc 合并成单一进程、D2 把「域内自我 gRPC」取消
            // ⇒ 领域层直接抛 FavoriteExceptions.Rpc（它<b>自带</b> bizCode + msg 两个字段）
            // ⇒ 若直接丢给 GrpcErrorMapper.bizCodeFromError，它会因为「不是 gRPC status」
            //    而落到 **1015（CODE_SERVER_BUSY）** 分支
            // ⇒ ⇒ 业务码被整个换掉（5201/5202/5203/5304 … → 1015）
            //    且文案从「收藏夹名称已存在」变成「服务繁忙」⇒ **可观察的 L1 漂移**。
            //
            // ⚠️ 与 like 域的差别：like 域的同款分支兜底是 **5001**，
            //    favorite 域是 **1015**（B 侧 status.FromError 的 ok=false 分支）
            //    ⇒ 这里不能照抄 like 的数字，必须走 favorite 自己的 GrpcErrorMapper。
            int code = resolveBizCode(e);
            logBusinessErr(code, e, userId, articleId);
            throw new BusinessException(code, ErrMsg.getMsg(code));
        }
    }

    /**
     * 算 api 面<b>这一跳</b>的绝对 deadline（纳秒时基）—— 复刻 B 侧 api→rpc 那一跳的客户端 cap。
     *
     * <p><b>🔴 为什么是「绝对时刻」而不是「时长」</b>：共享层
     * {@link GoStyleRequestDeadline} 存的是<b>绝对</b>到期时刻（纳秒时基）⇒ ⇒ 出向调用按
     * <b>剩余</b>算 ⇒ ⇒ 「起点更早 ⇒ 剩余更少 ⇒ 恒定更紧」这条性质<b>自动成立</b>
     * ⇒ ⇒ 不需要「比客户端 cap 小一点」这种脆弱的偏移。
     *
     * <p><b>🔴 为什么取 min(请求剩余, 本层 cap)</b>：本层的预算<b>不能比请求预算更宽</b> ——
     * 否则会把 HTTP 层的 3s 预算<b>放宽</b>成 2s-from-now（请求已经跑了 2.5s 时会变成 4.5s）
     * ⇒ ⇒ 取 min ⇒ ⇒ 两个预算都成立。
     *
     * <p><b>⚠️ 没有本层上界时返回 {@link OptionalLong#empty()}</b>：共享层的
     * {@code capMillis} 出厂默认 <b>0 = 不启用</b> ⇒ ⇒ 此时<b>不给载体赋值</b>
     * ⇒ ⇒ 与 B 侧「没有 ⇒ 没有」同形（共享层 {@code GoStyleRequestDeadline.Scope#NOOP}
     * 的 javadoc 明写这条理由）。
     *
     * @return 绝对 deadline（纳秒）；无本层上界时为空
     */
    private OptionalLong apiHopDeadlineNanos() {
        if (apiHopCapMillis <= 0L) {
            return OptionalLong.empty();
        }
        long capNanos = apiHopCapMillis * 1_000_000L;
        long requestRemaining = GoStyleRequestDeadline.remainingNanos();
        long budget = requestRemaining < 0L ? capNanos : Math.min(requestRemaining, capNanos);
        return OptionalLong.of(System.nanoTime() + Math.max(0L, budget));
    }

    /**
     * 把一次失败还原成 B 侧的业务码 —— <b>api 面上界先到时优先落 1015</b>。
     *
     * <p><b>🔴 1015 这条分支的判据是「异常链里有 gRPC 的 {@code DEADLINE_EXCEEDED}」</b>，
     * <b>不是</b>「异常长得像超时」、<b>也不是</b>「耗时超过预算」——
     * 后两者都<b>不能</b>当判据：
     * <ul>
     *   <li><b>为什么不能靠异常类型判</b>：领域层已经把下游超时翻译成了
     *       {@code FavoriteExceptions.Rpc(5001)} ⇒ ⇒ <b>「超时」这个信息已经丢了</b>
     *       ⇒ ⇒ 只能由<b>异常链</b>来判 ⇒ ⇒ 这也正是 B 侧的判据形状
     *       （B 侧是「api 那一跳的 cap 先到 ⇒ api 看到裸 DEADLINE_EXCEEDED ⇒ 1015」）。</li>
     *   <li><b>为什么不能靠耗时判</b>：① <b>无牙齿</b> —— 绑定被去掉时「耗时超过预算」
     *       这个判据<b>仍然成立</b> ⇒ ⇒ 变异测试里它抓不到任何东西；
     *       ② <b>有竞态</b> —— 出向调用的到期时刻是「预算绝对时刻」与「投递延迟」的组合
     *       ⇒ 用「此刻是否已过预算」判 ⇒ 边界上会抖；
     *       ③ <b>分不清「超时」与「别的慢」</b> —— 领域层内部的慢 DB 也会让耗时变长
     *       ⇒ ⇒ 而 B 侧只把<b>自己那一跳的 cap 到点</b>判成 1015。</li>
     *   <li><b>为什么 status 是确定的</b>：gRPC 的到期定时器<b>不会提前</b>触发
     *       ⇒ ⇒ 超时异常<b>必然</b>在预算到期之后才被抛出来 ⇒ ⇒ 链上看到
     *       {@code DEADLINE_EXCEEDED} 就是「这一跳超时了」的<b>充要</b>信号。</li>
     *   <li><b>这与 B 侧同形</b>：B 侧只要 api 那一跳的 cap 先到 ⇒ <b>不论下游慢在哪</b>
     *       都回 1015 ⇒ ⇒ 本层也<b>不论原因</b>（只要链上是 DEADLINE_EXCEEDED）⇒ 都回 1015。</li>
     * </ul>
     *
     * @param e 捕获到的异常
     * @return B 侧的业务码
     */
    private int resolveBizCode(Exception e) {
        // 🔴🔴 **B 侧的判据形状**：api 面收到的是「**带 status 的错误**」
        //    ⇒ 若 status 是 DEADLINE_EXCEEDED ⇒ 它**没有业务 description**
        //    ⇒ ⇒ `bizCodeFromError` 反查不出业务码 ⇒ ⇒ 落 **1015（CODE_SERVER_BUSY）**
        //    （B 侧 favorite-common/.../FavoriteGrpcError.java:161-168 的两条兜底分支）
        //    ⇒ ⇒ C 侧的对应物 = **异常链里有 gRPC 的 DEADLINE_EXCEEDED**。
        if (carriesDeadlineExceeded(e)) {
            return ErrCodes.CODE_SERVER_BUSY;
        }
        // 🔴🔴 D2（域内直连）的关键适配：**先认领域异常，再走 gRPC 反查**。
        //
        // B 侧这一层收到的是 **gRPC status 异常**（api → FavoriteRpcClient → gRPC
        // → favorite-rpc 进程），业务码靠 description 文案反查还原
        // （favoritecommon.BizCodeFromError，favorite-common/errmsg.go:108-137）。
        //
        // D1 把 api + rpc 合并成单一进程、D2 把「域内自我 gRPC」取消
        // ⇒ 领域层直接抛 FavoriteExceptions.Rpc（它<b>自带</b> bizCode + msg 两个字段）
        // ⇒ 若直接丢给 GrpcErrorMapper.bizCodeFromError，它会因为「不是 gRPC status」
        //    而落到 **1015（CODE_SERVER_BUSY）** 分支
        // ⇒ ⇒ 业务码被整个换掉（5201/5202/5203/5304 … → 1015）
        //    且文案从「收藏夹名称已存在」变成「服务繁忙」⇒ **可观察的 L1 漂移**。
        //
        // ⚠️ 与 like 域的差别：like 域的同款分支兜底是 **5001**，
        //    favorite 域是 **1015**（B 侧 status.FromError 的 ok=false 分支）
        //    ⇒ 这里不能照抄 like 的数字，必须走 favorite 自己的 GrpcErrorMapper。
        if (e instanceof FavoriteExceptions.Rpc business) {
            return business.getBizCode();
        }
        return GrpcErrorMapper.bizCodeFromError(e);
    }

    /**
     * 异常链里有没有 gRPC 的 <b>DEADLINE_EXCEEDED</b>。
     *
     * <p><b>🔴 为什么要走 cause 链，而不是「看耗时」</b>：
     * <ul>
     *   <li><b>B 侧的判据是 status，不是时间</b>：B 侧 api 面拿到的是
     *       <b>自己那一跳的 DEADLINE_EXCEEDED</b>（无 description）⇒ ⇒ 反查不出业务码 ⇒ ⇒ 1015。
     *       「耗时超过 2s」只是它<b>导致</b>的结果，不是判据本身。</li>
     *   <li><b>耗时判据有竞态</b>：出向调用的到期时刻是「预算绝对时刻」与
     *       「投递延迟」的组合 ⇒ 用「此刻是否已过预算」来判 ⇒ 边界上会抖
     *       ⇒ ⇒ 而 status 是<b>确定</b>的 ⇒ ⇒ 用它。</li>
     *   <li><b>耗时判据还分不清「超时」与「别的慢」</b>：领域层内部的慢 DB 也会让耗时变长
     *       ⇒ ⇒ 而 B 侧只把<b>自己那一跳的 cap 到点</b>判成 1015。</li>
     * </ul>
     *
     * <p><b>🔴 为什么领域层必须保留 cause</b>：领域层把下游错误翻译成
     * {@code FavoriteExceptions.Rpc(5001)} ⇒ ⇒ 若不保留 cause，status 就被吃掉了
     * ⇒ ⇒ 本方法永远返回 false ⇒ ⇒ 落 5001 ⇒ ⇒ 与 B 侧不等（DRIFT-FAV-014）。
     * ⇒ ⇒ 领域层的两处下游失败分支都改成保留 cause
     * （{@code FavoriteUserGuard#onDownstreamFailure} 与
     * {@code FavoriteArticleSnapshotResolver#onDownstreamFailure}）。
     *
     * @param e 捕获到的异常
     * @return 链上是否有 DEADLINE_EXCEEDED
     */
    private static boolean carriesDeadlineExceeded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof StatusRuntimeException sre
                    && sre.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * 下游返回 {@code null} 响应 —— 对应 B 侧的 {@code if rpcResp == nil { ... 5001 }}。
     *
     * <p><b>B 侧出处（4 个 logic：create ×2 + get ×2）</b>：
     * <ul>
     *   <li>{@code create_favorite_folder_logic.go:57-62}、</li>
     *   <li>{@code create_favorite_logic.go:64-69}、</li>
     *   <li>{@code get_favorite_folders_logic.go:51-56}、</li>
     *   <li>{@code get_favorites_by_folder_logic.go:57-62}。</li>
     * </ul>
     * <pre>
     *   if rpcResp == nil {
     *       err = fmt.Errorf("favorite rpc returned nil response")
     *       ... ErrorServerCommon(5001)
     *   }
     * </pre>
     * ⚠️ <b>这 4 个 logic 只判 {@code nil}</b>：它们的响应消息里<b>根本没有 {@code success} 字段</b>
     * ⇒ 不存在「success=false」这条分支。
     *
     * <p>⚠️ <b>为什么与 {@link #nullOrUnsuccessfulResponse} 分开成两个出口</b>：
     * B 侧的<b>另 3 个</b> logic（delete ×2 + update ×1）判的是
     * {@code rpcResp == nil || !rpcResp.Success}，且错误文案是<b>另一句</b>
     * （{@code "favorite rpc returned unsuccessful response"}）。
     * ⇒ B 侧这两类是<b>分开的两条分支</b>（判定不同、措辞不同），
     * 本实现逐条对齐，<b>不合并</b> —— 合并会让服务端业务日志少掉一条 B 侧存在的措辞。
     */
    public BusinessException nullResponse(Long userId, String articleId) {
        IllegalStateException cause = new IllegalStateException("favorite rpc returned nil response");
        logBusinessErr(ErrCodes.ERROR_SERVER_COMMON, cause, userId, articleId);
        int code = ErrCodes.ERROR_SERVER_COMMON;
        return new BusinessException(code, ErrMsg.getMsg(code));
    }

    /**
     * 下游返回 {@code null} <b>或</b> {@code success=false} —— 对应 B 侧的
     * {@code if rpcResp == nil || !rpcResp.Success { ... 5001 }}。
     *
     * <p><b>B 侧出处（3 个 logic：delete ×2 + update ×1）</b>：
     * <ul>
     *   <li>{@code delete_favorite_logic.go:57-62}、</li>
     *   <li>{@code delete_favorite_folder_logic.go:57-62}、</li>
     *   <li>{@code update_favorite_folder_name_logic.go:59-64}。</li>
     * </ul>
     * <pre>
     *   if rpcResp == nil || !rpcResp.Success {
     *       err = fmt.Errorf("favorite rpc returned unsuccessful response")
     *       ... ErrorServerCommon(5001)
     *   }
     * </pre>
     * ⚠️ <b>B 侧的措辞是 {@code "unsuccessful"}（不含 "nil or"}</b> ⇒ 本实现用同一句。
     */
    public BusinessException nullOrUnsuccessfulResponse(Long userId, String articleId) {
        IllegalStateException cause = new IllegalStateException("favorite rpc returned unsuccessful response");
        logBusinessErr(ErrCodes.ERROR_SERVER_COMMON, cause, userId, articleId);
        int code = ErrCodes.ERROR_SERVER_COMMON;
        return new BusinessException(code, ErrMsg.getMsg(code));
    }

    /**
     * {@code null} → {@code ""}，对齐 B 侧的字符串零值。
     *
     * <p><b>为什么需要它</b>：B 侧的 {@code string} <b>没有 null</b>，缺字段就是 {@code ""}；
     * 而 protobuf 生成的 {@code setXxx(String)} 内部是 {@code Objects.requireNonNull(value)}
     * ⇒ 直接把 DTO 的 {@code null} 透进去会抛 NPE，让一个本该 200 的请求变成 500。
     * <p>两种触发路径：
     * <ul>
     *   <li><b>optional 字段缺席 / 显式 null</b>：B 侧保持零值 {@code ""}
     *       （{@code core/mapping/unmarshaler.go:856-862}），
     *       Java 侧 DTO 里是 {@code null} ⇒ <b>必须</b>归一；</li>
     *   <li><b>必填字段</b>：必填判定已保证非 null，这条路径实际不可达；
     *       仍统一走本方法，是为了让「B 侧的 string 不可能为 null」这条语义在类型上就成立，
     *       而不是散落在每个调用点的隐含前提。</li>
     * </ul>
     */
    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * {@code null} → {@code 0}，对齐 B 侧的 int64 零值。
     *
     * <p><b>为什么需要它</b>：protobuf 的 {@code setFolderId(long)} 是<b>原始类型</b>入参，
     * 而 DTO 里是包装类型 {@code Long} ⇒ 直接传进去会触发<b>自动拆箱 NPE</b>。
     * 与 {@link #nullToEmpty(String)} 同理：B 侧的 int64 也没有 null，缺字段就是 0。
     */
    public static long nullToZero(Long v) {
        return v == null ? 0L : v;
    }

    /**
     * 取 B 侧 form 通道里某个 key 的<b>首值</b> —— 完整复刻 go-zero 的
     * {@code GetFormValues} + {@code WithFromArray} 两段语义。
     *
     * <p><b>⚠️ 为什么不能用 {@code @RequestParam String} 一把梭</b>：
     * Spring 对重复参数会<b>用逗号拼成一个字符串</b>，而 B 侧的两段语义合起来是
     * 「先滤掉空值，再取下标 0」。两者在重复参数上结论完全不同：
     * <pre>
     *   ?folderId=123&amp;folderId=456
     *     Spring @RequestParam → "123,456" → Long.parseLong 失败 → 5003
     *     B 侧                  → 123        → 200
     * </pre>
     *
     * <h3>B 侧出处（两段都必须落，缺一不可）</h3>
     * <p><b>① 先滤空值</b> —— {@code rest/httpx/util.go:37-53} 的 {@code GetFormValues}：
     * <pre>
     *   for _, v := range values {
     *       if len(v) == 0 { continue }        // ← 空值整个丢掉（注释原文：ignore empty values）
     *       if n &lt; maxFormParamCount { filtered = append(filtered, v); n++ }
     *       else { return nil, fmt.Errorf("too many form values, ...") }
     *   }
     *   if len(filtered) &gt; 0 { params[name] = filtered }
     * </pre>
     * <p><b>② 再取首值</b> —— {@code rest/httpx/requests.go:27-31} 构造 form 通道的
     * unmarshaler 时开了 {@code mapping.WithFromArray()}，于是
     * {@code core/mapping/unmarshaler.go:835-846}：
     * <pre>
     *   if u.opts.fromArray {
     *       ...
     *       if val.Len() &gt; 0 { mapValue = val.Index(0).Interface() }   // ← 下标 0
     *   }
     * </pre>
     *
     * <p><b>⚠️ 顺序不能反</b>。真源探针（go-zero v1.10.2 + 逐字 {@code types.go} 结构体）实测：
     * <pre>
     *   ?folderId=123&amp;folderId=456  → 123   （首值生效）
     *   ?folderId=&amp;folderId=456     → 456   （空值先被滤掉，456 成了首值）
     *   ?folderId=456&amp;folderId=     → 456
     *   ?folderId=&amp;folderId=        → 字段缺席 ⇒ 5003
     *   ?folderId=abc&amp;folderId=456  → 5003（在首值 abc 上就 ParseInt 失败）
     *   ?folderid=123&amp;folderId=456  → 456   （key 大小写敏感，只认 folderId）
     * </pre>
     * 若把两步反过来（先取下标 0 再判空），{@code ?folderId=&amp;folderId=456} 会退化成
     * 「字段缺席」⇒ 5003，与 B 侧的 200 相反。
     *
     * <p><b>⚠️ 计数是跨所有 key 累计的</b>，不是按 key。超过 {@value #GO_MAX_FORM_PARAM_COUNT} 个非空值时
     * B 侧直接报错 ⇒ 本域落到 5003。
     *
     * @param request 当前请求（{@code getParameterMap()} 同时覆盖 query string 与
     *                {@code application/x-www-form-urlencoded} 请求体，与 B 侧的 {@code r.Form} 同源）
     * @param name    目标 key（<b>大小写敏感</b>）
     * @return 清洗后的首值；{@code null} 表示「该 key 缺席或只有空值」
     * @throws IllegalArgumentException 非空值总数超过 {@value #GO_MAX_FORM_PARAM_COUNT}
     */
    public static String goFormFirstValue(HttpServletRequest request, String name) {
        int seen = 0;
        String selected = null;
        for (Map.Entry<String, String[]> entry : parameterMap(request).entrySet()) {
            boolean target = entry.getKey().equals(name);
            for (String value : entry.getValue()) {
                // B 侧: `if len(v) == 0 { continue }` —— 空值在计数与取值之前就被丢掉。（go-zero@v1.10.2/rest/httpx/util.go:44）
                if (value == null || value.isEmpty()) {
                    continue;
                }
                // B 侧: `if n < maxFormParamCount { ... n++ } else { return nil, fmt.Errorf(...) }`（util.go:15,48）
                // ⇒ 恰好放行 2048 个，第 2049 个触发报错。
                if (seen >= GO_MAX_FORM_PARAM_COUNT) {
                    throw new IllegalArgumentException("too many form values");
                }
                seen++;
                // 取该 key 的【第一个】非空值 —— B 侧是 filtered 数组的 index 0。
                if (target && selected == null) {
                    selected = value;
                }
            }
        }
        return selected;
    }

    /**
     * 取 {@code request.getParameterMap()}，并把 <b>Tomcat 的「参数过多」异常</b>转成
     * {@link IllegalArgumentException}。
     *
     * <p><b>🔴 为什么必须转</b>（2026-10-25 真 Tomcat 实测发现的漂移）：
     * <ul>
     *   <li>Tomcat 在请求的参数<b>条目数</b>超过 {@code server.tomcat.max-parameter-count}
     *       时，会在 {@code getParameterMap()} 里抛
     *       {@code org.apache.tomcat.util.http.InvalidParameterException}
     *       （它<b>不是</b> {@code IllegalArgumentException} 的子类）。</li>
     *   <li>⇒ 它<b>绕过了</b> {@code FavoriteApiExceptionHandler#handleIllegalArgument}
     *       ⇒ 落到 {@code handleAny} 兜底 ⇒ <b>HTTP 500 + {code:5001}</b>。</li>
     *   <li>而 B 侧的对应场景是 {@code httpx.GetFormValues} 超过
     *       {@code maxFormParamCount=2048} ⇒ {@code httpx.Parse} 失败
     *       ⇒ handler 落 <b>HTTP 200 + {code:5003, msg:"参数错误"}</b>。</li>
     * </ul>
     * ⇒ 两侧在<b>同一个输入</b>下是<b>不同状态码 + 不同业务码</b>（500/5001 vs 200/5003）——
     * 这是可观察的 L1 差异。
     *
     * <p><b>⇒ 本方法把「容器层的参数上限」与「应用层的参数上限」统一收敛到 B 侧的出口</b>：
     * 无论是谁先发现「参数太多」，对外都只呈现 B 侧的那一种形态（<b>200 + 5003</b>）。
     *
     * <p>⚠️ <b>为什么 catch 的是 {@link RuntimeException} 而不是 Tomcat 的类</b>：
     * 本方法<b>只包住 {@code getParameterMap()} 这一行</b>（范围极窄），
     * 且不把 favorite 域的编译期依赖绑到 Tomcat 的内部类上
     * （{@code org.apache.tomcat.util.http} 不是 Tomcat 的公开 API 面）。
     */
    private static Map<String, String[]> parameterMap(HttpServletRequest request) {
        try {
            return request.getParameterMap();
        } catch (RuntimeException e) {
            // Tomcat 的 InvalidParameterException（参数条目数超过 max-parameter-count）在此抛出。
            // ⚠️ 不把原异常吞掉：挂到 cause 上，日志里仍能看到「是哪个容器限制触发的」。
            throw new IllegalArgumentException("too many request parameters: " + e.getMessage(), e);
        }
    }

    /**
     * B 侧的 form 值总数上限 —— {@code rest/httpx/util.go:15}：
     * <pre>
     * const maxFormParamCount = 2048
     * </pre>
     */
    private static final int GO_MAX_FORM_PARAM_COUNT = 2048;

    /** 下游调用的函数式接口（stub 方法不声明 checked exception，故不抛）。 */
    @FunctionalInterface
    public interface RpcCall<T> {
        T invoke();
    }

    /**
     * 构造 B 侧的 {@code favoritecommon.ErrorUnauthorized}（5004）业务异常。
     *
     * <p>B 侧: {@code return nil, favoritecommon.ErrorUnauthorized}
     * （{@code service/favorite/common/errmsg.go:17}）⇒
     * handler 写 {@code {code:5004, msg:"未登录或登录已失效", data:null}}，<b>HTTP 200</b>。
     */
    private static BusinessException unauthorized() {
        return new BusinessException(ERROR_UNAUTHORIZED,
                ErrMsg.getMsg(ERROR_UNAUTHORIZED));
    }
}
