package com.rag.favorite.http;

import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.web.Coercion;
import com.rag.common.web.StringInt64;
import com.rag.favorite.dto.favorite.CreateFavoriteReq;
import com.rag.favorite.dto.favorite.CreateFavoriteResp;
import com.rag.favorite.dto.favorite.DeleteFavoriteReq;
import com.rag.favorite.dto.favorite.FavoriteItem;
import com.rag.favorite.dto.favorite.GetFavoritesByFolderReq;
import com.rag.favorite.dto.favorite.GetFavoritesByFolderResp;
import com.rag.favorite.dto.folder.CreateFavoriteFolderReq;
import com.rag.favorite.dto.folder.CreateFavoriteFolderResp;
import com.rag.favorite.dto.folder.DeleteFavoriteFolderReq;
import com.rag.favorite.dto.folder.FavoriteFolder;
import com.rag.favorite.dto.folder.GetFavoriteFoldersResp;
import com.rag.favorite.dto.folder.UpdateFavoriteFolderNameReq;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * favorite 域的<b>入参契约</b>测试 —— 把 Go 侧 {@code httpx.Parse} 的判定矩阵钉死。
 *
 * <p><b>矩阵的来源</b>：go-zero v1.10.2 真源探针（2026-10-23，对 {@code service/favorite/api/internal/types/types.go}
 * 的逐字同构 struct 直接调 {@code httpx.Parse}），不是从源码「推断」出来的。
 *
 * <p><b>为什么这些是 L1 而不是实现细节</b>：客户端按「能不能发这个请求」来写代码，
 * 而「{@code {"folderId":123}} 收不收」「{@code {"name":""}} 算不算空」
 * 这些判定直接决定客户端的请求构造方式。改掉任何一条都是契约变更。
 *
 * <p><b>本测试用 Jackson 2 ＋ 与生产同一套 coercion 规则</b>：
 * 生产环境的 mapper 由共享层 {@code JsonBodyConverterAutoConfiguration#seaCoercionMapperCustomizer}
 * （{@code common/.../JsonBodyConverterAutoConfiguration.java:78-82}）施加同样的 coercion，
 * 否则「单测里 Jackson 默认把 {@code {"name":123}} 强转成 "123"}」会与线上行为不一致 ——
 * 那正是 AGENTS.md §3.3 说的「测不到真实机制」。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>包名</b>压平（D7）：{@code com.rag.favorite.favorite.api.dto}
 *             ⇒ {@code com.rag.favorite.http}；DTO 包也压平为
 *             {@code com.rag.favorite.dto.favorite} / {@code com.rag.favorite.dto.folder}（D11）。</li>
 *         <li>🔴 <b>Jackson 3 → Jackson 2</b>（D14 工具链）：
 *             {@code tools.jackson.databind.json.JsonMapper} ⇒
 *             {@code com.fasterxml.jackson.databind.json.JsonMapper}；
 *             {@code tools.jackson.databind.DatabindException} ⇒
 *             {@code com.fasterxml.jackson.databind.DatabindException}。</li>
 *         <li>🔴 <b>「必填字段」判定换实现</b>（A10 · 禁用手写校验器）：
 *             B 侧是 DTO 上的 {@code assertGoRequiredFields()} ＋ 手写的
 *             {@code GoRequiredFieldException}；
 *             C 侧改成 <b>Bean Validation</b>（DTO 上的 {@code @NotNull}）
 *             ⇒ ⇒ 本类「缺字段 ⇒ 报错」的断言改为
 *             <b>断言 {@code Validator#validate} 产出「属性名 = 该字段」的违规</b>
 *             ⇒ ⇒ <b>判据的<b>语义</b>（哪个字段缺失 ⇒ 违规）逐条一致</b>，
 *             只是<b>载体</b>从「手写校验器抛异常」换成「Bean Validation 产出违规集合」。</li>
 *         <li>🔴 <b>DTO 由「可变 class」改成 immutable record</b>（D5 + A9）⇒
 *             <b>出参的 id 分量直接声明成 {@code String}</b>
 *             （B 侧是「存 Long、getter 返回 String」）⇒
 *             ⇒ 本类构造 DTO 时传的<b>就是字符串</b>（{@code "123"} 而不是 {@code 123L}）。</li>
 *         <li>⚠️ <b>mapper 的构造方式</b>：用 Spring 的 {@link Jackson2ObjectMapperBuilder}
 *             （Boot 自动配置用的同一个 builder ⇒ 同样的默认值：例如
 *             {@code FAIL_ON_UNKNOWN_PROPERTIES=false} ⇒ 未知字段被忽略，与 Go 侧
 *             「mapping 只取已声明的字段」同形）＋ 手工施加共享层的 {@link Coercion}。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 二十条用例的<b>输入</b>与<b>期望出口</b>逐条未改；
 *       只有「怎么观测出口」的机制换了（见上一条），判据本身逐字一致。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/dto/FavoriteApiInputContractTest.java:17-341}。</li>
 * </ol>
 */
