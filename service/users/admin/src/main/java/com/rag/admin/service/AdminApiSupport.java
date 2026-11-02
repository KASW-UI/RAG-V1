package com.rag.admin.service;

import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.domain.entity.UserEntity;
import com.rag.admin.dto.admin.AdminInfo;
import com.rag.admin.dto.user.UserInfo;
import com.rag.common.exception.BusinessException;
import com.rag.users.common.ErrCodes;
import com.rag.users.common.ErrMsg;
import org.springframework.stereotype.Component;

import java.util.HashMap;

/**
 * admin 域共用 helper —— 跨 13 个用例 / 拦截器 / 控制器的工具方法聚合点。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/service/AdminApiSupport.java}
 * ⇒ 方法集合对齐 B 侧（{@code parseUid} ＋ 实体→DTO 的转换规则聚合点）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：四处 ——
 *       ① 包名 {@code api.service} → {@code service}（D7）；
 *       ② DTO 从<b>可变 class + setter</b> 改成 <b>immutable record</b>（D5）⇒ 转换改为构造器传参；
 *       ③ {@code ErrorCode} enum → {@code ErrCodes}(int) ＋ {@code ErrMsg.getMsg(code)}（共享层既有形态）；
 *       ④ <b>转换的输入从 proto 变成 entity</b>（D2 域内直连 ⇒ HTTP 面不再经过 proto 中转）。</li>
 *   <li><b>语义还一样吗</b>：一样。④ 的等价性论证：B 侧链路是
 *       {@code entity →toProto→ proto →fromProto→ DTO}；
 *       proto3 对 string/int64/map <b>无 presence</b> ⇒ 每个 null 在序列化<b>之前</b>就被
 *       {@code toProtoAdmin/toProtoUser} 归一成 {@code 0}/{@code ""}/空 map ⇒
 *       <b>「序列化 + 反序列化」这一跳对已归一的值是无损的</b>
 *       ⇒ ⇒ 合并成「entity → DTO」一步后<b>取值逐字段相同</b>。逐字段对照见下两方法注释。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>⚠️ {@code fromProto} 为什么被删</b>：D2 域内直连后，HTTP 面<b>没有 proto 中转</b>
 * ⇒ B 侧的 {@code fromProto(proto)} 在 C 侧<b>零调用方</b> ⇒ 属「D2 合并后的职责归位」，
 * 其<b>转换规则</b>（email 归一、extra_info 空 ⇒ 省略）已<b>逐条落到 {@code fromEntity}</b>。
 * ⇒ 留着一个零调用方的 proto→DTO 入口 = 制造「两条转换路径」的假象 ⇒ 删除并在此留档。
 *
 * <p><b>⚠️ 两处 email 的差别（不要顺手统一）</b>：
 * <ul>
 *   <li>{@code AdminInfo.email}：B 侧<b>无条件 set</b>（空值也输出 {@code ""}）⇒ 本实现同；</li>
 *   <li>{@code UserInfo.email}：B 侧<b>空串时不 set</b>（字段省略）⇒ 本实现同（空串 → {@code null}）。</li>
 * </ul>
 */
@Component
public class AdminApiSupport {

    /**
     * uid 字段从 String 解析。
     *
     * <p>解析失败 / 为空 ⇒ 抛 {@code BusinessException(1017 请求参数错误)}。
     *
     * <p><b>⚠️ B 侧口径</b>：B 侧 {@code parseUid} 抛的也是 {@code BusinessException(1017)}，
     * 但 {@code 1017} 在 B 侧<b>从 HTTP 入口不可达</b> ——
     * 非数字 uid 在 Jackson 反序列化阶段就被拒（400），
     * 缺席 / 显式 null 在必填校验阶段就被拒（400）。
     * ⇒ 本方法保留（防未来新增调用点绕过那两层），但它抛的 1017 <b>在 B 侧没有对应物</b>。
     */
    public static long parseUid(String uidStr) {
        if (uidStr == null || uidStr.isEmpty()) {
            throw new BusinessException(ErrCodes.ERROR_REQUEST_PARAM,
                    ErrMsg.getMsg(ErrCodes.ERROR_REQUEST_PARAM));
        }
        try {
            return Long.parseLong(uidStr.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrCodes.ERROR_REQUEST_PARAM,
                    ErrMsg.getMsg(ErrCodes.ERROR_REQUEST_PARAM));
        }
    }

    /**
     * 实体 {@link AdminEntity} → DTO {@link AdminInfo}。
     *
     * <p><b>B 出处</b>：{@code admin-rpc/.../service/AdminServiceGrpcImpl.java} 的
     * {@code toProtoAdmin}（entity→proto）＋ {@code admin-api/.../service/AdminApiSupport.java}
     * 的 {@code fromProto}（proto→DTO）<b>两步的合并</b>。
     *
     * <p><b>逐字段对照（B 两步 ⇒ 本方法一步）</b>：
     * <table border="1">
     *   <tr><th>DTO 字段</th><th>B：entity→proto</th><th>B：proto→DTO</th><th>本方法</th></tr>
     *   <tr><td>{@code uid}</td><td>{@code null?0}</td><td>{@code String.valueOf}</td>
     *       <td>{@code String.valueOf(uid == null ? 0L : uid)}</td></tr>
     *   <tr><td>{@code username}</td><td>{@code null?""}</td><td>{@code null?"":v}</td>
     *       <td>同</td></tr>
     *   <tr><td>{@code email}</td><td>{@code stringValue(email)} ⇒ {@code ""}</td>
     *       <td><b>无条件 set</b> ⇒ {@code ""}</td><td>{@code stringValue(email)}</td></tr>
     *   <tr><td>{@code extra_info}</td><td>空 map ⇒ count 0</td><td>count 0 ⇒ <b>不 set</b></td>
     *       <td>空/缺 ⇒ {@code null}（字段被 NON_NULL 省略）</td></tr>
     * </table>
     */
    public AdminInfo fromEntity(AdminEntity admin) {
        if (admin == null) {
            return null;
        }
        return new AdminInfo(
                String.valueOf(admin.getUid() == null ? 0L : admin.getUid()),
                admin.getUsername() == null ? "" : admin.getUsername(),
                AdminHelpers.stringValue(admin.getEmail()),
                admin.getExtraInfo() == null || admin.getExtraInfo().isEmpty()
                        ? null
                        : new HashMap<>(admin.getExtraInfo()));
    }

    /**
     * 实体 {@link UserEntity} → DTO {@link UserInfo}。
     *
     * <p>⚠️ admin 的 proto {@code UserInfo} 多一个 {@code score} 字段，但 HTTP DTO <b>不暴露</b> score
     * （与 B 侧 1:1 保留）。
     *
     * <p><b>逐字段对照</b>：{@code uid} = {@code String.valueOf(null?0)}；{@code status} = {@code null?0}；
     * {@code username} = {@code null?""}；<b>{@code email} = 空串 ⇒ {@code null}（字段省略）</b>；
     * {@code extra_info} = 空/缺 ⇒ {@code null}。
     */
    public UserInfo fromEntity(UserEntity user) {
        if (user == null) {
            return null;
        }
        return new UserInfo(
                String.valueOf(user.getUid() == null ? 0L : user.getUid()),
                user.getStatus() == null ? 0L : user.getStatus(),
                user.getUsername() == null ? "" : user.getUsername(),
                user.getEmail() == null || user.getEmail().isEmpty() ? null : user.getEmail(),
                user.getExtraInfo() == null || user.getExtraInfo().isEmpty()
                        ? null
                        : new HashMap<>(user.getExtraInfo()));
    }
}
