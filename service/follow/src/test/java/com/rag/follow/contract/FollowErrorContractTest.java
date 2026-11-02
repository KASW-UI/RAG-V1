package com.rag.follow.contract;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>follow 域 L1 错误契约（纯单测 · 不需要容器）</b> —— 16 个码值 + 16 条文案 + 回落 + gRPC 反查。
 *
 * <h2>B 出处（期望值逐条抄自 B 侧源码，<b>不是</b>抄当前实现）</h2>
 * <pre>
 *   码值：B 侧 service/follow/follow-common/src/main/java/com/rag/follow/follow/common/errmsg/
 *         FollowErrorCode.java:42 起（枚举 16 项，码值逐字照抄）
 *   文案：B 侧 同包 FollowErrorMessages.java:52-54（整表 16 条）
 *   回落：B 侧 GetErrMsg(code) 未命中 ⇒ return codeMsg[Error] ⇒ "FAIL"
 *   反查：B 侧 BizCodeFromError(err) —— **先按 description 反查文案**，未命中才按 gRPC 码 switch
 * </pre>
 *
 * <h2>为什么这类断言用纯单测就够（而 HTTP 层那些要真 Tomcat）</h2>
 * <p>码表与反查表是<b>纯静态数据 + 纯函数</b>：不查 DB、不走容器、不产生副作用
 * ⇒ 「类上有注解」「方法存在」就是完整的机制证据（与 §3.3.1 的「要读消费点」不冲突：
 * 这里被测的<b>就是</b>那张表本身）。
 * <p>⚠️ <b>但</b>「gRPC 错误出口必须走 {@code onError} 且 description 保留业务文案」
 * 是<b>传输层</b>性质 ⇒ 必须真 gRPC 调用才能验 ⇒ 见
 * {@code com.rag.follow.grpc.FollowGrpcServiceContractTest}。
 */
class FollowErrorContractTest {

    /**
     * B 侧 {@code FollowErrorCode} 的 16 个码值（按 B 侧声明顺序；{@code CODE_SERVER_BUSY} 在 5001 之前）。
     */
    private static final Map<String, Integer> GO_CODES = new LinkedHashMap<>();

    static {
        GO_CODES.put("SUCCESS", 200);
        GO_CODES.put("ERROR", 500);
        GO_CODES.put("CODE_SERVER_BUSY", 1015);
        GO_CODES.put("ERROR_SERVER_COMMON", 5001);
        GO_CODES.put("ERROR_NOT_FOUND", 5002);
        GO_CODES.put("ERROR_INVALID_PARAM", 5003);
        GO_CODES.put("ERROR_UNAUTHORIZED", 5004);
        GO_CODES.put("ERROR_FORBIDDEN", 5005);
        GO_CODES.put("ERROR_ALREADY_EXIST", 5006);
        GO_CODES.put("ERROR_DB_SELECT", 5007);
        GO_CODES.put("ERROR_DB_UPDATE", 5008);
        GO_CODES.put("ERROR_USER_NOT_FOUND", 5101);
        GO_CODES.put("ERROR_SELF_RELATION", 5201);
        GO_CODES.put("ERROR_RELATION_BLOCKED", 5202);
        GO_CODES.put("ERROR_FOLLOW_NOT_FOUND", 5203);
        GO_CODES.put("ERROR_BLOCK_NOT_FOUND", 5204);
    }

    /**
     * B 侧 {@code FollowErrorMessages} 的整表 16 条（键用码值，值逐字抄自 B 侧 {@code codeMsg}）。
     */
    private static final Map<Integer, String> GO_MESSAGES = new LinkedHashMap<>();

