package com.rag.article.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * <p><b>职责</b>：🔴 {@code article.updated_at} 的自动推进 —— MyBatis-Plus 侧对 Go
 * {@code gorm:"autoUpdateTime"} 的复刻。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/config/ArticleMetaObjectHandler.java}
 * （类声明 {@code :81-82} · {@code updateFill} {@code :124-131} · {@code insertFill} {@code :133-142}）
 * ⇒ <b>方法体逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅包名</b>（{@code com.rag.article.article.rpc.config} →
 *       {@code com.rag.article.config}，D7 包压平）＋ <b>本段 javadoc</b>
 *       ⇒ <b>填充逻辑逐字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>填充字段名仍是 {@code "updatedAt"}（MyBatis-Plus 的<b>属性名</b>，不是列名 {@code updated_at}）；</li>
 *         <li>仍是<b>无条件覆盖</b>（不是 {@code strictUpdateFill}）⇒ 逐条论证见下；</li>
 *         <li>时区仍是 <b>UTC</b>（不是 JVM 默认时区）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>Go 出处（声明点 + 消费点）</h2>
 * <ul>
 *   <li><b>声明点</b>：{@code service/article/rpc/internal/model/article.go:31}
 *       {@code UpdatedAt time.Time `gorm:"autoUpdateTime"`}；</li>
 *   <li><b>消费点</b>（GORM 真正执行它的那几行，不是 tag 本身）：
 *       {@code gorm@v1.31.1/callbacks/update.go:238-241}（{@code Updates(map)} 路径）
 *       与 {@code :276-281}（{@code Updates(struct)} 路径）：
 *       <pre>{@code
 *       if !stmt.SkipHooks && stmt.Schema != nil {
 *           for _, dbName := range stmt.Schema.DBNames {
 *               field := stmt.Schema.LookUpField(dbName)
 *               if field.AutoUpdateTime > 0 && value[field.Name] == nil && value[field.DBName] == nil {
 *                   if v, ok := selectColumns[field.DBName]; (ok && v) || !ok {
 *                       now := stmt.DB.NowFunc()
 *                       assignValue(field, now)
 *                       set = append(set, clause.Assignment{Column: …, Value: now})
 *       }}}</pre></li>
 * </ul>
 *
 * <h2>语义等价论证（GORM 那三个判据逐条对上）</h2>
 * <ol>
 *   <li><b>{@code !stmt.SkipHooks}</b> ⇒ {@code UpdateColumn}（它置 {@code SkipHooks=true}）
 *       <b>不推进</b>时间戳。Java 侧对应：{@code ArticleMapper#incrViewCount} 与
 *       {@code ArticlePublicMapper} 的同款方法都是<b>原生 {@code @Update} SQL</b>，
 *       完全绕开 MyBatis-Plus ⇒ <b>本 handler 不会在它们上面触发</b>。✅ 已对齐；
 *       对照 Go 的两处 {@code UpdateColumn}（{@code model/article.go:123}、
 *       {@code model/article_public.go:159}）。</li>
 *   <li><b>{@code value[field] == nil}</b> ⇒ 调用方显式赋了值就<b>尊重调用方</b>。
 *       Java 侧本 handler 是<b>无条件覆盖</b>，看似更激进，但
 *       {@code grep -rn '"updated_at"|"UpdatedAt"' service/article --include=*.go}
 *       实测<b>零命中</b>（排除 model 声明与测试）⇒ Go 侧根本不存在显式赋值路径，
 *       <b>无条件覆盖不产生任何可观察差异</b>。✅ 等价（依据：实测 grep，不是推断）。</li>
 *   <li><b>{@code selectColumns} 未显式指定该列</b> ⇒ 用 {@code Updates} 显式点名
 *       {@code updated_at} 时不覆盖。Java 侧本模块无此路径（同样由上述 grep 佐证）。</li>
 * </ol>
 *
 * <h2>为什么必须走 handler，而不是逐个调用点 setUpdatedAt</h2>
 * <p>本模块对 {@code article} 表的写入<b>全部</b>经由 {@code articleMapper.updateById(entity)}
 * 一个收口点。逐点改是易漏的散点，漏掉第 N+1 处（新调用点）不会有任何编译或测试信号。
 *
 * <h2>🔴 修掉的是什么（静默失效，不是报错）</h2>
 * <p>{@code ArticleEntity.updatedAt} 必须带 {@code @TableField(fill = INSERT_UPDATE)}，
 * 而 {@code updateById} 的默认 {@code updateStrategy = NOT_NULL} 会把实体里那个
 * <b>从库里读出来的陈旧值原样写回</b> ⇒ {@code updated_at} <b>永远不推进</b>。
 * <p>更糟的是<b>只加 {@code @TableField(fill = …)} 而没有本类</b>，{@code fill} 同样是<b>静默空转</b>
 * —— 不报错、不告警，字段照旧不推进。两者必须成对出现，这正是 AGENTS.md §3.3.3
 * 「注解存在 ≠ 机制生效」的典型形态。
 *
 * <h2>时区：用 UTC 而不是 JVM 默认时区</h2>
 * <p>Go 的 {@code time.Now()} 是本地时间，本实现用 {@code OffsetDateTime.now(ZoneOffset.UTC)}。
 * 二者<b>不是分歧而是收紧</b>：列类型是 {@code TIMESTAMPTZ}，存的是<b>绝对时刻</b>，
 * 读回来时两种写法给出同一个瞬间；但若 JVM 运行在非 UTC 时区而驱动又把本地时刻当字面量写入，
 * 就会整体偏移一个时区差。显式 UTC 让结果与 JVM 时区无关。
 */
@Component
public class ArticleMetaObjectHandler implements MetaObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(ArticleMetaObjectHandler.class);

    /**
     * 🔴 <b>用无条件覆盖而不是 {@code strictUpdateFill}，这是本类最重要的一行。</b>
     *
     * <p><b>为什么 {@code strictUpdateFill} 在这里是错的</b>
     * （依据：mybatis-plus-core 3.5.17 {@code MetaObjectHandler.strictFillStrategy} 消费点）：
     * <pre>{@code
     * default MetaObjectHandler strictFillStrategy(MetaObject metaObject, String fieldName, Supplier<?> fieldVal) {
     *     if (metaObject.getValue(fieldName) == null) {      // ← 只在当前值为 null 时才填
     *         Object obj = fieldVal.get();
     *         if (Objects.nonNull(obj)) { metaObject.setValue(fieldName, obj); }
     *     }
     *     return this;
     * }}</pre>
     *
     * <p>而本域的写路径全是<b>读-改-写</b>：实体由 {@code selectById} /
     * {@code selectByIdForUpdate} 从库里读出，{@code updatedAt} <b>带着上一次的陈旧值</b>，
     * 永远<b>不是 null</b> ⇒ {@code strictUpdateFill} 永远跳过 ⇒ {@code updated_at}
     * 永远停在陈旧时刻。<b>这不是配置问题，是 strict 语义与本域写入形态不兼容。</b>
     *
     * <p><b>为什么无条件覆盖反而更贴近 Go</b>：
     * GORM 那一行的判据是 {@code value[field.Name] == nil}，含义是
     * 「<b>本次调用有没有赋值</b>」。MP 的 {@code == null} 含义是
     * 「<b>字段现在是不是空</b>」—— 二者<b>不是同一件事</b>：
     * Java 侧那个陈旧值<b>不是调用方的意图</b>，是上一轮写下的残留。
     *
     * <p>⚠️ <b>不要改回 {@code strictUpdateFill}</b>：改回去会让「先读出实体再更新」这条
     * 本域日常形态的用例变红。
     *
     * <p><b>用 {@code setFieldValByName} 而不是裸 {@code metaObject.setValue}</b>：
     * 前者先做 {@code hasSetter} 与非空检查，字段名写错时<b>静默跳过</b>而不是抛反射异常。
     *
     * <p><b>B 出处</b>：{@code .../config/ArticleMetaObjectHandler.java:124-131}。
     *
     * @param metaObject 待填充的实体元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        // MetaObjectHandler.setFieldValByName 的默认实现（mybatis-plus-core 3.5.17 :101-104）
        // 恰好就是「无条件覆盖 + 非空 + hasSetter 守卫」，正是本处需要的语义，无需覆写：
        //   if (Objects.nonNull(fieldVal) && metaObject.hasSetter(fieldName))
        //       metaObject.setValue(fieldName, fieldVal);
        setFieldValByName("updatedAt", OffsetDateTime.now(ZoneOffset.UTC), metaObject);
    }

    /**
     * 插入填充 —— <b>刻意空实现</b>。
     *
     * <p>理由见上方 javadoc：Go 的 insert 路径由 GORM 的
     * {@code autoCreateTime/autoUpdateTime} 各自处理，Java 侧建行已显式写入两个时间列，
     * 此处再填会覆盖调用方的显式赋值。
     *
     * <p><b>B 出处</b>：{@code .../config/ArticleMetaObjectHandler.java:133-142}。
     *
     * @param metaObject 待填充的实体元对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        if (log.isTraceEnabled()) {
            log.trace("insertFill invoked for {} —— 本实现刻意不填任何字段",
                    metaObject.getOriginalObject().getClass().getSimpleName());
        }
    }
}