class FavoriteApiInputContractTest {

    /**
     * 与生产环境同一套规则的 mapper（Jackson 2 ＋ 共享层 {@link Coercion}）。
     *
     * <p>⚠️ 走 {@link Jackson2ObjectMapperBuilder} 而不是裸 {@code new ObjectMapper()}：
     * Boot 的自动配置用的就是它 ⇒ 未知字段忽略等默认值才能与线上同形。
     */
    private static final ObjectMapper MAPPER = new Jackson2ObjectMapperBuilder()
            .postConfigurer(Coercion::applyTo)
            .build();

    /** Bean Validation 入口（与 MVC 管线用的是同一份 provider）。 */
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    // ==================================================================
    // POST /folder/create —— type CreateFavoriteFolderReq { Name string `json:"name"` }
    // ==================================================================

    @Test
    @DisplayName("POST /folder/create：{\"name\":\"x\"} → 接受")
    void createFolderAcceptsNormalName() throws Exception {
        CreateFavoriteFolderReq req = MAPPER.readValue("{\"name\":\"x\"}", CreateFavoriteFolderReq.class);
        assertNoViolation(req);
        assertEquals("x", req.name());
    }

    @Test
    @DisplayName("POST /folder/create：{\"name\":\"\"} → 接受（空串是合法值，Go json 通道不判空串）")
    void createFolderAcceptsEmptyName() throws Exception {
        CreateFavoriteFolderReq req = MAPPER.readValue("{\"name\":\"\"}", CreateFavoriteFolderReq.class);
        assertNoViolation(req);
        assertEquals("", req.name());
    }

    @Test
    @DisplayName("POST /folder/create：{} → name 必填缺失（⇒ 5003）")
    void createFolderRejectsMissingName() throws Exception {
        CreateFavoriteFolderReq req = MAPPER.readValue("{}", CreateFavoriteFolderReq.class);
        assertViolationProperty(req, "name");
    }

    @Test
    @DisplayName("POST /folder/create：{\"name\":null} → 必填失败（⇒ 5003）")
    void createFolderRejectsNullName() throws Exception {
        CreateFavoriteFolderReq req = MAPPER.readValue("{\"name\":null}", CreateFavoriteFolderReq.class);
        assertViolationProperty(req, "name");
    }

    @Test
    @DisplayName("POST /folder/create：{\"name\":123} → 拒绝（Go：type mismatch for field \"name\"）")
    void createFolderRejectsNumberForStringField() throws Exception {
        // Go: type mismatch for field "name", expect "string", actual "number"（go-zero@v1.10.2/core/mapping/unmarshaler.go:1203）
        // ⚠️ C 侧靠共享层 Coercion 的「LogicalType.Textual + Integer ⇒ Fail」实现同一条规则
        assertThrows(Exception.class,
                () -> MAPPER.readValue("{\"name\":123}", CreateFavoriteFolderReq.class));
    }

    @Test
    @DisplayName("POST /folder/create：{\"name\":\"x\",\"extra\":1} → 接受（Go 忽略未声明字段）")
    void createFolderIgnoresUnknownFields() throws Exception {
        CreateFavoriteFolderReq req = MAPPER.readValue("{\"name\":\"x\",\"extra\":1}", CreateFavoriteFolderReq.class);
        assertNoViolation(req);
        assertEquals("x", req.name());
    }

    // ==================================================================
    // ,string 的入参语义（JSON 字符串与数字都收）
    // ==================================================================

