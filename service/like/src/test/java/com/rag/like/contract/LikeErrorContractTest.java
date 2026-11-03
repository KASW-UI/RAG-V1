package com.rag.like.contract;

import com.rag.like.contract.CodeError;
import com.rag.like.contract.ErrCodes;
import com.rag.like.contract.ErrMsg;
import com.rag.like.contract.GrpcErrFactory;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>like 域错误契约的逐条冻结断言</b>（纯单元，<b>不起 Spring 上下文</b>）。
 *
 * <h2>🔴 为什么这一层必须单独钉死（而不是「领域层测试顺便覆盖」）</h2>
 * <p>错误码与文案是 <b>L1 契约</b>：客户端按 {@code code} 分支、按 {@code msg} 展示。
 * ⇒ 一旦某个码被换掉、或某条文案被改字 ⇒ 客户端的分支走错、用户看到别的提示。
 * <p>⚠️ <b>「领域层测试顺便覆盖」证不了这件事</b>：
 * 领域层测试通常只断言「某个场景返回了某个码」，**不会**把 <b>整张码表</b>摊开
 * ⇒ 漏掉一条码（新增/遗漏/重复）不会被发现。
 * ⇒ ⇒ 本类把 <b>29 条码 + 29 条文案</b>逐条摊开比对。
 *
 * <h2>抄写基准（B 侧 · 唯一 oracle）</h2>
 * <ul>
 *   <li>码值：B 侧 {@code like-common/.../errmsg/LikeErrorCode.java}（29 个枚举）</li>
 *   <li>文案：B 侧 {@code like-common/.../errmsg/LikeErrorMessages.java}（29 条）</li>
 *   <li>gRPC 侧：B 侧 {@code like-common/.../errmsg/LikeGrpcError.java}</li>
 * </ul>
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧的码值/文案与 B 侧源码<b>逐条一致</b>」。
 * 「B 侧此刻真的产出同样的码」属于<b>双侧活体对拍</b>（本类不冒充该证据）。
 */
class LikeErrorContractTest {

    /**
     * B 侧 {@code LikeErrorMessages} 的整张表 —— 29 条，逐字照抄。
     *
     * <p>⚠️ <b>这张表就是被测对象</b>：断言「C 侧 {@link ErrMsg#allMsgs()} 与它逐条相同」
     * ⇒ 任何一条码被漏掉、被换值、或文案被改字 ⇒ 这条断言红。
     */
    private static final Map<Integer, String> B_SIDE_TABLE = Map.ofEntries(
            // === 通用 ===
            Map.entry(200, "OK"),
            Map.entry(500, "FAIL"),
            Map.entry(1015, "服务繁忙"),
            // === 系统与基础设施 ===
            Map.entry(5001, "系统内部错误"),
            Map.entry(5002, "更新数据库失败"),
            Map.entry(5003, "查询数据库失败"),
            Map.entry(5004, "数据库插入失败"),
            Map.entry(5005, "事务操作失败"),
            Map.entry(5006, "雪花ID生成失败"),
            Map.entry(5007, "JSON序列化失败"),
            Map.entry(5008, "JSON反序列化失败"),
            Map.entry(5009, "Kafka消息发送失败"),
            Map.entry(5010, "延时消息发送失败"),
            Map.entry(5011, "Redis查询失败"),
            Map.entry(5012, "Redis更新失败"),
            // === 用户 / 鉴权 ===
            Map.entry(1001, "用户名已存在"),
            Map.entry(1002, "用户名或密码错误"),
            Map.entry(1003, "用户不存在"),
            Map.entry(1004, "TOKEN不存在"),
            Map.entry(1005, "TOKEN格式错误"),
            Map.entry(1006, "TOKEN已过期"),
            Map.entry(1007, "TOKEN刷新失败"),
            Map.entry(1008, "权限不足"),
            Map.entry(1009, "未登录"),
            Map.entry(1010, "已登录"),
            Map.entry(1011, "用户已被封禁"),
            // === 入参 / 类型 ===
            Map.entry(2001, "输入有误"),
            Map.entry(2002, "类型转换错误"),
            // === Outbox ===
            Map.entry(3001, "构建 Outbox 消息载荷失败"));

    @Test
    @DisplayName("🔴 29 个码值与 B 侧逐条一致（漏一条 / 换一条都会红）")
    void allCodesMatchBSide() {
        assertThat(B_SIDE_TABLE)
                .as("B 侧 like 域错误码表应当正好 29 条（改了条数 ⇒ 本表过时 ⇒ 必须同步更新断言）")
                .hasSize(29);

        Map<Integer, String> actual = ErrMsg.allMsgs();

        assertThat(actual)
                .as("C 侧码表必须与 B 侧逐条相同（码 → 文案）")
                .containsExactlyInAnyOrderEntriesOf(B_SIDE_TABLE);
    }

