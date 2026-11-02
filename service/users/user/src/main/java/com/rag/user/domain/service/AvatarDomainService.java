package com.rag.user.domain.service;

import com.rag.proto.user.AvatarHistoryItem;
import com.rag.user.domain.entity.UserAvatarHistoryEntity;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * <p><b>头像域服务</b> —— 头像字段归一化 ＋ proto 转换工具。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/service/AvatarDomainService.java}
 * （逐条照抄；仅 proto 包名由 {@code com.rag.users.user.proto} 改为 {@code com.rag.proto.user} —— D7 集中化）。
 *
 * <p><b>语义等价论证</b>：
 * <ul>
 *   <li>{@code fromModel} 的 6 个字段逐条对应 Go 端 {@code avatarHistoryItemFromModel}；</li>
 *   <li>{@code create_time} 用 String 透传（proto 字段类型就是 string）⇒ <b>不做 Instant.parse</b>；</li>
 *   <li>三个归一化方法逐条对应 Go 的 {@code strings.TrimSpace} 与
 *       {@code model.NormalizeAvatarContentType}（空 ⇒ {@code image/png}）。</li>
 * </ul>
 *
 * <p><b>框架职责</b>：Spring {@code @Service} —— 跨多个头像用例共享的工具方法聚合点
 * （B 侧是静态工具类 {@code internal.logic.AvatarHelpers}）。
 */
@Service
public class AvatarDomainService {

    /**
     * 对齐 Go {@code time.RFC3339} 的渲染格式。
     *
     * <p><b>为什么不能用 {@code Instant.toString()}</b>（B 侧 DRIFT-006 修复）：
     * Go 的 {@code Format(time.RFC3339)} 布局是 {@code "2006-01-02T15:04:05Z07:00"}，
     * <b>不含 {@code .999} 段</b>，而 {@code Time.Format} 只输出布局指定的字段
     * ⇒ <b>纳秒被整段丢弃</b>，输出恒为秒精度。
     * 而 {@code Instant.toString()} 在纳秒非零时<b>必带</b>小数部分
     * ⇒ 同一份数据两边渲染出的字符串必然不同 ⇒ 该字符串一路透传到 HTTP body 的
     * {@code create_time}，是<b>纯客户端可见</b>的差异。
     */
    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /**
     * 对应 Go {@code avatarHistoryItemFromModel}：
     * <pre>
     *   Id          ← item.HistoryID
     *   AvatarUrl   ← item.AvatarURL
     *   ContentType ← item.ContentType
     *   SizeBytes   ← item.SizeBytes
     *   IsCurrent   ← item.IsCurrent
     *   CreateTime  ← item.CreateTime.Format(time.RFC3339)（非零时）
     * </pre>
     */
    public AvatarHistoryItem fromModel(UserAvatarHistoryEntity h) {
        if (h == null) {
            return null;
        }
        AvatarHistoryItem.Builder b = AvatarHistoryItem.newBuilder()
                .setId(h.getHistoryId() == null ? "" : h.getHistoryId())
                .setAvatarUrl(h.getAvatarUrl() == null ? "" : h.getAvatarUrl())
                .setContentType(h.getContentType() == null ? "" : h.getContentType())
                .setSizeBytes(h.getSizeBytes() == null ? 0L : h.getSizeBytes())
                .setIsCurrent(Boolean.TRUE.equals(h.getIsCurrent()));
        OffsetDateTime ct = h.getCreateTime();
        if (ct != null) {
            // 🔴 渲染必须落在**进程本地时区**（Go 的 time.Local），而不是 pgjdbc 给的 UTC。
            //
            // ⚠️ 实测（pgjdbc 42.7.13，2026-10-23，独立 JDBC 探针）：
            //     session TZ=UTC          → getObject(OffsetDateTime) = 2026-01-01T08:00Z  offset=Z
            //     session TZ=Asia/Shanghai → getObject(OffsetDateTime) = 2026-01-01T08:00Z  offset=Z
            //   ⇒ pgjdbc 把 timestamptz 取成 OffsetDateTime 时**恒用 UTC**，
            //     与会话时区、与 JVM 默认时区都无关。
            //   ⇒ 而 Go 侧（pgx v5.10.0 pgtype/timestamptz.go:270-276，ScanLocation 默认 nil）
            //     落回 **time.Local** ⇒ Format(RFC3339) 打印的是**进程本地时区**的偏移量。
            //   ⇒ 两侧要同形，必须由 RAG 侧显式换算到 JVM 本地时区后再格式化。
            //
            // ⚠️ atZoneSameInstant（不是 withOffsetSameInstant(UTC)）：
            //   前者换的是**时区**（同一时刻、不同的墙上表示），
            //   后者会把时刻标成 UTC —— 那正是 B 侧修复前的错误（实测差 8 小时）。
            OffsetDateTime local = ct.atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime();
            // 布局在偏移为 0 时输出 "Z"、否则输出 "+08:00"，与 Go 的 "Z07:00" 逐字符同构。
            b.setCreateTime(RFC3339.format(local));
        }
        return b.build();
    }

    /** 对应 Go 端 {@code strings.TrimSpace(value)}。 */
    public String normalizeAvatarUrl(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 对应 Go 端 {@code model.NormalizeAvatarContentType}：
     * <pre>
     *   trimmed = strings.TrimSpace(value)
     *   if trimmed == "" { return "image/png" }   // D-012
     *   return trimmed
     * </pre>
     */
    public String normalizeContentType(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? "image/png" : trimmed;
    }

    /** 对应 Go 端 {@code strings.TrimSpace}。 */
    public String normalizeHistoryId(String value) {
        return value == null ? "" : value.trim();
    }
}
