package com.rag.favorite.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.communityfact.AuthorityFact;
import com.rag.common.communityfact.Event;
import com.rag.common.communityfact.SubjectRef;
import com.rag.common.communityfact.TechnicalReceipt;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 🔴 权威事实的 <b>v1 形状</b> —— 对应 Go
 * {@code service/favorite/rpc/internal/model/favorite_authority.go:27-43} 的
 * {@code FavoriteAuthorityFact} 与 {@code :106-112} 的 {@code FavoriteSubjectRef}。
 *
 * <h2>为什么不能用 {@code common/communityfact/AuthorityFact}</h2>
 *
 * <p>共享层 {@code com.rag.common.communityfact.AuthorityFact} 的 {@code subject_ref} 是
 * {@code SubjectRef}，只有 <b>2 个字段</b>：
 * <pre>
 * { "issuer": "rtw.identity", "subject_id": "1001" }
 * </pre>
 * 而 Go favorite 的 v1 权威响应里 {@code subject_ref} 是 <b>3 个字段</b>：
 * <pre>
 * { "authority_id": "rtw.identity", "tenant_id": "platform", "subject_id": "1001" }
 * </pre>
 * （Go {@code favorite_fact.go:78-82}；样例见 Go 的
 * {@code FACT_AUTHORITY_ACCEPTANCE.md:33,45}。）
 *
 * <p>⇒ {@code /internal/v1/favorite/facts/{producer}/{event_id}} 的响应体
 * <b>不能</b>用共享层的 {@code AuthorityFact}；只有 {@code /internal/v2/...} 才可以
 * （v2 的 payload 里 {@code subject_ref} 本来就是 2 字段，
 * 见 {@code FavoriteSubjectRefV2} 的注释「The legacy fixed platform slot is
 * deliberately absent」）。
 *
 * <p>⚠️ 这是一个 <b>L1 响应体形状</b>：下游 binder（BTW）按字段名解析。
 * 多写、少写、改名都是 wire 破坏。
 *
 * <h2>{@code event} / {@code technical_receipt} 直接复用共享层</h2>
 *
 * <p>逐字段核对过（docs/FileTree/favorite/rpc.md §12.1）：Go 的
 * {@code FavoriteWireEvent}（favorite_delivery.go:26-36，9 字段）与
 * {@code FavoriteTechnicalReceipt}（:38-46，7 字段）与共享层的
 * {@code Event} / {@code TechnicalReceipt} <b>逐字段逐 tag 一致</b> ⇒ 直接复用，不另立类型。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.favorite.favorite.rpc.domain} 压平为
 *           {@code com.rag.favorite.domain.model}（D7；映射表 §1.3 的
 *           「{@code rpc/domain/FavoriteAuthorityFact.java} ⇒ {@code domain/model/FavoriteAuthorityFact.java}」行）；</li>
 *       <li>共享层 {@code Wire} 1:N 拆分 ⇒ {@code Wire.Event} → 顶层 {@link Event}、
 *           {@code Wire.TechnicalReceipt} → 顶层 {@link TechnicalReceipt}、
 *           {@code Wire.SubjectRef} → 顶层 {@link SubjectRef}、
 *           {@code Wire.AuthorityFact} → 顶层 {@link AuthorityFact}；</li>
 *       <li>两个嵌套类的<b>构造器</b>改用 Lombok 的
 *           {@code @NoArgsConstructor @AllArgsConstructor}（D18）⇒ 展开见下。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。三个 JSON 字段名与顺序、{@code omitempty} 语义、
 *       {@code toV2} 的压扁规则逐条未改；<b>唯一必须手写的是 {@code equals}/{@code hashCode}</b>
 *       —— 见 {@link SubjectRefV1} 的说明（Lombok 的 {@code @EqualsAndHashCode} 在此<b>不等价</b>）。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/domain/FavoriteAuthorityFact.java:42-212}
 *       （类声明 :42-46 · {@code SubjectRefV1} :55-109 · {@code FactV1} :116-162 ·
 *       {@code toV2} :194-212）。</li>
 * </ol>
 */
public final class FavoriteAuthorityFact {

    private FavoriteAuthorityFact() {
        // 响应体，只用于序列化
    }