    @Test
    @DisplayName("🔴 每一个码常量都等于它在码表里的码值（常量名与值不得漂移）")
    void constantsMatchTable() {
        assertThat(ErrCodes.SUCCESS).isEqualTo(200);
        assertThat(ErrCodes.ERROR).isEqualTo(500);
        assertThat(ErrCodes.CODE_SERVER_BUSY).isEqualTo(1015);
        assertThat(ErrCodes.ERROR_SERVER_COMMON).isEqualTo(5001);
        assertThat(ErrCodes.ERROR_DB_UPDATE).isEqualTo(5002);
        assertThat(ErrCodes.ERROR_DB_SELECT).isEqualTo(5003);
        assertThat(ErrCodes.ERROR_DB_INSERT).isEqualTo(5004);
        assertThat(ErrCodes.ERROR_DB_TRANSACTION).isEqualTo(5005);
        assertThat(ErrCodes.ERROR_SNOWFLAKE_ID).isEqualTo(5006);
        assertThat(ErrCodes.ERROR_JSON_MARSHAL).isEqualTo(5007);
        assertThat(ErrCodes.ERROR_JSON_UNMARSHAL).isEqualTo(5008);
        assertThat(ErrCodes.ERROR_KAFKA_PUSH).isEqualTo(5009);
        assertThat(ErrCodes.ERROR_DELAY_MSG).isEqualTo(5010);
        assertThat(ErrCodes.ERROR_REDIS_SELECT).isEqualTo(5011);
        assertThat(ErrCodes.ERROR_REDIS_UPDATE).isEqualTo(5012);
        assertThat(ErrCodes.ERROR_USER_EXIST).isEqualTo(1001);
        assertThat(ErrCodes.ERROR_LOGIN_WRONG).isEqualTo(1002);
        assertThat(ErrCodes.ERROR_USER_NOT_EXIST).isEqualTo(1003);
        assertThat(ErrCodes.ERROR_TOKEN_NOT_EXIST).isEqualTo(1004);
        assertThat(ErrCodes.ERROR_TOKEN_TYPE_WRONG).isEqualTo(1005);
        assertThat(ErrCodes.ERROR_TOKEN_RUNTIME).isEqualTo(1006);
        assertThat(ErrCodes.ERROR_TOKEN_REFRESH).isEqualTo(1007);
        assertThat(ErrCodes.ERROR_USER_NO_RIGHT).isEqualTo(1008);
        assertThat(ErrCodes.ERROR_USER_NO_LOGIN).isEqualTo(1009);
        assertThat(ErrCodes.ERROR_USER_LOGINED).isEqualTo(1010);
        assertThat(ErrCodes.ERROR_USER_BANNED).isEqualTo(1011);
        assertThat(ErrCodes.ERROR_INPUT_WRONG).isEqualTo(2001);
        assertThat(ErrCodes.ERROR_TYPE_TRANSFER).isEqualTo(2002);
        assertThat(ErrCodes.ERROR_BUILD_OUTBOX).isEqualTo(3001);
    }

    @Test
    @DisplayName("🔴 未知码回落到 \"FAIL\"（不是 null、不是空串、不是码值本身）")
    void unknownCodeFallsBackToFail() {
        // B 出处：Go `errmsg.go:101-107`
        //   func GetErrMsg(code int) string { msg, ok := codeMsg[code]; if !ok { return codeMsg[Error] } … }
        assertThat(ErrMsg.getMsg(999999)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(-1)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(0)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(1012))
                .as("1012 不在码表里（码表是 1001~1011 + 1015）⇒ 必须回落 FAIL")
                .isEqualTo("FAIL");
    }

    @Test
    @DisplayName("CodeError.newCode 走码表；newCodeMsg 走自定义文案（后者不查表）")
    void codeErrorCarriesCodeAndMsg() {
        CodeError fromTable = CodeError.newCode(ErrCodes.ERROR_DB_SELECT);
        assertThat(fromTable.getCode()).isEqualTo(5003);
        assertThat(fromTable.getMsg()).isEqualTo("查询数据库失败");

        // ⚠️ 关键：B 侧 like-rpc 发的是 `NewGrpcErr(5003, "DB查询失败")` ——
        //    **自定义文案与码表里的 5003 文案不相等** ⇒ 查表会把客户端看到的错误信息换掉。
        CodeError custom = CodeError.newCodeMsg(5003, "DB查询失败");
        assertThat(custom.getCode()).isEqualTo(5003);
        assertThat(custom.getMsg())
                .as("newCodeMsg 必须原样透传自定义文案，不得回落码表")
                .isEqualTo("DB查询失败");
        assertThat(custom.getMsg()).isNotEqualTo(ErrMsg.getMsg(5003));
    }

    @Test
    @DisplayName("🔴 gRPC 边界：业务码走 trailer「rag-like-biz-code」，文案走 description")
    void grpcErrorCarriesBizCodeInTrailer() {
        StatusException err = GrpcErrFactory.grpcError(5003, "DB查询失败");

        Metadata trailers = err.getTrailers();
        assertThat(trailers.get(GrpcErrFactory.BIZ_CODE_TRAILER))
                .as("业务码必须落在 trailer 里（key = rag-like-biz-code）")
                .isEqualTo("5003");
        assertThat(err.getStatus().getDescription()).isEqualTo("DB查询失败");
        assertThat(GrpcErrFactory.bizCodeFromError(err)).isEqualTo(5003);
    }

