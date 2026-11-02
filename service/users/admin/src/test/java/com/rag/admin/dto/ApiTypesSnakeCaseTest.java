package com.rag.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.admin.dto.admin.AdminInfo;
import com.rag.admin.dto.admin.CreateAdminReq;
import com.rag.admin.dto.admin.CreateAdminResp;
import com.rag.admin.dto.admin.UpdateSelfReq;
import com.rag.admin.dto.auth.LoginReq;
import com.rag.admin.dto.invite.CreateInviteCodeResp;
import com.rag.admin.dto.user.BanUserResp;
import com.rag.admin.dto.user.GetUserListReq;
import com.rag.admin.dto.user.GetUserListResp;
import com.rag.admin.dto.user.GetUserReq;
import com.rag.admin.dto.user.GetUserResp;
import com.rag.admin.dto.user.ResetUserPasswordReq;
import com.rag.admin.dto.user.UpdateUserReq;
import com.rag.admin.dto.user.UpdateUserResp;
import com.rag.admin.dto.user.UserInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO 序列化 wire 契约测试 —— 验证 snake_case 字段名 + uid 序列化为字符串。
 *
 * <p>对应 Go 端 internal/types/types.go 的 json tag 1:1：
 * <pre>
 *   uid → "uid"（Long.toString）
 *   invite_code → "invite_code"
 *   extra_info → "extra_info"
 *   page_size → "page_size"
 *   expires_at → "expires_at"
 *   invite_code / page_size / extra_info / expires_at 等多词字段全 snake_case
 * </pre>
 *
 * <p><b>本类由 B 侧同名类移植，期望值沿用 B 侧（D13 · AGENTS.md §1.0c「测试资产单独一档」）。</b>
 *
 * <p><b>B 出处</b>：{@code /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-api/src/test/java/com/rag/users/admin/api/dto/ApiTypesSnakeCaseTest.java}
 * （B 侧源文件 · 279 行 · 18 个 {@code @Test}）。
 *
 * <p><b>⚠️ 移植时只改了「怎么造对象」，没改「断言什么」</b>：
 * B 侧的 DTO 是<b>可变 class + setter</b>（{@code new LoginReq(); req.setUsername("alice");}），
 * 本仓的 DTO 是 <b>immutable record</b>（D5 · AGENTS.md §4.1「DTO 层要 immutable record」）
 * ⇒ ⇒ 造对象的写法从「new + setXxx」改成「new 构造器」，
 * <b>被断言的 JSON 文本逐字未动</b>（B 侧的期望值全部原样保留）。
 *
 * <p><b>⚠️ uid 的类型差别（不是断言差别）</b>：B 侧 DTO 的字段是 {@code Long uid}，
 * 但 getter 返回 {@code String} ⇒ JSON 里是<b>字符串</b>；本仓 record 的 {@code uid} 组件
 * <b>直接就是 {@code String}</b> ⇒ <b>序列化结果逐字节一致</b>
 * （§D-C：uid 字符串化是 L1 事实，必须复刻）。
 *
 * <p><b>⚠️ 本类<b>不</b>加 {@code @DisplayName}</b>：B 侧同名类<b>一个都没有</b>（只有方法名承载语义）
 * ⇒ 移植时逐字保留（方法名也逐字保留），不新造措辞。
 */
class ApiTypesSnakeCaseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loginReq_containsFields() throws Exception {
        LoginReq req = new LoginReq("alice", "pwd");

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"username\":\"alice\""), json);
        assertTrue(json.contains("\"password\":\"pwd\""), json);
    }

    @Test
    void createAdminReq_containsSnakeCase() throws Exception {
        Map<String, String> extra = new HashMap<>();
        extra.put("k", "v");

        CreateAdminReq req = new CreateAdminReq("alice", "pwd", "a@b.c", "ADM-DEADBEEF", extra);

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"username\":\"alice\""), json);
        assertTrue(json.contains("\"password\":\"pwd\""), json);
        assertTrue(json.contains("\"email\":\"a@b.c\""), json);
        assertTrue(json.contains("\"invite_code\":\"ADM-DEADBEEF\""), json);
        assertTrue(json.contains("\"extra_info\":{\"k\":\"v\"}"), json);
    }

    @Test
    void createAdminResp_uidIsString() throws Exception {
        CreateAdminResp resp = new CreateAdminResp("123456789012345");   // Snowflake UID

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("\"uid\":\"123456789012345\""), json);
    }

    @Test
    void createInviteCodeResp_containsSnakeCase() throws Exception {
        CreateInviteCodeResp resp = new CreateInviteCodeResp("ADM-ABCDEF0123456789", 1893456000L);

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("\"invite_code\":\"ADM-ABCDEF0123456789\""), json);
        assertTrue(json.contains("\"expires_at\":1893456000"), json);
    }

    /**
     * DTO <b>自身</b>的字段省略行为。
     *
     * <p>注意与 A-02 的区别：本测试只锁定 DTO 的 {@code NON_NULL} 行为——
     * <b>不 set 就省略</b>。而 B 侧的 {@code AdminApiSupport.fromProto(AdminInfo)} 现在会
     * <b>总是</b> set email（空值 set成 {@code ""}），因为 Go 的 {@code types.AdminInfo.Email}
     * 没有 omitempty、空值也要输出 {@code "email":""}。
     * 两层都要测，端到端的实际输出见 {@code AdminApiSupportConversionTest}。
     */
    @Test
    void adminInfo_uidIsStringAndOmitsAbsentFields() throws Exception {
        AdminInfo ai = new AdminInfo("42", "bob", null, null);
        // email / extra_info 不设 → DTO 层面应不出现

        String json = mapper.writeValueAsString(ai);
        assertTrue(json.contains("\"uid\":\"42\""), json);
        assertTrue(json.contains("\"username\":\"bob\""), json);
        assertFalse(json.contains("email"), json);
        assertFalse(json.contains("extra_info"), json);
    }

    /** A-02：email 被显式 set 成空串时，字段<b>出现</b>且值为 ""（对齐 Go 的无 omitempty）。 */
    @Test
    void adminInfo_emitsEmptyEmailWhenSetToEmptyString() throws Exception {
        AdminInfo ai = new AdminInfo("42", "bob", "", null);

        String json = mapper.writeValueAsString(ai);
        assertTrue(json.contains("\"email\":\"\""), json);
    }

    @Test
    void adminInfo_extraInfoSnakeCase() throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("k", "v");

        AdminInfo ai = new AdminInfo("42", null, null, extra);

        String json = mapper.writeValueAsString(ai);
        assertTrue(json.contains("\"extra_info\":{\"k\":\"v\"}"), json);
    }

    /**
     * UserInfo 的 email <b>带 omitempty</b>，与 AdminInfo 相反 —— DTO 层面不 set 就省略。
     *
     * <p>Go 出处：internal/types/types.go 的 {@code UserInfo.Email string `json:"email,omitempty"`}
     * （而 {@code AdminInfo.Email} 是 {@code `json:"email"`}，无 omitempty）。
     * 所以 {@code AdminApiSupport} 里两个 fromProto 对 email 的处理必须不同，不要顺手统一。
     */
    @Test
    void userInfo_uidIsStringAndIncludesStatus() throws Exception {
        UserInfo ui = new UserInfo("42", 0L, "bob", null, null);
        // email / extra_info 不设 → 应不出现

        String json = mapper.writeValueAsString(ui);
        assertTrue(json.contains("\"uid\":\"42\""), json);
        assertTrue(json.contains("\"status\":0"), json);
        assertTrue(json.contains("\"username\":\"bob\""), json);
        assertFalse(json.contains("email"), json);
        assertFalse(json.contains("extra_info"), json);
    }

    @Test
    void updateUserReq_omitsUnsetOptionalFields() throws Exception {
        UpdateUserReq req = new UpdateUserReq("100", "alice", null, null, null);
        // password / email / extra_info 不设 → 应不出现

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"uid\":\"100\""), json);
        assertTrue(json.contains("\"username\":\"alice\""), json);
        assertFalse(json.contains("password"), json);
        assertFalse(json.contains("email"), json);
        assertFalse(json.contains("extra_info"), json);
    }

    @Test
    void updateSelfReq_omitsUnsetOptionalFields() throws Exception {
        UpdateSelfReq req = new UpdateSelfReq("alice", null, null, null);

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"username\":\"alice\""), json);
        assertFalse(json.contains("password"), json);
        assertFalse(json.contains("email"), json);
        assertFalse(json.contains("extra_info"), json);
    }

    @Test
    void getUserReq_uidSnakeCase() throws Exception {
        GetUserReq req = new GetUserReq("12345");

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"uid\":\"12345\""), json);
    }

    @Test
    void resetUserPasswordReq_uidSnakeCase() throws Exception {
        ResetUserPasswordReq req = new ResetUserPasswordReq("67890");

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"uid\":\"67890\""), json);
    }

    @Test
    void getUserListReq_pageSizeSnakeCase() throws Exception {
        GetUserListReq req = new GetUserListReq(2L, 50L, "alice");

        String json = mapper.writeValueAsString(req);
        assertTrue(json.contains("\"page\":2"), json);
        assertTrue(json.contains("\"page_size\":50"), json);
        assertTrue(json.contains("\"keyword\":\"alice\""), json);
    }

    /**
     * A-01（已修）：空结果时 list 序列化成<b>字面量 null</b>，不是 []，也不是「字段消失」。
     *
     * <p>Go 出处：internal/types/types.go 的 {@code List []UserInfo `json:"list"`} —— <b>无 omitempty</b>；
     * 空结果时 api 侧 {@code getuserlistlogic.go:51} 的 {@code var list []types.UserInfo} 保持 nil，
     * 序列化结果就是 {@code "list": null}。
     *
     * <p>本断言在 DRIFT-001 批次前锁的是旧行为（NON_NULL ⇒ 字段消失），已按Go 行为改写。
     */
    @Test
    void getUserListResp_emitsNullListWhenEmpty() throws Exception {
        GetUserListResp resp = new GetUserListResp(null, 0L);

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("\"total\":0"), json);
        assertTrue(json.contains("\"list\":null"), json);
    }

    /** A-01 的另一半：非空时是正常数组，且不为 null。 */
    @Test
    void getUserListResp_emitsArrayListWhenPresent() throws Exception {
        GetUserListResp resp = new GetUserListResp(List.of(new UserInfo("1", 0L, "bob", null, null)), 1L);

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("\"list\":[{"), json);
    }

    @Test
    void getUserResp_includesUserWithStatusAndExtraInfo() throws Exception {
        UserInfo ui = new UserInfo("42", 0L, "bob", null, null);

        GetUserResp resp = new GetUserResp(ui, true);

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("\"user\":{"), json);
        assertTrue(json.contains("\"uid\":\"42\""), json);
        assertTrue(json.contains("\"status\":0"), json);
        assertTrue(json.contains("\"username\":\"bob\""), json);
    }

    @Test
    void updateUserResp_includesSuccessAndUser() throws Exception {
        UserInfo ui = new UserInfo("42", 0L, "bob", null, null);

        UpdateUserResp resp = new UpdateUserResp(true, ui);

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("\"success\":true"), json);
        assertTrue(json.contains("\"user\":{"), json);
        assertTrue(json.contains("\"uid\":\"42\""), json);
    }

    @Test
    void banUserResp_containsSuccess() throws Exception {
        BanUserResp resp = new BanUserResp(true);

        String json = mapper.writeValueAsString(resp);
        assertTrue(json.contains("\"success\":true"), json);
    }
}
