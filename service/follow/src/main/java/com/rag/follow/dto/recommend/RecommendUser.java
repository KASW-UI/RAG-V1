package com.rag.follow.dto.recommend;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * <p><b>推荐列表里的一项</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/dto/RecommendUser.java}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type RecommendUser struct {
 *     TargetId    int64 `json:"target_id,string"`     // ← 注意：出参也是 ,string
 *     MutualScore int32 `json:"mutual_score"`
 * }
 * </pre>
 *
 * <h3>⚠️ 出参是 <b>JSON 字符串</b>，与同域 {@code user_ids} 相反</h3>
 * <p>{@code TargetId} 的 tag <b>带 {@code ,string}</b> ⇒ B 侧输出
 * {@code "target_id":"123"}，<b>不是</b> {@code 123}。
 * 而 {@code FollowUserListResp#user_ids}（无 {@code ,string}）输出的是 {@code [123]}。
 * <b>同一个域里两种 id 的出参形态不同</b> ⇒ 客户端必须分别处理
 * —— 「顺手统一成一种」会造成线上解析错位。
 *
 * <p><b>⚠️ 为什么本类持有的是 {@link String} 而不是 {@code long}</b>：
 * {@code ,string} 的出参形态由 B 侧的「类型 + tag」共同决定，
 * 而 Jackson 侧没有「按 tag 决定是否加引号」的等价开关（要靠
 * {@code @JsonSerialize(using = ToStringSerializer.class)} 之类的 per-field serializer）。
 * <p>本 DTO <b>只用于出参</b>（请求侧根本不出现这个结构），
 * 所以「持有 String、原样写出」与「持有 long、由 serializer 加引号」
 * 产出的 JSON <b>字节相同</b>，而前者不依赖任何 Jackson 版本特性。
 * ⇒ 从 proto 的 {@code long target_id} 到本字段的转换只发生在服务层一处。
 *
 * <p><b>⚠️ {@code mutual_score} 是普通 int32</b>：无 {@code ,string} ⇒ 输出 JSON 数字。
 *
 * <h3>⚠️ 字段顺序也要对齐</h3>
 * <p>B 侧的 {@code encoding/json} 按 <b>struct 字段声明序</b>输出 ⇒
 * {@code {"target_id":"4","mutual_score":20}}。
 * <p>⚠️ <b>但 Jackson 的默认序取决于「class 还是 record」—— 两侧不一样</b>：
 * <ul>
 *   <li><b>B 侧 = 可变 class</b>：两个字段都带 {@link JsonProperty}（为了 snake_case）
 *       ⇒ 落入「属性全部显式命名」分支 ⇒ Jackson 默认按<b>字母序</b>排
 *       ⇒ <b>不加 {@link JsonPropertyOrder} 就会输出 {@code {"mutual_score":20,"target_id":"4"}}
 *       ⇒ 与 B 侧的字节顺序相反</b> ⇒ B 侧**必须**靠注解钉死；</li>
 *   <li><b>C 侧 = immutable record</b>（D5 · A9）：Jackson 对 record 默认按
 *       <b>构造参数（= 声明）序</b>输出 ⇒ <b>默认就与 B 侧一致</b>
 *       ⇒ ⚠️ <b>删掉本注解，字节不变</b>（实测，见下）。</li>
 * </ul>
 * <p>⚠️ 由此，本注解在 <b>C 侧</b>的作用**不是**「修正默认字母序」，而是
 * <b>把顺序显式钉死、防止有人重排 record 的组件</b>（组件一重排 ⇒ 无注解时字节序会静默漂移）。
 * <p>⚠️ <b>实测依据</b>（Jackson 2.19.4 · jshell 三组对照 · 2026-11-02 E-4 对拍轮）：
 * <pre>
 *   注解 = {"target_id","mutual_score"} ⇒ {"target_id":"1004","mutual_score":20}
 *   注解 = {"mutual_score","target_id"} ⇒ {"mutual_score":20,"target_id":"1004"}   ← 注解对 record 生效
 *   无注解                        ⇒ {"target_id":"1004","mutual_score":20}      ← 默认=声明序，非字母序
 * </pre>
 * ⇒ ⇒ **本注解确实生效**（第二行与第一行不同）⇒ 但**删掉它今天不会改字节**（第三行 = 第一行）。
 * <p>⚠️ 这类差异<b>不是语义差异</b>（JSON 对象无序），但它是 <b>wire 级字节差异</b>：
 * 做 body 快照比对 / 签名校验 / 严格字符串匹配的客户端会失配。
 * ⇒ 顺序必须<b>显式</b>钉死（而不是「碰巧对」）。
 *
 * <h3>⚠️ 这条差异对「变异测试」的影响</h3>
 * <p>B 侧的变异脚本用「<b>删掉</b> {@link JsonPropertyOrder}」来证明探针看得见字段序漂移
 * ⇒ 在 B 侧（class）能转红；<b>在 C 侧（record）转不了红</b>（删注解字节不变）
 * ⇒ ⇒ <b>变异必须针对被测形态设计</b>，不能照抄
 * ⇒ 本仓的探针已把 M1 改成「把注解<b>改成反序</b>」，见
 * {@code tools/difftest/follow/mutate-parity.sh}。
 *
 * @param targetId    目标用户 id（JSON <b>字符串</b>）
 * @param mutualScore 互相关度分数（JSON 数字）
 */
@JsonPropertyOrder({"target_id", "mutual_score"})
public record RecommendUser(
        @JsonProperty("target_id") String targetId,
        @JsonProperty("mutual_score") int mutualScore
) {
}
