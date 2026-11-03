package com.rag.like.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.rag.common.web.RawStringListDeserializer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <h2>🔴 「record 的 {@code @JsonCreator} 工厂会不会顶替组件上的 {@code @JsonDeserialize}」——
 * <b>区分性实验</b></h2>
 *
 * <p><b>为什么必须单独做这个实验</b>（AGENTS.md §3.3.1 / §3.3.2）：
 * <blockquote>
 * 本仓 3 个 DTO（{@code GetLikeCountReq} / {@code GetLikeStateReq} / {@code LikeActionReq}）
 * 的 javadoc 里写着「<b>实测证据</b>：record 组件上的 {@code @JsonDeserialize} <b>不会被应用</b>，
 * 所以注解必须同时写在工厂参数上」。
 * ⇒ 这是一句<b>以机制断言形式出现的「实测证据」</b> ⇒ AGENTS §3.3.2 要求它必须有
 * <b>区分性</b>实验支撑 —— 而 {@code LikeDtoWireShapeTest} 只断言<b>结果</b>
 * （用真 DTO 解析），<b>分不清</b>是「组件上的注解」在起作用还是「工厂参数上的注解」在起作用。
 * </blockquote>
 *
 * <h3>🔴 本类就是那个区分性实验</h3>
 * <p>做法：构造 <b>4 个同形 record</b>（只挪动注解位置，其余完全相同），
 * 用<b>同一个裸 mapper</b> 喂<b>同一个判别性输入</b> {@code {"ids":[true]}}：
 * <ul>
 *   <li>{@link RawStringListDeserializer} 生效 ⇒ 抛 {@code MismatchedInputException}
 *       （B 侧对 bool 元素也是 {@code errTypeMismatch}）；</li>
 *   <li>默认 {@code String} 反序列化器生效 ⇒ <b>不抛</b>，得到 {@code ["true"]}
 *       （Jackson 的 {@code StringDeserializer} 接受布尔并转成字符串）。</li>
 * </ul>
 * ⇒ ⇒ 4 个 fixture 的结果<b>两两不同</b> ⇒ 才能说清「哪一处注解真正生效」。
 *
 * <p>⚠️ <b>本类不依赖应用上下文</b>（裸 {@link ObjectMapper}）——
 * 理由与 {@code LikeDtoWireShapeTest} 相同：应用 mapper 上还挂着共享层的全局强转补强，
 * 混在一起就<b>分不清谁在起作用</b>了。
 */
class LikeJsonCreatorAnnotationScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 判别性输入：布尔元素 —— 专用的反序列化器会拒，默认反序列化器会放行并转成 "true"。 */
    private static final String DISCRIMINATING_JSON = "{\"ids\":[true]}";

    // ==================================================================
    // 4 个同形 fixture —— 唯一的差别是「注解摆在哪儿」
    // ==================================================================

    /** ① 只有组件上的注解，**没有** {@code @JsonCreator} ⇒ 走 record 规范构造器。 */
    record ComponentOnly(
            @JsonDeserialize(using = RawStringListDeserializer.class) List<String> ids) {
    }

    /** ② 只有工厂参数上的注解，组件上**没有** ⇒ 走显式工厂。 */
    record FactoryParamOnly(List<String> ids) {

        @JsonCreator
        static FactoryParamOnly fromJson(@JsonProperty("ids")
                                         @JsonDeserialize(using = RawStringListDeserializer.class)
                                         List<String> ids) {
            return new FactoryParamOnly(ids);
        }
    }

    /** ③ 组件与工厂参数<b>两处都有</b>注解（= 本仓 3 个 DTO 的真实形态）。 */
    record Both(
            @JsonDeserialize(using = RawStringListDeserializer.class) List<String> ids) {

        @JsonCreator
        static Both fromJson(@JsonProperty("ids")
                             @JsonDeserialize(using = RawStringListDeserializer.class)
                             List<String> ids) {
            return new Both(ids);
        }
    }

    /**
     * ④ 🔴 **有显式工厂，组件与工厂参数同名**（{@code @JsonProperty("ids")}）⇒ 组件上的注解会**被继承**。
     */
    record FactoryWithComponentOnly(
            @JsonDeserialize(using = RawStringListDeserializer.class) List<String> ids) {

        @JsonCreator
        static FactoryWithComponentOnly fromJson(@JsonProperty("ids") List<String> ids) {
            return new FactoryWithComponentOnly(ids);
        }
    }

    /**
     * ⑤ 🔴 **有显式工厂，但工厂参数的 JSON 名与组件名不同**（组件叫 {@code ids}，
     * 工厂参数映射成 {@code target_ids}）⇒ 继承**断链** ⇒ 组件上的注解**不生效**。
     * ⇒ 这就是「工厂参数上必须再写一遍」的真实适用场景。
     */
    record FactoryWithRenamedParam(
            @JsonDeserialize(using = RawStringListDeserializer.class) List<String> ids) {

        @JsonCreator
        static FactoryWithRenamedParam fromJson(@JsonProperty("target_ids") List<String> ids) {
            return new FactoryWithRenamedParam(ids);
        }
    }

    // ==================================================================
    // 断言
    // ==================================================================

    @Test
    @DisplayName("① 没有 @JsonCreator 时，组件上的 @JsonDeserialize **生效**（走规范构造器）")
    void componentAnnotationAppliesWithoutJsonCreator() {
        assertThatThrownBy(() -> MAPPER.readValue(DISCRIMINATING_JSON, ComponentOnly.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("② 有 @JsonCreator 时，**工厂参数上**的 @JsonDeserialize 生效")
    void factoryParameterAnnotationApplies() {
        assertThatThrownBy(() -> MAPPER.readValue(DISCRIMINATING_JSON, FactoryParamOnly.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("③ 两处都写时生效（本仓 3 个 DTO 的真实形态）")
    void bothAnnotationsApply() {
        assertThatThrownBy(() -> MAPPER.readValue(DISCRIMINATING_JSON, Both.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("🔴 ④ 区分性结论：工厂参数与组件**同名**时，组件上的 @JsonDeserialize **会被继承**"
            + "（Jackson 2.19.4 的 creator 属性继承）⇒ 「组件上的注解是装饰品」这句**是错的**")
    void componentAnnotationIsInheritedWhenCreatorParamNameMatches() {
        assertThatThrownBy(() -> MAPPER.readValue(DISCRIMINATING_JSON, FactoryWithComponentOnly.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("🔴 ⑤ 区分性结论：工厂参数的 JSON 名与组件名**不同**时 ⇒ 继承断链 ⇒ "
            + "组件上的 @JsonDeserialize **不生效** ⇒ 「工厂参数上必须再写一遍」的真实适用场景")
    void componentAnnotationIsDroppedWhenCreatorParamNameDiffers() {
        // ⇒ 默认反序列化器接了 bool ⇒ 解析成功、得到 ["true"] ⇒ 专用反序列化器没被调用。
        assertThatCode(() -> {
            FactoryWithRenamedParam parsed =
                    MAPPER.readValue("{\"target_ids\":[true]}", FactoryWithRenamedParam.class);
            assertThat(parsed.ids()).containsExactly("true");
        }).doesNotThrowAnyException();
    }
}
