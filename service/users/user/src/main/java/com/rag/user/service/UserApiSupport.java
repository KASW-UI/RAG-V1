package com.rag.user.service;

import com.rag.user.dto.avatar.AvatarHistoryItem;
import com.rag.user.dto.user.UserInfo;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>user 域 HTTP 层共用 helper</b> —— 跨用例共享的工具方法聚合点。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/service/UserApiSupport.java}
 * （逐条照抄）。
 *
 * <p><b>框架职责</b>：Spring {@code @Component} —— 跨 9 个用例 / 拦截器 / Controller 的工具方法聚合点
 * （B 侧取代了原 {@code internal.svc.ServiceContext} 里的 helper）。
 *
 * <p><b>⚠️ 三个归一化方法与 {@code AvatarDomainService} 的同名方法<b>刻意重复</b></b>：
 * B 侧两侧各有 3 个同名方法（api 侧是「镜像」，rpc 侧才是权威实现）⇒ C 侧<b>逐条照抄</b>，
 * <b>不</b>把 api 侧改成「调用 rpc 侧」⇒ 因为 D2 之后 api 侧<b>不再</b>经过 rpc，
 * 两边各自持有自己的一份才是 B 的实际形状。
 */
@Component
public class UserApiSupport {

    /** 与 {@code AvatarDomainService#normalizeAvatarUrl} 同款（TrimSpace）。 */
    public String normalizeAvatarUrl(String value) {
        return value == null ? "" : value.trim();
    }

    /** 与 {@code AvatarDomainService#normalizeContentType} 同款（空 ⇒ {@code "image/png"}）。 */
    public String normalizeContentType(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? "image/png" : trimmed;
    }

    /** 与 {@code AvatarDomainService#normalizeHistoryId} 同款（TrimSpace）。 */
    public String normalizeHistoryId(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * proto → DTO：{@code AvatarHistoryItem}（6 字段）。
     * <pre>
     *   id (string)          → 空字符串 fallback（与 Go 端 nil string 对齐）
     *   sizeBytes (int64)    → long（不会是 0）
     *   isCurrent (bool)     → boolean
     *   createTime (string)  → 直接透传（proto 已是 RFC3339 字符串）
     * </pre>
     */
    public AvatarHistoryItem fromProto(com.rag.proto.user.AvatarHistoryItem proto) {
        if (proto == null) {
            return null;
        }
        return new AvatarHistoryItem(
                proto.getId() == null ? "" : proto.getId(),
                proto.getAvatarUrl() == null ? "" : proto.getAvatarUrl(),
                proto.getContentType() == null ? "" : proto.getContentType(),
                proto.getSizeBytes(),
                proto.getIsCurrent(),
                proto.getCreateTime() == null ? "" : proto.getCreateTime()
        );
    }

    /**
     * proto → DTO：{@code UserInfo}（<b>4 字段，不含 score</b>）。
     *
     * <p>B 侧 D-011：HTTP 层不暴露 score 字段；Go 端 UserInfo 包含但只填 4 字段。
     * ⇒ C 侧 record 的 {@code score} 分量保持原始 {@code long} 的默认值 {@code 0}
     * ⇒ JSON 上仍是 {@code "score":0}（与 B 侧 getter 的 {@code score == null ? 0L : score} 同形）✔。
     */
    public UserInfo fromProto(com.rag.proto.user.UserInfo proto) {
        if (proto == null) {
            return null;
        }
        String email = proto.getEmail();
        Map<String, String> extra = null;
        if (proto.getExtraInfoCount() > 0) {
            extra = new HashMap<>(proto.getExtraInfoMap());
        }
        return new UserInfo(
                proto.getUid(),
                0L,
                proto.getUsername(),
                (email == null || email.isEmpty()) ? null : email,
                extra
        );
    }
}
