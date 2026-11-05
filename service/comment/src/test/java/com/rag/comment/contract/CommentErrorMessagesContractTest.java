package com.rag.comment.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>comment 域错误码「文案 + 业务错误载体」的 1:1 守门测试</b>（纯单元，<b>不起 Spring 上下文</b>）。
 *
 * <h2>🔴 为什么这一层必须单独钉死</h2>
 * <p>文案是<b>用户直接看到的字符串</b>，且与码值<b>成对出现在 HTTP envelope</b>里
 * ⇒ 改一个字就是改用户可见行为；漏一条文案则是客户端拿到 {@code null}。
 * <p>⚠️ 「码错了」与「文案错了」的<b>排查路径不同</b>（码错 ⇒ 客户端分支走错；
 * 文案错 ⇒ 用户看到错的提示）⇒ 与码值测试分成两个类。
 *
 * <h2>① 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>C 侧 {@link ErrMsg} 的 {@code MSG_BY_CODE} 是 {@code private}</b>
 *       （B 侧是 {@code public}）⇒ 「文案表不可被外部篡改」这条断言
 *       改成<b>反射读取该表后对其做 {@code put}</b> ⇒ 守门点不变（仍是
 *       「外部改不动这张表」），只是入口从「直接引用」变成「反射拿引用」。</li>
 *   <li><b>C 侧把 B 侧的 {@code CommentErrorMessages.MSG_BY_CODE} 与
 *       {@code CommentCodeError} 拆成了 {@link ErrMsg} 与 {@link CodeError} 两个类</b>
 *       （L4 落点变化）⇒ B 侧同一文件里的两类断言在 C 侧分处两个类。</li>
 * </ul>
 *
 * <h2>② 语义还一样吗</h2>
 * <p><b>一样</b>。本类断言的全是「文案文本」「回退值」「载体形状」三类可观察行为，
 * 与「一个类还是两个类」的落点形态无关。
 *
 * <h2>③ B 出处</h2>
 * <pre>
 * B 侧源文件：service/comment/comment-common/src/test/java/com/rag/comment/comment/common/errmsg/CommentErrorMessagesContractTest.java
 *   everyMessageMatchesGoOneByOne              :70-76    → everyMessageMatchesGoOneByOne
 *   subjectAndCommentMessagesDiffer            :78-85    → subjectAndCommentMessagesDiffer
 *   unknownCodeFallsBackToFail                 :87-97    → unknownCodeFallsBackToFail
 *   msgTableIsImmutable                        :99-104   → msgTableIsImmutable
 *   codeErrorMessageFormatMatchesGo            :106-116  → codeErrorMessageFormatMatchesGo
 *   factoriesDifferOnLookup                    :118-128  → factoriesDifferOnLookup
 *   findInUnwrapsCauseChain                    :130-149  → findInUnwrapsCauseChain
 *   codeErrorSerialisesToExactlyTwoFieldsLikeGo:151-168  → codeErrorSerialisesToExactlyTwoFieldsLikeGo
 *   codeErrorIsUnchecked                       :170-178  → codeErrorIsUnchecked
 *   findInTerminatesOnSelfCause                :180-187  → findInTerminatesOnSelfCause
 * B 侧 oracle（文案真源）：service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentErrorMessages.java:44-95
 * B 侧 oracle（载体真源）：service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentCodeError.java:43-88
 * </pre>
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧的文案/载体与 B 侧源码<b>逐条一致</b>」。
 * 「B 侧此刻真的产出同样的文案」属于<b>双侧活体对拍</b>（本类不冒充该证据）。
 */
class CommentErrorMessagesContractTest {

    /**
     * B 侧 {@code CommentErrorMessages} 的整张 {@code codeMsg} 表 —— 27 条，逐字照抄。
     *
     * <p>⚠️ <b>这张表就是被测对象</b>：断言「C 侧 {@link ErrMsg#getMsg(int)} 与它逐条相同」
     * ⇒ 任何一条文案被改字、被漏掉、或被换码 ⇒ 断言红。
     */
    private static final Map<Integer, String> B_SIDE_MESSAGES = bSideMessages();