    @Test
    @DisplayName("🔴 gRPC 边界：2001 ⇒ INVALID_ARGUMENT；其余业务码 ⇒ UNKNOWN")
    void grpcStatusMappingIsFixed() {
        // B 出处：B 侧 `LikeGrpcError` 的 status 映射表（2001 → INVALID_ARGUMENT，其余 → UNKNOWN）。
        assertThat(GrpcErrFactory.grpcError(2001, "输入有误").getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(GrpcErrFactory.grpcError(5003, "DB查询失败").getStatus().getCode())
                .isEqualTo(Status.Code.UNKNOWN);
        assertThat(GrpcErrFactory.grpcError(1006, "TOKEN已过期").getStatus().getCode())
                .isEqualTo(Status.Code.UNKNOWN);
    }

    @Test
    @DisplayName("🔴 非 gRPC 异常 ⇒ 业务码回落 1015（bizCodeFromError 的兜底）")
    void nonGrpcErrorFallsBackToServerBusy() {
        // ⚠️ 这一条是 **bizCodeFromError** 的兜底（1015）；
        //    而 **LikeApiSupport#rpcError** 对非 status 异常给的是 **5001** —— 两者不同，
        //    不可混用。详见 LikeApiSupport#rpcError 的类注释（Go 的 ok 标志决定一切）。
        assertThat(GrpcErrFactory.bizCodeFromError(new RuntimeException("boom")))
                .isEqualTo(ErrCodes.CODE_SERVER_BUSY);
    }

    @Test
    @DisplayName("🔴 trailer 里的业务码是「字符串形态的十进制整数」，可原样往返")
    void bizCodeTrailerRoundTrip() {
        for (int code : new int[]{2001, 5003, 5011, 1006, 1015}) {
            StatusException err = GrpcErrFactory.grpcError(code, "x");
            assertThat(GrpcErrFactory.bizCodeFromError(err))
                    .as("业务码 %d 必须能原样往返", code)
                    .isEqualTo(code);
        }
    }

    @Test
    @DisplayName("CodeError 是 RuntimeException（可被异常处理器直接捕获）")
    void codeErrorIsRuntimeException() {
        assertThatThrownBy(() -> {
            throw CodeError.newCode(ErrCodes.ERROR_INPUT_WRONG);
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("🔴 客户端收到的 StatusRuntimeException（带 trailer）必须能原样反查业务码")
    void statusRuntimeExceptionAlsoCarriesBizCode() {
        // B 侧 like-rpc 把业务码放在 trailer 里 ⇒ 客户端侧收到的是 StatusRuntimeException
        // ⇒ 反查必须同样命中 trailer（blocking stub 实际抛的就是这个）。
        StatusException source = GrpcErrFactory.grpcError(5011, "Redis查询失败");
        StatusRuntimeException runtime = source.getStatus().asRuntimeException(source.getTrailers());
        assertThat(GrpcErrFactory.bizCodeFromError(runtime))
                .as("带 trailer 的 StatusRuntimeException 必须还原出 5011")
                .isEqualTo(5011);
    }

    @Test
    @DisplayName("🔴 不带 trailer 的 StatusRuntimeException ⇒ 标准码兜底 1015（不是猜出来的业务码）")
    void statusRuntimeExceptionWithoutTrailerFallsBackToServerBusy() {
        // ⚠️ 灰度期边界：Go like-rpc 发的是 `grpc-status: 5003`（**越界**）且**无本域 trailer**
        //    ⇒ grpc-java 降级成 UNKNOWN + "Unknown code 5003" ⇒ 本类能从文案里**捞回码**；
        //    但若连那句文案都没有 ⇒ 只能落标准码兜底 **1015**。
        // ⇒ ⇒ 断言「不猜业务码」：捞不回来就是 1015。
        StatusRuntimeException bare = Status.UNKNOWN.withDescription("some failure").asRuntimeException();
        assertThat(GrpcErrFactory.bizCodeFromError(bare))
                .as("无 trailer、无法从文案捞码 ⇒ 只能落标准码兜底 1015")
                .isEqualTo(ErrCodes.CODE_SERVER_BUSY);
    }

    @Test
    @DisplayName("🔴 灰度期降级形态：\"Unknown code 5003\" 的 description 能捞回 5003")
    void degradedGoDescriptionCanRecoverBizCode() {
        // B 出处：Go like-rpc 用 `status.Error(codes.Code(5003), msg)` ⇒ grpc-status 越界
        //        ⇒ grpc-java 降级为 UNKNOWN + "Unknown code 5003"（原始 msg 被覆盖）。
        StatusRuntimeException degraded =
                Status.UNKNOWN.withDescription("Unknown code 5003").asRuntimeException();
        assertThat(GrpcErrFactory.bizCodeFromError(degraded))
                .as("灰度期降级形态必须能捞回业务码（否则客户端看到的是 1015 而不是 5003）")
                .isEqualTo(5003);
    }
}