    /**
     * Go {@code FavoriteSubjectRef}（favorite_fact.go:78-82）。
     *
     * <p><b>三字段是 v1 的硬契约</b>：{@code tenant_id} 在 v2 里被刻意去掉
     * （{@code FavoriteSubjectRefV2} 的注释明写「The legacy fixed platform slot is
     * deliberately absent from the v2 EventSpec」）。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static final class SubjectRefV1 {
     *
     *     @JsonProperty("authority_id")
     *     public String authorityId;
     *
     *     @JsonProperty("tenant_id")
     *     public String tenantId;
     *
     *     @JsonProperty("subject_id")
     *     public String subjectId;
     *
     *     public SubjectRefV1() {
     *     }
     *
     *     public SubjectRefV1(String authorityId, String tenantId, String subjectId) {
     *         this.authorityId = authorityId;
     *         this.tenantId = tenantId;
     *         this.subjectId = subjectId;
     *     }
     * }</pre>
     *
     * <p>⚠️ <b>{@code equals} / {@code hashCode} 刻意<b>不</b>用 Lombok</b>：
     * B 侧（B 侧 {@code FavoriteAuthorityFact.java:84-108}）把 {@code subjectId == null}
     * <b>归一化成空串</b>再比（{@code subjectID()}），而 Lombok 的 {@code @EqualsAndHashCode}
     * 用的是 {@code Objects.equals(a, b)} ⇒ {@code null} 与 {@code ""} <b>不再相等</b>
     * ⇒ 那是<b>可观察的行为差异</b>（retract 的前驱一致性校验会误判）⇒ 必须手写。
     */
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class SubjectRefV1 {

        @JsonProperty("authority_id")
        public String authorityId;

        @JsonProperty("tenant_id")
        public String tenantId;

        @JsonProperty("subject_id")
        public String subjectId;

        /**
         * 🔴 Go 在比较时用<b>值相等</b>（favorite_authority.go:275
         * {@code prior.SubjectRef != fact.SubjectRef}）。
         *
         * <p>Go 的 struct 可直接用 {@code !=}，Java 侧必须显式给 {@code equals}，
         * 否则引用比较永远不等 ⇒ <b>retract 的前驱一致性校验会永远失败</b>
         * ⇒ 所有 retract 的权威读都返回 404。
         * ⇒ 这个方法不是"锦上添花"，它是那条断言的正确性载体。
         *
         * <p>⚠️ {@code subjectId} 为 {@code null} 时按<b>空串</b>参与比较
         * （B 侧 {@code FavoriteAuthorityFact.java:102-104} 的 {@code subjectID()}）
         * ⇒ 与 Lombok 的 {@code @EqualsAndHashCode} <b>不等价</b>，故手写。
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SubjectRefV1 other)) {
                return false;
            }
            return eq(authorityId, other.authorityId)
                    && eq(tenantId, other.tenantId)
                    && eq(subjectID(), other.subjectID());
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(authorityId, tenantId, subjectID());
        }

        private String subjectID() {
            return subjectId == null ? "" : subjectId;
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /**
     * Go {@code FavoriteAuthorityFact}（favorite_authority.go:27-33）。
     *
     * <p>字段顺序与 Go 的 struct 声明顺序逐条一致（JSON 对象顺序无语义，但便于逐行 review）。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static final class FactV1 {
     *
     *     @JsonProperty("event")
     *     public Event event;
     *
     *     @JsonProperty("subject_ref")
     *     public SubjectRefV1 subjectRef;
     *
     *     @JsonInclude(JsonInclude.Include.NON_EMPTY)
     *     @JsonProperty("predecessor_event_id")
     *     public String predecessorEventId;
     *
     *     @JsonProperty("technical_receipt")
     *     public TechnicalReceipt technicalReceipt;
     *
     *     @JsonProperty("source_event_hash")
     *     public String sourceEventHash;
     *
     *     public FactV1() {
     *     }
     *
     *     public FactV1(Event event, SubjectRefV1 subjectRef, String predecessorEventId,
     *                   TechnicalReceipt technicalReceipt, String sourceEventHash) {
     *         this.event = event;
     *         this.subjectRef = subjectRef;
     *         this.predecessorEventId = predecessorEventId;
     *         this.technicalReceipt = technicalReceipt;
     *         this.sourceEventHash = sourceEventHash;
     *     }
     * }</pre>
     */
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class FactV1 {

        /** Go {@code Event FavoriteWireEvent `json:"event"`} ⇒ 复用 {@code Wire.Event}。 */
        @JsonProperty("event")
        public Event event;

        /** Go {@code SubjectRef FavoriteSubjectRef `json:"subject_ref"`} ⇒ 见 {@link SubjectRefV1}。 */
        @JsonProperty("subject_ref")
        public SubjectRefV1 subjectRef;

        /**
         * Go {@code PredecessorEventID string `json:"predecessor_event_id,omitempty"`}。
         *
         * <p>⚠️ {@code omitempty}：assert 行<b>不带</b>这个字段（favorite_authority.go:173-175
         * 只在 {@code AggregateVersion == 2} 时赋值）⇒ 必须用
         * {@link JsonInclude.Include#NON_EMPTY}，与 Go 的 omitempty 语义一致。
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("predecessor_event_id")
        public String predecessorEventId;

        /** Go {@code TechnicalReceipt FavoriteTechnicalReceipt `json:"technical_receipt"`}。 */
        @JsonProperty("technical_receipt")
        public TechnicalReceipt technicalReceipt;

        /**
         * Go {@code SourceEventHash string `json:"source_event_hash"`}。
         *
         * <p>= {@code favoriteJCSHash([]byte(row.Payload))}，且必须与
         * {@code row.TechnicalInputHash} 相等（favorite_authority.go:164-166）——
         * 这就是「RTW 的不可变 outbox」与「DC 的技术回执」互相印证的地方。
         */
        @JsonProperty("source_event_hash")
        public String sourceEventHash;
    }

    /**
     * 🔴 对应 Go {@code AuthoritativeFavoriteFactV2}（favorite_authority.go:195-204）——
     * 把 v1 的权威事实<b>压扁</b>成 v2 形状。
     *
     * <pre>
     * return FavoriteAuthorityFactV2{Event: fact.Event,
     *     SubjectRef:         FavoriteSubjectRefV2{Issuer: fact.SubjectRef.AuthorityID, SubjectID: fact.SubjectRef.SubjectID},
     *     PredecessorEventID: fact.PredecessorEventID,
     *     TechnicalReceipt:   fact.TechnicalReceipt,
     *     SourceEventHash:    fact.SourceEventHash}, nil
     * </pre>
     *
     * <p>⇒ <b>只有 {@code subject_ref} 变形状</b>：三字段 → 两字段
     * （{@code authority_id} <b>改名</b>成 {@code issuer}，<b>{@code tenant_id} 被丢弃</b>），
     * 其余 4 个字段原样带过。
     *
     * <h2>为什么可以复用共享层的 {@code AuthorityFact}</h2>
     * <p>它与 Go 的 {@code FavoriteAuthorityFactV2} <b>逐字段逐 tag 一致</b>
     * （event / subject_ref / predecessor_event_id(omitempty) / technical_receipt / source_event_hash）
     * ⇒ <b>不另立类型</b> —— 与本类复用 {@code Wire.Event} / {@code Wire.TechnicalReceipt} 同一理由。
     *
     * <h2>⚠️ {@code tenant_id} 为什么被丢弃</h2>
     * <p>Go 的 {@code FavoriteSubjectRefV2} 注释明写：
     * <pre>
     * // FavoriteSubjectRefV2 contains only the RTW-owned identity pair. The legacy
     * // fixed platform slot is deliberately absent from the v2 EventSpec.
     * </pre>
     * ⇒ v2 的 {@code subject_ref} <b>本就没有</b> {@code tenant_id}。
     * ⇒ <b>保留它就是 wire 破坏</b>：下游 binder（BTW）按字段名解析，多一个字段就失配。
     */
    public static AuthorityFact toV2(FactV1 v1) {
        if (v1 == null) {
            throw new IllegalArgumentException("null favorite authority fact");
        }
        AuthorityFact v2 = new AuthorityFact();
        v2.event = v1.event;
        SubjectRef subjectRef = new SubjectRef();
        if (v1.subjectRef != null) {
            // Go: service/favorite/rpc/internal/model/favorite_authority.go:201 —— Issuer 来自 v1 的 AuthorityID，SubjectID 来自 v1 的 SubjectID。
            // ⚠️ v1 的 TenantID 在 Go 侧【没有被带过来】—— 不要"顺手"补上。
            subjectRef.issuer = v1.subjectRef.authorityId;
            subjectRef.subjectId = v1.subjectRef.subjectId;
        }
        v2.subjectRef = subjectRef;
        v2.predecessorEventId = v1.predecessorEventId;
        v2.technicalReceipt = v1.technicalReceipt;
        v2.sourceEventHash = v1.sourceEventHash;
        return v2;
    }
}