    private static Map<Integer, String> bSideMessages() {
        Map<Integer, String> map = new LinkedHashMap<>();
        // === 通用（B 侧 CommentErrorMessages.java:46-48）===
        map.put(200, "OK");
        map.put(500, "FAIL");
        map.put(1015, "服务繁忙");
        // === 系统与基础设施（B 侧 CommentErrorMessages.java:49-58）===
        map.put(5001, "系统内部错误");
        map.put(5002, "更新数据库失败");
        map.put(5003, "查询数据库失败");
        map.put(5004, "数据库插入失败");
        map.put(5005, "事务操作失败");
        map.put(5006, "雪花ID生成失败");
        map.put(5007, "JSON序列化失败");
        map.put(5008, "JSON反序列化失败");
        map.put(5009, "Kafka消息发送失败");
        map.put(5010, "延时消息发送失败");
        // === 用户 / 令牌（B 侧 CommentErrorMessages.java:59-69）===
        map.put(1001, "用户名已存在");
        map.put(1002, "用户名或密码错误");
        map.put(1003, "用户不存在");
        map.put(1004, "TOKEN不存在");
        map.put(1005, "TOKEN格式错误");
        map.put(1006, "TOKEN已过期");
        map.put(1007, "TOKEN刷新失败");
        map.put(1008, "权限不足");
        map.put(1009, "未登录");
        map.put(1010, "已登录");
        map.put(1011, "用户已被封禁");
        // === comment 专属（B 侧 CommentErrorMessages.java:70-71）===
        map.put(1012, "评论区不存在");
        map.put(1013, "评论不存在");
        // === 输入校验（B 侧 CommentErrorMessages.java:72）===
        map.put(2001, "输入有误");
        return map;
    }