    static {
        GO_MESSAGES.put(200, "OK");
        GO_MESSAGES.put(500, "FAIL");
        GO_MESSAGES.put(1015, "service busy");
        GO_MESSAGES.put(5001, "internal server error");
        GO_MESSAGES.put(5002, "record not found");
        GO_MESSAGES.put(5003, "invalid parameter");
        GO_MESSAGES.put(5004, "unauthorized");
        GO_MESSAGES.put(5005, "forbidden");
        GO_MESSAGES.put(5006, "already exists");
        GO_MESSAGES.put(5007, "database query failed");
        GO_MESSAGES.put(5008, "database update failed");
        GO_MESSAGES.put(5101, "user not found");
        GO_MESSAGES.put(5201, "self relation is not allowed");
        GO_MESSAGES.put(5202, "blocked relation exists");
        GO_MESSAGES.put(5203, "follow relation not found");
        GO_MESSAGES.put(5204, "block relation not found");
    }

    // ==================================================================
    // 1. 16 个码值
    // ==================================================================

    @Test
    @DisplayName("🔴 16 个码值逐条等于 B 侧 FollowErrorCode（码值漂移 = L1 契约破坏）")
    void everyCodeValueMatchesGoOneByOne() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        actual.put("SUCCESS", ErrCodes.SUCCESS);
        actual.put("ERROR", ErrCodes.ERROR);
        actual.put("CODE_SERVER_BUSY", ErrCodes.CODE_SERVER_BUSY);
        actual.put("ERROR_SERVER_COMMON", ErrCodes.ERROR_SERVER_COMMON);
        actual.put("ERROR_NOT_FOUND", ErrCodes.ERROR_NOT_FOUND);
        actual.put("ERROR_INVALID_PARAM", ErrCodes.ERROR_INVALID_PARAM);
        actual.put("ERROR_UNAUTHORIZED", ErrCodes.ERROR_UNAUTHORIZED);
        actual.put("ERROR_FORBIDDEN", ErrCodes.ERROR_FORBIDDEN);
        actual.put("ERROR_ALREADY_EXIST", ErrCodes.ERROR_ALREADY_EXIST);
        actual.put("ERROR_DB_SELECT", ErrCodes.ERROR_DB_SELECT);
        actual.put("ERROR_DB_UPDATE", ErrCodes.ERROR_DB_UPDATE);
        actual.put("ERROR_USER_NOT_FOUND", ErrCodes.ERROR_USER_NOT_FOUND);
        actual.put("ERROR_SELF_RELATION", ErrCodes.ERROR_SELF_RELATION);
        actual.put("ERROR_RELATION_BLOCKED", ErrCodes.ERROR_RELATION_BLOCKED);
        actual.put("ERROR_FOLLOW_NOT_FOUND", ErrCodes.ERROR_FOLLOW_NOT_FOUND);
        actual.put("ERROR_BLOCK_NOT_FOUND", ErrCodes.ERROR_BLOCK_NOT_FOUND);

