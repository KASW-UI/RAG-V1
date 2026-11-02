package com.rag.message.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>message 域错误契约（码值 / 文案 / gRPC 双向转换）的锁定测试</b>。
 *
 * <p><b>B 出处（本次迁移的行为基线 · D16：B 侧 RAG 仓是唯一判定基准）</b>：
 * <pre>
 *   B: RAG  service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageErrorCode.java
 *      :25-90    20 个枚举常量（常量名 / 码值 / 声明顺序逐条照抄）
 *      :103-105 getCode()
 *   B: RAG  service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageErrorMessages.java
 *      :50-76   static 块：20 条文案逐条写入（:53-:74）
 *      :86-92   getMsg(int)：命中返回文案，未知码回落 ERROR 的文案 "FAIL"
 *   B: RAG  service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageGrpcError.java
 *      :76-106  内部类 CodeError（@JsonAutoDetect 屏蔽 Throwable 的 getter）
 *      :135-139 grpcError(Status.Code, int)：业务码查表文案塞进 status description
 *      :154-161 statusMessage(Throwable)：沿 cause 链取 status 的 description
 *      :185-198 msg → code 反查表（排除 SUCCESS / ERROR + first-write-wins 去重）
 *      :217-248 bizCodeFromError(Throwable)：判定顺序 = CodeError → status → 兜底 1015
 *      :255-272 mapStatusCodeToBizCode(Status.Code)：gRPC code class 兜底映射
 *      :300-314 extractStatus(Throwable)：沿 cause 链找 status（带自引用防环）
 *   B: RAG  service/message/common/errmsg.go:104-106（Go 侧 bizCodeFromError 的判定顺序原文）
 * </pre>
 *
 * <h2>本类钉住什么</h2>
 * <ol>
 *   <li><b>20 个码值 + 20 条文案</b>逐条钉死（L1 契约 · 客户端可见）；</li>
 *   <li><b>未知码回落 "FAIL"</b>（不是 null、不是空串、不抛异常）；</li>
 *   <li><b>gRPC code class → 业务码的兜底表</b>（7 个分支逐条）；</li>
 *   <li><b>判定顺序</b>：先判 {@code CodeError}（取它的 code）、再判 status；</li>
 *   <li><b>沿 cause 链解包</b>（B 侧 {@code DRIFT-MSG-COMMON-001} 的修复点）；</li>
 *   <li><b>{@code CodeError} 的 JSON 形状恰好两键</b>（不得泄漏 Throwable 的 getter）。</li>
 * </ol>
 *
 * <p>⚠️ 本类是<b>纯单元测试</b>（不执行 SQL、不装配容器）—— 它锁的是<b>纯函数型 L1 契约</b>，
 * 不属于「必须真触发」的范畴（真触发测试见
 * {@code com.rag.message.domain.service.MessageDomainServiceRealPgTest}）。
 */
class MessageErrorContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ============== 1. 20 个码值 ==============

    @Test
    @DisplayName("ErrCodes：20 个码值逐条照抄 B 侧 MessageErrorCode（200/500/1015/5001..5008/5101/5102/5201..5207）")
    void errCodesExposeTwentyStableValues() {
        assertEquals(200, ErrCodes.SUCCESS.getCode());
        assertEquals(500, ErrCodes.ERROR.getCode());
        assertEquals(1015, ErrCodes.CODE_SERVER_BUSY.getCode());
        assertEquals(5001, ErrCodes.ERROR_SERVER_COMMON.getCode());
        assertEquals(5002, ErrCodes.ERROR_NOT_FOUND.getCode());
        assertEquals(5003, ErrCodes.ERROR_INVALID_PARAM.getCode());
        assertEquals(5004, ErrCodes.ERROR_UNAUTHORIZED.getCode());
        assertEquals(5005, ErrCodes.ERROR_FORBIDDEN.getCode());
        assertEquals(5006, ErrCodes.ERROR_ALREADY_EXIST.getCode());
        assertEquals(5007, ErrCodes.ERROR_DB_SELECT.getCode());
        assertEquals(5008, ErrCodes.ERROR_DB_UPDATE.getCode());
        assertEquals(5101, ErrCodes.ERROR_USER_NOT_FOUND.getCode());
        assertEquals(5102, ErrCodes.ERROR_ADMIN_NOT_FOUND.getCode());
        assertEquals(5201, ErrCodes.ERROR_SELF_CHAT.getCode());
        assertEquals(5202, ErrCodes.ERROR_CONVERSATION_MISS.getCode());
        assertEquals(5203, ErrCodes.ERROR_NOTIFICATION_MISS.getCode());
        assertEquals(5204, ErrCodes.ERROR_CHAT_PENDING.getCode());
        assertEquals(5205, ErrCodes.ERROR_ADMIN_ONLY_INIT.getCode());
        assertEquals(5206, ErrCodes.ERROR_FIRST_MSG_TOO_LONG.getCode());
        assertEquals(5207, ErrCodes.ERROR_UNSUPPORTED_ROLE.getCode());

        assertEquals(20, ErrCodes.values().length, "B 侧 MessageErrorCode 恰好 20 个常量（:25-90）");
    }

    // ============== 2. 20 条文案 ==============

    @Test
    @DisplayName("ErrMsg：20 条英文文案逐条照抄 B 侧 MessageErrorMessages 的 static 块")
    void errMsgExposesTwentyEnglishTexts() {
        assertEquals("OK", ErrMsg.getMsg(200));
        assertEquals("FAIL", ErrMsg.getMsg(500));
        assertEquals("service busy", ErrMsg.getMsg(1015));
        assertEquals("internal server error", ErrMsg.getMsg(5001));
        assertEquals("record not found", ErrMsg.getMsg(5002));
        assertEquals("invalid parameter", ErrMsg.getMsg(5003));
        assertEquals("unauthorized", ErrMsg.getMsg(5004));
        assertEquals("forbidden", ErrMsg.getMsg(5005));
        assertEquals("already exists", ErrMsg.getMsg(5006));
        assertEquals("database query failed", ErrMsg.getMsg(5007));
        assertEquals("database update failed", ErrMsg.getMsg(5008));
        assertEquals("user not found", ErrMsg.getMsg(5101));
        assertEquals("admin not found", ErrMsg.getMsg(5102));
        assertEquals("self chat is not allowed", ErrMsg.getMsg(5201));
        assertEquals("conversation not found", ErrMsg.getMsg(5202));
        assertEquals("notification not found", ErrMsg.getMsg(5203));
        assertEquals("waiting for the other side to reply", ErrMsg.getMsg(5204));
        assertEquals("only admin can initiate this conversation", ErrMsg.getMsg(5205));
        assertEquals("first private message cannot exceed 50 characters", ErrMsg.getMsg(5206));
        assertEquals("unsupported sender role", ErrMsg.getMsg(5207));

        assertEquals(20, ErrMsg.allMsgs().size(), "B 侧 MSG_BY_CODE 恰好 20 条（:53-:74）");
    }

    @Test
    @DisplayName("ErrMsg.getMsg：未知码 ⇒ 回落 \"FAIL\"（不是 null、不是空串、不抛异常）")
    void unknownCodeFallsBackToFailText() {
        assertEquals("FAIL", ErrMsg.getMsg(99999));
        assertEquals("FAIL", ErrMsg.getMsg(-1));
        assertEquals("FAIL", ErrMsg.getMsg(0));
        assertEquals(ErrMsg.getMsg(ErrCodes.ERROR.getCode()), ErrMsg.getMsg(99999),
                "回落值必须等于 ERROR 码的文案");
    }

    @Test
    @DisplayName("ErrMsg.allMsgs：只读视图（运行期不可改）")
    void allMsgsIsUnmodifiable() {
        Map<Integer, String> all = ErrMsg.allMsgs();
        assertThrows(UnsupportedOperationException.class, () -> all.put(12345, "x"));
    }

    // ============== 3. gRPC code class → 业务码的兜底表 ==============

    @Test
    @DisplayName("bizCodeFromError：status description 反查不中时，按 gRPC code class 兜底映射（7 个分支）")
    void bizCodeFromErrorFallsBackByStatusCodeClass() {
        // ⚠️ 刻意用**不带 description** 的 status ⇒ 反查表必然查不中 ⇒ 走兜底分支
        assertEquals(5002, GrpcErrorMapper.bizCodeFromError(Status.NOT_FOUND.asRuntimeException()));
        assertEquals(5004, GrpcErrorMapper.bizCodeFromError(Status.UNAUTHENTICATED.asRuntimeException()));
        assertEquals(5005, GrpcErrorMapper.bizCodeFromError(Status.PERMISSION_DENIED.asRuntimeException()));
        assertEquals(5006, GrpcErrorMapper.bizCodeFromError(Status.ALREADY_EXISTS.asRuntimeException()));
        assertEquals(5003, GrpcErrorMapper.bizCodeFromError(Status.INVALID_ARGUMENT.asRuntimeException()));
        assertEquals(5001, GrpcErrorMapper.bizCodeFromError(Status.INTERNAL.asRuntimeException()));
        // 其它 code 一律兜底 1015
        assertEquals(1015, GrpcErrorMapper.bizCodeFromError(Status.UNKNOWN.asRuntimeException()));
        assertEquals(1015, GrpcErrorMapper.bizCodeFromError(Status.DEADLINE_EXCEEDED.asRuntimeException()));
        assertEquals(1015, GrpcErrorMapper.bizCodeFromError(Status.UNAVAILABLE.asRuntimeException()));
    }

    @Test
    @DisplayName("bizCodeFromError(null) ⇒ SUCCESS(200)")
    void nullErrorFallsBackToSuccess() {
        assertEquals(200, GrpcErrorMapper.bizCodeFromError(null));
    }

    // ============== 4. 判定顺序：先 CodeError，再 status ==============

    @Test
    @DisplayName("bizCodeFromError：先判 CodeError（取它的 code），再判 status")
    void codeErrorIsJudgedBeforeStatus() {
        // ⚠️ 5101（user not found）**不可能**由任何 gRPC code class 兜底映射产出
        //    ⇒ 取到 5101 就证明走的是 CodeError 分支，而不是 status 分支。
        CodeError codeError = GrpcErrorMapper.newCode(ErrCodes.ERROR_USER_NOT_FOUND.getCode());
        assertEquals(5101, GrpcErrorMapper.bizCodeFromError(codeError));

        // newCode：按码值查表拿文案（B 侧 MessageGrpcError.java:112-114）
        assertEquals("user not found", codeError.getMsg());
        // newCodeMsg：不查表，原样使用传入文案（B 侧 :120-122）
        CodeError custom = GrpcErrorMapper.newCodeMsg(5202, "custom text");
        assertEquals("custom text", custom.getMsg());
        assertEquals("ErrCode:5202, Errmsg:custom text", custom.getMessage());
    }

    @Test
    @DisplayName("bizCodeFromError：status 的 description 反查命中 ⇒ 取业务码（不是 code class 兜底）")
    void statusDescriptionReverseLookupWins() {
        // "notification not found" 是 5203 的文案 ⇒ 反查命中 5203（不是 NOT_FOUND 的兜底 5002）
        StatusRuntimeException sre =
                Status.NOT_FOUND.withDescription("notification not found").asRuntimeException();
        assertEquals(5203, GrpcErrorMapper.bizCodeFromError(sre));
        assertEquals("notification not found", GrpcErrorMapper.statusMessage(sre));
    }

    // ============== 5. 沿 cause 链解包（B 侧 DRIFT-MSG-COMMON-001 的修复点） ==============

    @Test
    @DisplayName("bizCodeFromError：StatusRuntimeException 被包进 RuntimeException ⇒ 仍按 status 映射")
    void bizCodeFromErrorUnwrapsStatusFromCauseChain() {
        // ⚠️ 这是 B 侧 MessageGrpcError.java:290-294 记录的 DRIFT-MSG-COMMON-001：
        //    旧实现只做 instanceof ⇒ 直接落到兜底 1015；Go 会 errors.As 解包后按 code class 映射。
        StatusRuntimeException inner = Status.NOT_FOUND.asRuntimeException();
        RuntimeException outer = new RuntimeException("rpc call failed", inner);

        assertEquals(5002, GrpcErrorMapper.bizCodeFromError(outer),
                "沿 cause 链解包后应落到 NOT_FOUND 的兜底 5002，而不是 1015");
        // ⚠️ 解包路径上 grpc-go 用**外层**文本覆盖 description（B 侧 :159-160 / :236-239）
        assertEquals("rpc call failed", GrpcErrorMapper.statusMessage(outer));
    }

    @Test
    @DisplayName("statusMessage：链上无 status ⇒ null（对应 Go 的 ok=false）")
    void statusMessageIsNullWhenNoStatusOnChain() {
        assertNull(GrpcErrorMapper.statusMessage(new IllegalStateException("boom")));
    }

    // ============== 6. grpcError：业务码文案塞进 status description ==============

    @Test
    @DisplayName("grpcError：业务码查表文案塞进 gRPC status description（wire 格式）")
    void grpcErrorPutsBizMsgIntoDescription() {
        StatusException se = GrpcErrorMapper.grpcError(
                Status.Code.FAILED_PRECONDITION, ErrCodes.ERROR_CHAT_PENDING.getCode());
        assertEquals(Status.Code.FAILED_PRECONDITION, se.getStatus().getCode());
        assertEquals("waiting for the other side to reply", se.getStatus().getDescription());
        // 直接命中（未解包）⇒ description 反查命中 5204
        assertEquals(5204, GrpcErrorMapper.bizCodeFromError(se));
    }

    // ============== 7. CodeError 的 JSON 形状 ==============

    @Test
    @DisplayName("CodeError 序列化成 JSON ⇒ 恰好两键 code/msg，不泄漏 Throwable 的 getter")
    void codeErrorSerializesToExactlyTwoKeys() throws Exception {
        String json = MAPPER.writeValueAsString(
                new CodeError(ErrCodes.ERROR_CONVERSATION_MISS.getCode(), "conversation not found"));

        JsonNode node = MAPPER.readTree(json);
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);

        assertEquals(List.of("code", "msg"), keys,
                "B 侧靠 @JsonAutoDetect(getterVisibility=NONE) 只暴露这两个 getter");
        assertEquals(5202, node.get("code").asInt());
        assertEquals("conversation not found", node.get("msg").asText());

        Set<String> forbidden = Set.of("message", "localizedMessage", "cause", "stackTrace");
        for (String leaked : forbidden) {
            assertFalse(keys.contains(leaked), "不得泄漏 Throwable 继承来的 getter：" + leaked);
        }
        assertFalse(json.contains("stackTrace"));
        assertFalse(json.contains("localizedMessage"));
    }

    @Test
    @DisplayName("CodeError.getMessage() 的文本格式是 L1 契约：\"ErrCode:%d, Errmsg:%s\"")
    void codeErrorMessageFormatIsStable() {
        assertEquals("ErrCode:5203, Errmsg:notification not found",
                new CodeError(5203, "notification not found").getMessage());
    }
}