    /**
     * 反射读取 C 侧 {@link ErrMsg} 的私有文案表 {@code MSG_BY_CODE}。
     *
     * <p>🔴 <b>读不到就失败</b>（不静默跳过）⇒ 否则「文案表不可篡改」这条会退化成「永远绿」的假门。
     */
    @SuppressWarnings("unchecked")
    private static Map<Integer, String> msgTable() {
        try {
            Field field = ErrMsg.class.getDeclaredField("MSG_BY_CODE");
            field.setAccessible(true);
            return (Map<Integer, String>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 ErrMsg.MSG_BY_CODE（文案表无法核对）", e);
        }
    }

    @Test
    @DisplayName("🔴 27 条文案与 B 侧逐条 1:1")
    void everyMessageMatchesBSideOneByOne() {
        assertThat(B_SIDE_MESSAGES)
                .as("B 侧 comment 域文案表应当正好 27 条")
                .hasSize(27);
        assertThat(msgTable())
                .as("C 侧文案表必须正好 27 条（少一条 ⇒ 该码会回落成 FAIL）")
                .hasSize(27);

        B_SIDE_MESSAGES.forEach((code, msg) ->
                assertThat(ErrMsg.getMsg(code))
                        .as("code=" + code + " 的文案漂移了")
                        .isEqualTo(msg));
    }

    @Test
    @DisplayName("🔴 comment 域两个专属文案锁定：「评论区不存在」≠「评论不存在」")
    void subjectAndCommentMessagesDiffer() {
        assertThat(ErrMsg.getMsg(1012)).isEqualTo("评论区不存在");
        assertThat(ErrMsg.getMsg(1013)).isEqualTo("评论不存在");
        // 边界：这两个文案只差 3 个字，最容易被"合并顺手改错"，单独钉一次
        assertThat(ErrMsg.getMsg(1013)).isNotEqualTo("评论区不存在");
    }

    @Test
    @DisplayName("🔴 未知码回退到 \"FAIL\"，不是 null、不是空串 —— 对齐 B 侧 GetErrMsg")
    void unknownCodeFallsBackToFail() {
        assertThat(ErrMsg.getMsg(999_999)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(-1)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(0)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(3)).isEqualTo("FAIL");
        // 边界：500 本身是已知码，返回自己的 msg
        assertThat(ErrMsg.getMsg(500)).isEqualTo("FAIL");
        assertThat(ErrMsg.getMsg(200)).isEqualTo("OK");
    }

    @Test
    @DisplayName("🔴 文案表不可被外部篡改")
    void msgTableIsImmutable() {
        Map<Integer, String> table = msgTable();
        assertThatThrownBy(() -> table.put(12345, "篡改"))
                .as("文案表必须被 unmodifiableMap 包装 —— 外部改一个字就是改用户可见行为")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("🔴 getMessage() 与 B 侧 Error() 逐字节相同")
    void codeErrorMessageFormatMatchesGo() {
        // B 侧: fmt.Sprintf("ErrCode:%d, Errmsg:%s", e.Code, e.Msg)
        assertThat(new CodeError(5003, "DB查询失败").getMessage())
                .isEqualTo("ErrCode:5003, Errmsg:DB查询失败");
        assertThat(new CodeError(1012, "评论区不存在").getMessage())
                .isEqualTo("ErrCode:1012, Errmsg:评论区不存在");
        assertThat(new CodeError(1013, "评论不存在").getMessage())
                .isEqualTo("ErrCode:1013, Errmsg:评论不存在");
    }

    @Test
    @DisplayName("🔴 newCode 查表、newCodeMsg 不查表 —— 这是 B 侧的区分，必须保留")
    void factoriesDifferOnLookup() {
        // B 侧 NewErrCode(5003) → msg = GetErrMsg(5003) = "查询数据库失败"
        assertThat(CodeError.newCode(5003).getMsg()).isEqualTo("查询数据库失败");
        // B 侧 NewErrCodeMsg(5003, "DB查询失败") → msg 原样，不能被表覆盖
        assertThat(CodeError.newCodeMsg(5003, "DB查询失败").getMsg()).isEqualTo("DB查询失败");
        // comment-rpc 的真实用法：全部走 NewGrpcErr(code, 自定义 msg)，
        // 落到 api 层就是 NewErrCodeMsg(int(st.Code()), st.Message())（见 B 侧各 logic:52-55）
        assertThat(CodeError.newCodeMsg(1008, "非up主无法评论").getMsg()).isEqualTo("非up主无法评论");
        // 🔴 边界：自定义文案与码表里的文案**不相等** ⇒ 查表会把客户端看到的错误信息换掉
        assertThat(CodeError.newCodeMsg(5003, "DB查询失败").getMsg())
                .isNotEqualTo(ErrMsg.getMsg(5003));
    }

    @Test
    @DisplayName("🔴 findIn 沿 cause 链回溯 —— 对齐 B 侧 errors.As，包过一层的业务错误不能被降级成 5001")
    void findInUnwrapsCauseChain() {
        CodeError original = CodeError.newCode(1012);

        // 未包装：直接命中
        assertThat(CodeError.findIn(original)).isSameAs(original);

        // B 侧: fmt.Errorf("...: %w", err) 包一层后 errors.As 仍能还原 → Java 必须沿 getCause() 回溯
        RuntimeException wrapped = new RuntimeException("创建评论失败", original);
        assertThat(CodeError.findIn(wrapped)).isSameAs(original);

        // 包两层
        RuntimeException wrappedTwice = new RuntimeException("logic 失败", wrapped);
        assertThat(CodeError.findIn(wrappedTwice)).isSameAs(original);

        // 不是业务错误 → 返回 null，调用方据此回退 5001（B 侧 code 保持 ErrorServerCommon）
        assertThat(CodeError.findIn(new IllegalStateException("PG 连接断开"))).isNull();
        assertThat(CodeError.findIn(null)).isNull();
    }

    @Test
    @DisplayName("🔴 序列化后只有 code / msg 两个字段，与 B 侧 encoding/json 一致")
    void codeErrorSerialisesToExactlyTwoFieldsLikeGo() throws Exception {
        // B 侧: encoding/json 只序列化导出字段，输出 {"code":5003,"msg":"DB查询失败"}。
        // 若没有 @JsonAutoDetect(NONE)，Jackson 还会吐出 Throwable 继承来的
        // message / localizedMessage / cause / stackTrace —— B 侧一个都没有。
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(
                mapper.writeValueAsString(new CodeError(5003, "DB查询失败")));

        assertThat(node.size())
                .as("多出/少了字段：" + node)
                .isEqualTo(2);
        assertThat(node.get("code").asInt()).isEqualTo(5003);
        assertThat(node.get("msg").asText()).isEqualTo("DB查询失败");

        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactly("code", "msg");
    }

    @Test
    @DisplayName("🔴 CodeError 是 unchecked —— rpc logic 才能像 B 侧返回错误那样直接交出去")
    void codeErrorIsUnchecked() {
        assertThat(RuntimeException.class.isAssignableFrom(CodeError.class)).isTrue();
        CodeError err = CodeError.newCode(ErrCodes.ERROR_SERVER_COMMON);
        assertThat(err.getCode()).isEqualTo(5001);
        assertThat(err.getMessage()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("🔴 CodeError 包装自身不会造成 findIn 死循环")
    void findInTerminatesOnSelfCause() {
        // Throwable.initCause 禁止自引用，这里确认构造路径本身不会埋雷
        CodeError err = CodeError.newCode(2001);
        assertThat(CodeError.findIn(err)).isSameAs(err);
        assertThat(err.getCause()).isNull();
    }
}