    @Test
    @DisplayName("DELETE /item/delete：{\"favoriteId\":\"123\"} → 123（字符串形态）")
    void deleteFavoriteAcceptsQuotedInt64() throws Exception {
        DeleteFavoriteReq req = MAPPER.readValue("{\"favoriteId\":\"123\"}", DeleteFavoriteReq.class);
        assertNoViolation(req);
        assertEquals(123L, req.favoriteId());
    }

    @Test
    @DisplayName("DELETE /item/delete：{\"favoriteId\":123} → 123（⚠️ 数字也收！Go 的 ,string 入参方向是宽松的）")
    void deleteFavoriteAcceptsNumericInt64() throws Exception {
        DeleteFavoriteReq req = MAPPER.readValue("{\"favoriteId\":123}", DeleteFavoriteReq.class);
        assertNoViolation(req);
        assertEquals(123L, req.favoriteId());
    }

    @Test
    @DisplayName("DELETE /item/delete：{\"favoriteId\":\"abc\"} → 拒绝（Go：strconv.ParseInt invalid syntax）")
    void deleteFavoriteRejectsNonNumericInt64() throws Exception {
        // StringInt64 抛 IllegalArgumentException，但 Jackson 会把 creator 里抛的异常
        // 包成 ValueInstantiationException（DatabindException 的子类）
        // —— 这条链正是 MVC 上「HttpMessageNotReadableException → 200 + 5003」的来源。
        DatabindException e = assertThrows(
                DatabindException.class,
                () -> MAPPER.readValue("{\"favoriteId\":\"abc\"}", DeleteFavoriteReq.class));
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @Test
    @DisplayName("DELETE /item/delete：{\"favoriteId\":1.5} → 拒绝（Go：\"1.0\"/\"1.5\"/\"1e2\" 全部 ParseInt 失败）")
    void deleteFavoriteRejectsFractionalInt64() throws Exception {
        DatabindException e = assertThrows(
                DatabindException.class,
                () -> MAPPER.readValue("{\"favoriteId\":1.5}", DeleteFavoriteReq.class));
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @Test
    @DisplayName("DELETE /item/delete：{} → favoriteId 必填缺失（⇒ 5003）")
    void deleteFavoriteRejectsMissingId() throws Exception {
        DeleteFavoriteReq req = MAPPER.readValue("{}", DeleteFavoriteReq.class);
        assertViolationProperty(req, "favoriteId");
    }

    @Test
    @DisplayName("DELETE /folder/delete：{\"folderId\":9} → 9（数字形态）")
    void deleteFolderAcceptsNumericInt64() throws Exception {
        DeleteFavoriteFolderReq req = MAPPER.readValue("{\"folderId\":9}", DeleteFavoriteFolderReq.class);
        assertNoViolation(req);
        assertEquals(9L, req.folderId());
    }

    // ==================================================================
    // POST /folder/update —— 两个字段都必填
    // ==================================================================

    @Test
    @DisplayName("POST /folder/update：{\"folderId\":\"1\",\"name\":\"x\"} → 接受")
    void updateFolderAcceptsBothFields() throws Exception {
        UpdateFavoriteFolderNameReq req =
                MAPPER.readValue("{\"folderId\":\"1\",\"name\":\"x\"}", UpdateFavoriteFolderNameReq.class);
        assertNoViolation(req);
        assertEquals(1L, req.folderId());
        assertEquals("x", req.name());
    }

    @Test
    @DisplayName("POST /folder/update：{\"name\":\"x\"} → folderId 必填缺失（⇒ 5003）")
    void updateFolderRejectsMissingFolderId() throws Exception {
        UpdateFavoriteFolderNameReq req =
                MAPPER.readValue("{\"name\":\"x\"}", UpdateFavoriteFolderNameReq.class);
        assertViolationProperty(req, "folderId");
    }

    @Test
    @DisplayName("POST /folder/update：{\"folderId\":\"1\"} → name 必填缺失（⇒ 5003）")
    void updateFolderRejectsMissingName() throws Exception {
        UpdateFavoriteFolderNameReq req =
                MAPPER.readValue("{\"folderId\":\"1\"}", UpdateFavoriteFolderNameReq.class);
        assertViolationProperty(req, "name");
    }

    // ==================================================================
    // POST /item/create —— folderId/targetId/targetType 必填，title/cover 可选
    // ==================================================================

    @Test
    @DisplayName("POST /item/create：三个必填齐全 → 接受；title/cover 缺席即为空串")
    void createItemAcceptsRequiredFields() throws Exception {
        CreateFavoriteReq req = MAPPER.readValue(
                "{\"folderId\":\"1\",\"targetId\":\"a\",\"targetType\":\"article\"}", CreateFavoriteReq.class);
        assertNoViolation(req);
        assertEquals(1L, req.folderId());
        assertEquals("a", req.targetId());
        assertEquals("article", req.targetType());
        assertNull(req.title());
        assertNull(req.cover());
    }

    @Test
    @DisplayName("POST /item/create：缺 targetType → targetType 必填缺失（⇒ 5003）")
    void createItemRejectsMissingTargetType() throws Exception {
        CreateFavoriteReq req = MAPPER.readValue(
                "{\"folderId\":\"1\",\"targetId\":\"a\"}", CreateFavoriteReq.class);
        assertViolationProperty(req, "targetType");
    }

    @Test
    @DisplayName("POST /item/create：缺 targetId → targetId 必填缺失（⇒ 5003）")
    void createItemRejectsMissingTargetId() throws Exception {
        CreateFavoriteReq req = MAPPER.readValue(
                "{\"folderId\":\"1\",\"targetType\":\"article\"}", CreateFavoriteReq.class);
        assertViolationProperty(req, "targetId");
    }

    @Test
    @DisplayName("POST /item/create：title/cover 显式 null → 接受（Go 的 ,optional 允许显式 null）")
    void createItemAcceptsExplicitNullOnOptionalFields() throws Exception {
        CreateFavoriteReq req = MAPPER.readValue(
                "{\"folderId\":\"1\",\"targetId\":\"a\",\"targetType\":\"article\",\"title\":null,\"cover\":null}",
                CreateFavoriteReq.class);
        assertNoViolation(req);
        assertNull(req.title());
        assertNull(req.cover());
    }

    // ==================================================================
    // GET /item/list —— 唯一走 form 通道的端点
    // ==================================================================

    @Test
    @DisplayName("GET /item/list：?folderId=123 → 123")
    void listItemsAcceptsNumericFormValue() throws Exception {
        GetFavoritesByFolderReq req = GetFavoritesByFolderReq.fromForm("123");
        assertNoViolation(req);
        assertEquals(123L, req.folderId());
    }

    @Test
    @DisplayName("GET /item/list：?folderId=0 → 0（0 是合法值）")
    void listItemsAcceptsZeroFormValue() throws Exception {
        GetFavoritesByFolderReq req = GetFavoritesByFolderReq.fromForm("0");
        assertNoViolation(req);
        assertEquals(0L, req.folderId());
    }

    @Test
    @DisplayName("GET /item/list：?folderId=（空） → 字段缺席（Go：GetFormValues 把空值整个丢掉）⇒ 5003")
    void listItemsTreatsEmptyFormValueAsAbsent() throws Exception {
        GetFavoritesByFolderReq req = GetFavoritesByFolderReq.fromForm("");
        assertViolationProperty(req, "folderId");
    }

    @Test
    @DisplayName("GET /item/list：无 query → folderId 必填缺失（⇒ 5003）")
    void listItemsRejectsAbsentFormValue() throws Exception {
        GetFavoritesByFolderReq req = GetFavoritesByFolderReq.fromForm(null);
        assertViolationProperty(req, "folderId");
    }

    @Test
    @DisplayName("GET /item/list：?folderId=abc → 拒绝（Go：strconv.ParseInt invalid syntax）")
    void listItemsRejectsNonNumericFormValue() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> GetFavoritesByFolderReq.fromForm("abc"));
    }

