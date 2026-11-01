package com.rag.common.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * jsonb 列 ↔ String 的 TypeHandler —— 对应 Go 端 PG 的 {@code jsonb} 列。
 *
 * <p><b>为什么用 {@link String} 而不是 POJO</b>：本域有两个 jsonb 用途都要求
 * 「以字符串原样存取」——
 * <ul>
 *   <li>{@code comment_content.meta}：Go 的 {@code normalizeCommentMeta}（comment.go:222-240）
 *       契约是「输入已是合法 JSON 就<b>原样保留</b>」，再被 GetComment 直接塞进 wire 上
 *       {@code CommentItem.meta} 这个 string 字段；</li>
 *   <li>{@code comment_domain_fact_outbox.payload / delivery_envelope}：
 *       {@code validCommentDelivery} 要对 payload 列与信封内嵌 payload 做
 *       <b>规范化哈希比对</b>（fact_delivery.go:42-47）—— 只有同一份字节才能算出同一个哈希。</li>
 * </ul>
 *
 * <p><b>为什么不用 MyBatis-Plus 自带的 JacksonTypeHandler</b>：它按目标 Java 类型推断 PG 列类型，
 * 会把 {@code json} 与 {@code jsonb} 混为一谈。本域三处都是 jsonb，显式指定才不会被
 * 灰度期的类型推断坑到。
 *
 * <p><b>写入前不校验 JSON 合法性</b>：Go 侧也不校验（payload 由 {@code json.Marshal} 产生，一定合法）。
 * 若上层传了非法 JSON，让 PG 在插入时报错，而不是在这里静默吞掉。
 *
 * <p><b>读回用 {@code getString}</b>：PG 对 jsonb 返回 {@code PGobject}，直接取出文本形式。
 * ⚠️ 注意 jsonb 列<b>会规范化空白与键序</b>（PG 内部存储语义），因此读回的文本与写入的原文
 * 可能不同 —— 这正是上面「规范化哈希比对」必须走 {@code Wire.canonicalHash}（JCS）而不是
 * 字节比较的原因。
 */
@MappedTypes(String.class)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    /** PG 的 jsonb 类型名。 *
 * <p><b>⚠️ N:1 合并（映射表 §1.7 第 192 行 · 决策锁 L-8「7 份副本合 1」）</b>：
 * RAG 侧共有 **5 份** {@code JsonbTypeHandler}，但它们<b>并非同一份</b> ⇒ 逐份核对结论：
 * <ul>
 *   <li><b>comment / favorite / like 三份</b>：剥掉 package / import / 注释后<b>代码体逐字一致</b>
 *       ⇒ 都是标准的 {@code @MappedTypes(String.class) public class JsonbTypeHandler extends BaseTypeHandler<String>}；</li>
 *   <li><b>article 那份<b>不是</b> TypeHandler</b>：它是个<b>工具类</b>
 *       （{@code final class JsonbTypeHandler} + 静态方法 + 内嵌
 *       {@code StringListTypeHandler} / {@code StringMapTypeHandler}）
 *       ⇒ ⇒ 它<b>不</b>是本类的副本 ⇒ <b>不并入</b>（其落点属 article 域，见各域映射表）。</li>
 * </ul>
 * ⇒ 本类取<b>三域一致的标准版</b>（comment 版）⇒ 行为等价，且副本从 3 份收敛为 1 份。
 *
 * <p><b>抄写基准</b>：RAG {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/mapper/JsonbTypeHandler.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类 Jackson 写法在 RAG 侧<b>本就是 Jackson 2</b> ⇒ 零回改）。
 */
    public static final String PG_JSONB = "jsonb";

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        // PGobject 让 pgjdbc 走 PGobject 路径，把类型显式声明为 jsonb，
        // 否则按 VARCHAR 绑定，jsonb 列会因类型不匹配报错。
        PGobject jsonb = new PGobject();
        jsonb.setType(PG_JSONB);
        jsonb.setValue(parameter);
        ps.setObject(i, jsonb);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}