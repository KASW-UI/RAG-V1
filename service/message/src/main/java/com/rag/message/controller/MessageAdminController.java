package com.rag.message.controller;

import com.rag.common.exception.BusinessException;
import com.rag.message.contract.ErrCodes;
import com.rag.message.contract.ErrMsg;
import com.rag.message.dto.chat.AdminSendChatReq;
import com.rag.message.dto.chat.SendChatResp;
import com.rag.message.dto.common.EmptyReq;
import com.rag.message.dto.common.PageReq;
import com.rag.message.dto.conversation.ConversationListResp;
import com.rag.message.dto.conversation.ConversationMessagesReq;
import com.rag.message.dto.conversation.ConversationMessagesResp;
import com.rag.message.dto.notification.AdminSendNotificationReq;
import com.rag.message.dto.notification.AdminSendNotificationResp;
import com.rag.message.dto.read.MarkConversationReadReq;
import com.rag.message.service.MessageApiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p><b>职责</b>：message 域<b>管理侧</b>的 5 个 HTTP 端点 —— 只做「解析 + 转交」，
 * <b>不含任何业务判断</b>。
 *
 * <p><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/controller/MessageAdminController.java}
 * （1-144 行；类声明 {@code :55-57}；构造器 {@code :65-75}；5 个端点 {@code :82-133}；
 * 零值辅助 {@code :141-143}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li><b>5 个 case service ⇒ 1 个 {@code MessageApiService}</b>（映射表 §1.2 决策锁）；</li>
 *         <li><b>包名</b> {@code com.rag.message.message.api.controller} → {@code com.rag.message.controller}
 *             （D7 包压平）；</li>
 *         <li><b>{@code GoStyleRequest.normalize(req, XxxReq::new)} ⇒ 私有静态零值辅助</b>
 *             —— 详见 {@link #zeroIfNull(PageReq)} 等 3 个辅助方法的 javadoc。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>5 个端点的 path + method 逐条未动</b>（<b>全是 POST</b>，
 *             {@code /message/v1/admin} 前缀逐字保留）；</li>
 *         <li>{@code @RequestBody(required = false)} <b>逐条保留</b>；</li>
 *         <li>零值归一与必填判定的<b>顺序</b>逐条未动。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号（逐方法另给行号）。</li>
 * </ol>
 *
 * <p><b>5 个端点与 HTTP 方法逐条对齐</b>（B 侧 Go {@code routes.go} 第二段：
 * {@code server.AddRoutes(..., rest.WithJwt(AdminAuth.AccessSecret), rest.WithPrefix("/message/v1/admin"))}）：
 * <pre>
 *   POST /message/v1/admin/conversations/list       ← ListAdminConversationsHandler
 *   POST /message/v1/admin/conversations/messages   ← GetAdminConversationMessagesHandler
 *   POST /message/v1/admin/conversations/read       ← MarkAdminConversationReadHandler
 *   POST /message/v1/admin/conversations/send       ← SendAdminChatMessageHandler
 *   POST /message/v1/admin/notifications/send       ← SendAdminNotificationHandler
 * </pre>
 *
 * <p><b>鉴权</b>：本前缀由 {@code MessageAdminJwtAuthInterceptor} 单独守卫
 * （user secret 在这里会被拒）。业务身份（操作人 id）由
 * {@link MessageApiService} 从 JWT claim 解析后作为 {@code SenderId}/{@code OperatorId} 下发，
 * <b>不</b>来自请求体 —— B 侧同样只从 {@code ctx.Value("userId")} 取。
 *
 * <p><b>⚠️ {@code @RequestBody(required = false)} 是必需写法，不是省事</b>：
 * Go 的 handler 一定会调 {@code httpx.Parse}，非法 JSON 会让解析失败 →
 * 本服务输出 {@code 200 + {code:5001}}。若<b>完全不声明</b> {@code @RequestBody}，
 * Spring 根本不读 body，畸形 JSON 就会被静默忽略、请求照常成功 ——
 * 那是凭空多出一条 Go 没有的路径。
 */
@RestController
@RequestMapping("/message/v1/admin")
public class MessageAdminController {

    private final MessageApiService apiService;

    /**
     * 构造器注入 —— B 侧注入 5 个 case service，本仓注入 1 个聚合 service。
     *
     * <p><b>B 出处</b>：{@code .../controller/MessageAdminController.java:65-75}（B 侧 5 个参数的构造器）。
     *
     * @param apiService HTTP 层 13 个用例的编排（构造器注入）
     */
    public MessageAdminController(MessageApiService apiService) {
        this.apiService = apiService;
    }

    // ============== 会话 ==============

    /**
     * 会话列表（admin）—— Go {@code types.go PageReq}（3 个字段全部 {@code ,optional}）。
     *
     * <p><b>B 出处</b>：{@code .../controller/MessageAdminController.java:82-86}。
     *
     * @param req         分页请求（可空 ⇒ 零值）
     * @param httpRequest 当前请求（承载 claim）
     * @return 会话列表信封
     */
    @PostMapping("/conversations/list")
    public ConversationListResp listConversations(@Valid @RequestBody(required = false) PageReq req,
                                                  HttpServletRequest httpRequest) {
        return apiService.listAdminConversations(zeroIfNull(req), httpRequest);
    }

    /**
     * 会话消息列表（admin）—— Go {@code ConversationMessagesReq}，
     * {@code conversation_id} 必填且 {@code ,string}。
     *
     * <p><b>B 出处</b>：{@code .../controller/MessageAdminController.java:92-98}。
     *
     * @param req         会话消息请求（可空 ⇒ 零值 ⇒ 必填判定失败 ⇒ 200 + 5001）
     * @param httpRequest 当前请求（承载 claim）
     * @return 会话消息列表信封
     */
    @PostMapping("/conversations/messages")
    public ConversationMessagesResp getConversationMessages(
            @Valid @RequestBody(required = false) ConversationMessagesReq req,
            HttpServletRequest httpRequest) {
        return apiService.getAdminConversationMessages(zeroIfNull(req), httpRequest);
    }

    /**
     * 标记会话已读（admin）—— Go {@code MarkConversationReadReq}，{@code conversation_id} 必填。
     *
     * <p><b>B 出处</b>：{@code .../controller/MessageAdminController.java:103-108}。
     *
     * @param req         标记已读请求（可空 ⇒ 零值 ⇒ 必填判定失败 ⇒ 200 + 5001）
     * @param httpRequest 当前请求（承载 claim）
     * @return 空信封
     */
    @PostMapping("/conversations/read")
    public EmptyReq markConversationRead(@Valid @RequestBody(required = false) MarkConversationReadReq req,
                                         HttpServletRequest httpRequest) {
        return apiService.markAdminConversationRead(zeroIfNull(req), httpRequest);
    }

    /**
     * 管理员发私信 —— Go {@code AdminSendChatReq}，
     * {@code recipient_id} 与 {@code content} 必填。
     *
     * <p><b>B 出处</b>：{@code .../controller/MessageAdminController.java:114-119}。
     *
     * @param req         发私信请求（可空 ⇒ 零值 ⇒ 必填判定失败 ⇒ 200 + 5001）
     * @param httpRequest 当前请求（承载 claim）
     * @return 发消息回执信封
     */
    @PostMapping("/conversations/send")
    public SendChatResp sendChat(@Valid @RequestBody(required = false) AdminSendChatReq req,
                                 HttpServletRequest httpRequest) {
        return apiService.sendAdminChatMessage(zeroIfNull(req), httpRequest);
    }

    // ============== 通知 ==============

    /**
     * 管理员发通知 —— Go {@code AdminSendNotificationReq}，{@code title} / {@code content} 必填，
     * {@code extra} 需要 presence 语义（缺席 vs 显式 null 结局相反）。
     *
     * <p><b>B 出处</b>：{@code .../controller/MessageAdminController.java:127-133}。
     *
     * @param req         发通知请求（可空 ⇒ 零值 ⇒ 必填判定失败 ⇒ 200 + 5001）
     * @param httpRequest 当前请求（承载 claim）
     * @return 发通知回执信封
     */
    @PostMapping("/notifications/send")
    public AdminSendNotificationResp sendNotification(
            @Valid @RequestBody(required = false) AdminSendNotificationReq req,
            HttpServletRequest httpRequest) {
        return apiService.sendAdminNotification(zeroIfNull(req), httpRequest);
    }

    // ============== 零值归一（B 侧 GoStyleRequest.normalize 的等价物） ==============

    /**
     * 无必填字段的 DTO 归一 —— 空 body 与 {@code {}} 在 Go 侧等价（零值 struct）。
     *
     * <p><b>⚠️ B 侧用 {@code GoStyleRequest.normalize(req, PageReq::new)}，C 侧因 record 不可变
     * 改为显式零值构造</b>：
     * <ul>
     *   <li>B 侧 {@code PageReq} <b>不</b>实现 {@code GoStyleRequiredFields}（零必填字段）
     *       ⇒ B 侧对它<b>手写</b>了等价的零值归一（{@code MessageAdminController.java:141-143}
     *       的 {@code zeroIfNull}），<b>没有</b>走 {@code GoStyleRequest.normalize}；</li>
     *   <li>C 侧 DTO 是 <b>immutable record</b>（D5 + A9）⇒ 无法「new 出来后补字段」，
     *       只能显式构造零值 record ⇒ 语义等价（B 侧零值 struct 的字段全为 Go 零值：
     *       {@code offset=0} / {@code limit=0} / {@code unread_only=false}）。</li>
     * </ul>
     *
     * @param req Spring 反序列化结果（可能为 {@code null}）
     * @return 非 null 的 DTO
     */
    private static PageReq zeroIfNull(PageReq req) {
        return req == null ? new PageReq(0, 0, false) : req;
    }

    /**
     * {@code ConversationMessagesReq} 的零值归一 ＋ 必填判定。
     *
     * <p><b>⚠️ B 侧用 {@code GoStyleRequest.normalize(req, ConversationMessagesReq::new)}，
     * C 侧因 record 不可变改为显式零值构造</b>：
     * <ul>
     *   <li>B 侧 {@code normalize} 做<b>两段</b>：① {@code req == null} ⇒ 造零值 struct；
     *       ② {@code assertGoRequiredFields()}（{@code conversation_id} 必填）；</li>
     *   <li>C 侧第 ① 段 = 本方法显式构造零值 record（record 不可变 ⇒ 不能 new 完再补字段）；</li>
     *   <li>C 侧第 ② 段 = <b>Bean Validation</b>（D5）：body <b>存在</b>时由
     *       {@code @Valid} + {@code @NotNull} 判掉（抛 {@code MethodArgumentNotValidException}
     *       ⇒ advice 映射成 200 + 5001）；
     *       body <b>缺席</b>时 {@code @Valid} 不生效（Spring 对 {@code null} 不校验）⇒
     *       <b>本方法补上这一步</b>：零值 struct 的必填字段必为 {@code null}
     *       ⇒ 判定必然失败 ⇒ 抛业务异常 ⇒ 同样落成 200 + 5001。</li>
     * </ul>
     *
     * <p><b>为什么必须是 5001 而不是 5003</b>：B 侧的 {@code GoRequiredFieldException}
     * <b>不是</b> {@code CodeError} ⇒ message api 的 {@code httpx.SetErrorHandler}
     * 走 default 分支 ⇒ {@code ErrorServerCommon}（5001）+ {@code "internal server error"}。
     *
     * @param req Spring 反序列化结果（可能为 {@code null}）
     * @return 非 null 的 DTO
     * @throws BusinessException 空 body ⇒ 必填缺失 ⇒ 5001
     */
    private static ConversationMessagesReq zeroIfNull(ConversationMessagesReq req) {
        if (req != null) {
            return req;
        }
        ConversationMessagesReq zero = new ConversationMessagesReq(null, 0, 0);
        if (zero.conversationId() == null) {
            throw missingRequiredField();
        }
        return zero;
    }

    /**
     * {@code MarkConversationReadReq} 的零值归一 ＋ 必填判定。
     *
     * <p>论证与 {@link #zeroIfNull(ConversationMessagesReq)} 完全同构
     * （B 侧 {@code .../controller/MessageAdminController.java:106-107} 用
     * {@code GoStyleRequest.normalize}；C 侧 record 不可变 ⇒ 显式零值构造 ＋ 显式必填判定）。
     *
     * @param req Spring 反序列化结果（可能为 {@code null}）
     * @return 非 null 的 DTO
     * @throws BusinessException 空 body ⇒ 必填缺失 ⇒ 5001
     */
    private static MarkConversationReadReq zeroIfNull(MarkConversationReadReq req) {
        if (req != null) {
            return req;
        }
        MarkConversationReadReq zero = new MarkConversationReadReq(null);
        if (zero.conversationId() == null) {
            throw missingRequiredField();
        }
        return zero;
    }

    /**
     * {@code AdminSendChatReq} 的零值归一 ＋ 必填判定
     * （必填字段 = {@code recipient_id} 与 {@code content}）。
     *
     * <p>论证与 {@link #zeroIfNull(ConversationMessagesReq)} 完全同构
     * （B 侧 {@code .../controller/MessageAdminController.java:117-118}）。
     *
     * @param req Spring 反序列化结果（可能为 {@code null}）
     * @return 非 null 的 DTO
     * @throws BusinessException 空 body ⇒ 必填缺失 ⇒ 5001
     */
    private static AdminSendChatReq zeroIfNull(AdminSendChatReq req) {
        if (req != null) {
            return req;
        }
        AdminSendChatReq zero = new AdminSendChatReq(null, null, null);
        if (zero.recipientId() == null || zero.content() == null) {
            throw missingRequiredField();
        }
        return zero;
    }

    /**
     * {@code AdminSendNotificationReq} 的零值归一 ＋ 必填判定
     * （必填字段 = {@code title} 与 {@code content}）。
     *
     * <p>论证与 {@link #zeroIfNull(ConversationMessagesReq)} 完全同构
     * （B 侧 {@code .../controller/MessageAdminController.java:131-132}）。
     *
     * <p>⚠️ {@code extra} <b>刻意不做</b>「显式 null」判定：B 侧的
     * {@code assertGoRequiredFields} 只判 {@code title} / {@code content}
     * （{@code ,optional vs ,omitempty} 的取证见该 DTO 的类注释）。
     *
     * @param req Spring 反序列化结果（可能为 {@code null}）
     * @return 非 null 的 DTO
     * @throws BusinessException 空 body ⇒ 必填缺失 ⇒ 5001
     */
    private static AdminSendNotificationReq zeroIfNull(AdminSendNotificationReq req) {
        if (req != null) {
            return req;
        }
        AdminSendNotificationReq zero = new AdminSendNotificationReq(false, null, 0, null, null, null);
        if (zero.title() == null || zero.content() == null) {
            throw missingRequiredField();
        }
        return zero;
    }

    /**
     * 「必填字段缺失」的统一出口 —— 对齐 B 侧 {@code GoRequiredFieldException} 的
     * <b>最终 wire 形状</b>。
     *
     * <p>B 侧：{@code GoRequiredFieldException} → message api 的
     * {@code httpx.SetErrorHandler} 的 default 分支 ⇒
     * {@code HTTP 200 + {code:5001, msg:"internal server error", data:null}}。
     * 本仓用 {@link BusinessException} 承载同一个 (code, msg)，
     * 由 {@link com.rag.message.advice.MessageApiExceptionHandler} 渲染成同样的信封。
     *
     * @return 业务码 5001 的业务异常
     */
    private static BusinessException missingRequiredField() {
        int code = ErrCodes.ERROR_SERVER_COMMON.getCode();
        return new BusinessException(code, ErrMsg.getMsg(code));
    }
}