        assertThat(actual).isEqualTo(GO_CODES);
        assertThat(actual).hasSize(16);
    }

    // ==================================================================
    // 2. 16 条文案 + 回落
    // ==================================================================

    @Test
    @DisplayName("🔴 16 条文案逐条等于 B 侧 codeMsg（含大小写与中英文形态）")
    void everyMessageMatchesGoOneByOne() {
        assertThat(ErrMsg.MSG_BY_CODE).hasSize(16);
        GO_MESSAGES.forEach((code, expectedMsg) ->
                assertThat(ErrMsg.getMsg(code))
                        .as("文案漂移：码 %s 必须逐字节等于 B 侧 codeMsg 的值", code)
                        .isEqualTo(expectedMsg));
    }

    @Test
    @DisplayName("🔴 未知码回落 \"FAIL\"（不是 null、不是空串、不是码的字符串）")
    void unknownCodeFallsBackToFail() {
        // ⚠️ 走跨域撞车样本：这些码值在别的域存在，本域未登记
        for (int code : new int[]{1016, 1017, 2001, 2002, 3001, 5009, 5010, 5011, 5012, 5102, 5103, 5205}) {
            assertThat(ErrMsg.getMsg(code)).as("未知码必须回退 \"FAIL\"：%s", code).isEqualTo("FAIL");
        }
        assertThat(ErrMsg.getMsg(0)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(-1)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(Integer.MAX_VALUE)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(Integer.MIN_VALUE)).isEqualTo("FAIL");
    }

    @Test
    @DisplayName("🔴 文案表与码表严格一一对应（16 ↔ 16），且顺序与 B 侧书写顺序一致")
    void tableAndCodesAreInBijectionAndKeepGoOrder() {
        assertThat(ErrMsg.MSG_BY_CODE.keySet())
                .containsExactlyInAnyOrderElementsOf(GO_CODES.values());
        assertThat(new ArrayList<>(ErrMsg.MSG_BY_CODE.values()))
                .isEqualTo(new ArrayList<>(GO_MESSAGES.values()));
    }

    @Test
    @DisplayName("🔴 文案两两互不相同（这是反查能确定结果的前提）+ 整表英文（逐域隔离）")
    void messagesArePairwiseDistinctAndAllEnglish() {
        Set<String> unique = new HashSet<>();
        List<String> dup = new ArrayList<>();
        for (String msg : ErrMsg.MSG_BY_CODE.values()) {
            if (!unique.add(msg)) {
                dup.add(msg);
            }
            assertThat(msg.chars().allMatch(c -> c < 128))
                    .as("follow 域整表英文，不得按邻近 favorite 域的形态补中文：%s", msg)
                    .isTrue();
        }
        assertThat(dup).isEmpty();
    }

    @Test
    @DisplayName("🔴 文案表不可被外部篡改")
    void msgTableIsImmutable() {
        assertThatThrownBy(() -> ErrMsg.MSG_BY_CODE.put(9999, "hacked"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ==================================================================
    // 3. CodeError 的两条构造路径
    // ==================================================================

    @Test
    @DisplayName("🔴 CodeError：newCode 走查表、newCodeMsg 原样透传；getMessage() 文本与 B 侧逐字相同")
    void codeErrorConstructorPathsMatchGo() {
        CodeError viaTable = CodeError.newCode(ErrCodes.ERROR_DB_SELECT);
        assertThat(viaTable.getCode()).isEqualTo(5007);
        assertThat(viaTable.getMsg()).isEqualTo("database query failed");
        assertThat(viaTable.getMessage()).isEqualTo("ErrCode:5007, Errmsg:database query failed");

        CodeError viaMsg = CodeError.newCodeMsg(5202, "blocked relation exists");
        assertThat(viaMsg.getCode()).isEqualTo(5202);
        assertThat(viaMsg.getMsg()).isEqualTo("blocked relation exists");

        // ⚠️ 走查表路径遇未知码必须回退 "FAIL"，而 newCodeMsg 原样透传
        assertThat(CodeError.newCode(9999).getMsg()).isEqualTo("FAIL");
        assertThat(CodeError.newCodeMsg(9999, "custom text").getMsg()).isEqualTo("custom text");
    }

    @Test
    @DisplayName("🔴 CodeError 序列化后只有 code / msg 两个键（B 侧没有 message/cause/stackTrace）")
    void codeErrorSerializesToExactlyCodeAndMsg() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(CodeError.newCode(5007));

        Map<?, ?> parsed = mapper.readValue(json, Map.class);
        assertThat(parsed.keySet()).isEqualTo(Set.of("code", "msg"));
        assertThat(parsed.get("code")).isEqualTo(5007);
        assertThat(parsed.get("msg")).isEqualTo("database query failed");
        assertThat(json).isEqualTo("{\"code\":5007,\"msg\":\"database query failed\"}");
    }

    // ==================================================================
    // 4. gRPC 反查：文案反查**优先于** gRPC 码 switch
    // ==================================================================

    @Test
    @DisplayName("🔴 反查优先级：PERMISSION_DENIED + description=\"blocked relation exists\" ⇒ **5202**（不是 switch 兜底的 5005）")
    void descriptionReverseLookupWinsOverGrpcCodeSwitch() {
        // B 出处：B 侧 BizCodeFromError 先按 description 查 bizCodeByMsg，命中即返回；
        //         未命中才走 `switch st.Code()`（PERMISSION_DENIED ⇒ ErrorForbidden = 5005）。
        StatusRuntimeException err = Status.PERMISSION_DENIED
                .withDescription("blocked relation exists")
                .asRuntimeException();

        assertThat(GrpcErrorMapper.bizCodeFromError(err))
                .as("文案反查命中 ⇒ 5202；若退化成 5005 就说明反查被 switch 盖住了")
                .isEqualTo(ErrCodes.ERROR_RELATION_BLOCKED);
    }

    @Test
    @DisplayName("🔴 反查未命中时才按 gRPC 码 switch 兜底（PERMISSION_DENIED ⇒ 5005；UNAVAILABLE ⇒ 1015）")
    void grpcCodeSwitchIsTheFallbackWhenDescriptionMisses() {
        assertThat(GrpcErrorMapper.bizCodeFromError(
                Status.PERMISSION_DENIED.withDescription("something else").asRuntimeException()))
                .isEqualTo(ErrCodes.ERROR_FORBIDDEN);
        // ⚠️ 白名单恰好 6 个：InvalidArgument / Unauthenticated / PermissionDenied /
        //    AlreadyExists / NotFound / Internal；Unavailable 不在其中 ⇒ 1015
        assertThat(GrpcErrorMapper.bizCodeFromError(
                Status.UNAVAILABLE.withDescription("down").asRuntimeException()))
                .isEqualTo(ErrCodes.CODE_SERVER_BUSY);
        assertThat(GrpcErrorMapper.bizCodeFromError(
                Status.INVALID_ARGUMENT.withDescription("x").asRuntimeException()))
                .isEqualTo(ErrCodes.ERROR_INVALID_PARAM);
        assertThat(GrpcErrorMapper.bizCodeFromError(
                Status.UNAUTHENTICATED.withDescription("x").asRuntimeException()))
                .isEqualTo(ErrCodes.ERROR_UNAUTHORIZED);
        assertThat(GrpcErrorMapper.bizCodeFromError(
                Status.ALREADY_EXISTS.withDescription("x").asRuntimeException()))
                .isEqualTo(ErrCodes.ERROR_ALREADY_EXIST);
        assertThat(GrpcErrorMapper.bizCodeFromError(
                Status.NOT_FOUND.withDescription("x").asRuntimeException()))
                .isEqualTo(ErrCodes.ERROR_NOT_FOUND);
        assertThat(GrpcErrorMapper.bizCodeFromError(
                Status.INTERNAL.withDescription("x").asRuntimeException()))
                .isEqualTo(ErrCodes.ERROR_SERVER_COMMON);
    }

    @Test
    @DisplayName("🔴 非 gRPC status：CodeError 取自身 code；其它一律 1015（err == null ⇒ 200）")
    void nonStatusErrorsFallBackToServerBusy() {
        assertThat(GrpcErrorMapper.bizCodeFromError(null)).isEqualTo(ErrCodes.SUCCESS);
        assertThat(GrpcErrorMapper.bizCodeFromError(CodeError.newCode(5201)))
                .isEqualTo(ErrCodes.ERROR_SELF_RELATION);
        assertThat(GrpcErrorMapper.bizCodeFromError(new IllegalStateException("boom")))
                .isEqualTo(ErrCodes.CODE_SERVER_BUSY);
    }

    @Test
    @DisplayName("🔴 grpcError 造出的异常必须带业务文案 description（文案是业务码的载体，丢了就还原不回去）")
    void grpcErrorCarriesBusinessDescription() {
        io.grpc.StatusException e = GrpcErrorMapper.grpcError(Status.Code.INVALID_ARGUMENT,
                ErrCodes.ERROR_SELF_RELATION);
        assertThat(e.getStatus().getDescription()).isEqualTo("self relation is not allowed");
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
}
