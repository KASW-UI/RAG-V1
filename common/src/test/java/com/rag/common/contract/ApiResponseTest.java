package com.rag.common.contract;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3 真触发测试 —— {@link ApiResponse} 这个<b>全局 L1 信封</b>的序列化形状。
 *
 * <p><b>L1 契约（Go 为准 · D13）</b>：响应体 = {@code {code, msg, data}}，
 * 键序固定为 {@code code → msg → data}，且 <b>{@code data:null} 恒出现</b>
 * （Go 侧的 {@code httpx.OkJsonCtx} / {@code WriteJson} 不做 omitempty）。
 *
 * <p><b>⚠️ 为什么这必须用「真触发」钉住</b>（AGENTS.md §3.3）：
 * 「<b>加不加 {@code @JsonInclude(NON_NULL)}</b>」与「<b>字段声明顺序</b>」都<b>不会编译报错</b>、
 * 也<b>不会在启动日志里报错</b> —— 错了只有真客户端能看出来。
 * ⇒ ⇒ 这里用<b>真实</b> {@code ObjectMapper} 序列化，断言<b>逐字节</b>的 JSON 形状。
 */
class ApiResponseTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("L1：键序 = code → msg → data")
    void keyOrderIsCodeMsgData() throws Exception {
        ApiResponse resp = new ApiResponse(200, "OK", null);
        String json = MAPPER.writeValueAsString(resp);
        assertThat(json).isEqualTo("{\"code\":200,\"msg\":\"OK\",\"data\":null}");
    }

    @Test
    @DisplayName("L1：data 为 null 时字段**恒出现**（禁 @JsonInclude(NON_NULL)）")
    void nullDataStillAppears() throws Exception {
        String json = MAPPER.writeValueAsString(new ApiResponse(5001, "解析失败", null));
        assertThat(json).contains("\"data\":null");
        assertThat(json).isEqualTo("{\"code\":5001,\"msg\":\"解析失败\",\"data\":null}");
    }

    @Test
    @DisplayName("L1：data 有值时同样在第三个位置")
    void nonNullDataIsThird() throws Exception {
        String json = MAPPER.writeValueAsString(new ApiResponse(0, "OK", "payload"));
        assertThat(json).isEqualTo("{\"code\":0,\"msg\":\"OK\",\"data\":\"payload\"}");
    }

    @Test
    @DisplayName("反序列化：三个字段都能读回来（各域 advice 的回写路径）")
    void deserializesBack() throws Exception {
        ApiResponse resp = MAPPER.readValue("{\"code\":1,\"msg\":\"m\",\"data\":null}", ApiResponse.class);
        assertThat(resp.getCode()).isEqualTo(1);
        assertThat(resp.getMsg()).isEqualTo("m");
        assertThat(resp.getData()).isNull();
    }
}