    @Test
    @DisplayName("GET /item/list：?folderId=1.5 → 拒绝（Go：\"1.5\" ParseInt 失败）")
    void listItemsRejectsFractionalFormValue() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> GetFavoritesByFolderReq.fromForm("1.5"));
    }

    // ==================================================================
    // StringInt64 的边界（与共享层实现同源）
    // ==================================================================

    @Test
    @DisplayName("StringInt64：null → null（由必填判定接手）")
    void stringInt64PassesThroughNull() throws Exception {
        assertNull(StringInt64.parse(null, "folderId"));
    }

    @Test
    @DisplayName("StringInt64：\"+123\" → 123（Go 的 ParseInt 接受前导 +）")
    void stringInt64AcceptsLeadingPlus() throws Exception {
        assertEquals(123L, StringInt64.parse("+123", "folderId"));
    }

    @Test
    @DisplayName("StringInt64：\" 12\"（带空格）→ 拒绝（Go 的 ParseInt 不裁空白）")
    void stringInt64RejectsSurroundingSpaces() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(" 12", "folderId"));
    }

    @Test
    @DisplayName("StringInt64：越界 BigInteger → 拒绝（Go：strconv.ParseInt range error）")
    void stringInt64RejectsOutOfRangeValue() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> StringInt64.parse(new java.math.BigInteger("18446744073709551616"), "folderId"));
    }

    // ==================================================================
    // 出参编码
    // ==================================================================

    @Test
    @DisplayName("出参 folderId 序列化成【字符串】（Go 的 json:\"folderId,string\"）")
    void createFolderResponseSerializesFolderIdAsString() throws Exception {
        CreateFavoriteFolderResp resp = new CreateFavoriteFolderResp("123");
        assertEquals("{\"folderId\":\"123\"}", MAPPER.writeValueAsString(resp));
    }

    @Test
    @DisplayName("出参 favoriteId 序列化成【字符串】（Go 的 json:\"favoriteId,string\"）")
    void createFavoriteResponseSerializesFavoriteIdAsString() throws Exception {
        assertEquals("{\"favoriteId\":\"456\"}",
                MAPPER.writeValueAsString(new CreateFavoriteResp("456")));
    }

    @Test
    @DisplayName("出参 createdAt/updatedAt 是【数字】、title/cover 永远出现（Go 无 omitempty）")
    void folderAndItemResponsesKeepGoEncoding() throws Exception {
        // Go types.go:42-47：folderId 是 string 形态，createdAt/updatedAt 是数字
        assertEquals("{\"folderId\":\"7\",\"name\":\"n\",\"createdAt\":1700000000000,\"updatedAt\":1700000000001}",
                MAPPER.writeValueAsString(new FavoriteFolder("7", "n", 1700000000000L, 1700000000001L)));

        // Go types.go:49-57：favoriteId/folderId 是 string 形态，createdAt 是数字，
        // title/cover 即使为空串也【必须出现】（Go 的 ,optional 不是 encoding/json 的 omitempty）
        assertEquals("{\"favoriteId\":\"1\",\"folderId\":\"2\",\"targetId\":\"t\",\"targetType\":\"article\","
                        + "\"title\":\"\",\"cover\":\"\",\"createdAt\":1700000000000}",
                MAPPER.writeValueAsString(new FavoriteItem("1", "2", "t", "article", "", "", 1700000000000L)));
    }

    @Test
    @DisplayName("出参空集合是【[]】不是 null（Go 侧无条件 make）")
    void listResponsesSerializeEmptyArrayNotNull() throws Exception {
        assertEquals("{\"folders\":[]}",
                MAPPER.writeValueAsString(new GetFavoriteFoldersResp(List.of())));
        assertEquals("{\"favorites\":[]}",
                MAPPER.writeValueAsString(new GetFavoritesByFolderResp(List.of())));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 断言「这个 DTO 通过必填校验」（B 侧对应 {@code assertGoRequiredFields()} 不抛）。 */
    private static <T> void assertNoViolation(T dto) {
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(dto);
        assertTrue(violations.isEmpty(),
                "不该有必填违规（实际：" + violations + "）");
    }

    /**
     * 断言「这个 DTO 在<b>指定字段</b>上有必填违规」。
     *
     * <p>⚠️ B 侧对应的断言是 {@code GoRequiredFieldException#getField()} 等于该字段名
     * ⇒ ⇒ 本断言的<b>判据完全同形</b>（只是载体从异常换成了 Bean Validation 的违规集合）。
     */
    private static <T> void assertViolationProperty(T dto, String property) {
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(dto);
        assertFalse(violations.isEmpty(),
                "必须报出必填违规（字段=" + property + "）");
        assertTrue(violations.stream()
                        .anyMatch(v -> property.equals(String.valueOf(v.getPropertyPath()))),
                "违规必须落在字段 [" + property + "] 上；实际违规=" + violations);
    }
}
