package com.rag.like.dto.count;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>某用户总获赞数查询请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetUserTotalLikeReq.java:20-38}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type GetUserTotalLikeReq struct {
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/getusertotallike}（<b>需 uid</b>）。
 *
 * <h3>⚠️ 零字段 DTO ⇒ 没有任何必填校验</h3>
 * <p>这是 like 域<b>唯一</b>一个「空 body / 任意 body 都能解析成功」的请求类型。
 *
 * <p>⚠️ <b>它仍然保留 {@code @RequestBody(required = false)}</b>：
 * B 侧的 handler <b>照样调 {@code httpx.Parse}</b>（{@code getusertotallikehandler.go:17-20}），
 * 只是这个 struct 没有字段所以不会失败。为了让 Java 端能复现
 * 「畸形 JSON 仍会被拒」，不能让 Spring 跳过 body 读取。
 *
 * <p>⚠️ <b>零字段 record 的 Jackson 绑定</b>：Jackson 对<b>无分量</b>的 record 会走
 * 「零参构造器」⇒ <b>任何</b> JSON 对象都能绑成它 ⇒ 与 B 侧的零值 struct 同形。
 */
public record GetUserTotalLikeReq() {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 零参。
     *
     * <p>⚠️ 显式写成静态工厂（而非只靠规范构造器）⇒ 与本域其它 DTO 的
     * 「{@code @JsonCreator} 工厂 = 唯一入口」约定一致（A9 判据只看有无 setter）。
     */
    @JsonCreator
    public static GetUserTotalLikeReq fromJson() {
        return new GetUserTotalLikeReq();
    }
}
