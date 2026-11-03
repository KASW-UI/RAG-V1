package com.rag.like.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.rag.common.web.Coercion;
import com.rag.like.interceptor.LikeJwtAuthInterceptor;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>like 域的 MVC 装配</b> —— 对应 B 侧
 * {@code like-api/src/main/java/com/rag/like/like/api/config/WebMvcConfig.java}
 * ＋ {@code config/LikeGoStyleCoercion.java}（2 个类 ⇒ C 侧 1 个类）。
 *
 * <p><b>抄写基准</b>：B 侧两文件（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>Jackson 3 → Jackson 2</b>（D14 工具链）：B 侧的
 *           {@code tools.jackson.databind.*} 全部改为 {@code com.fasterxml.jackson.databind.*}。</li>
 *       <li><b>改走 {@code postConfigurer} 路线</b>（Boot 3.5 约束）：
 *           Spring 6.2 的 {@code Jackson2ObjectMapperBuilder} <b>没有</b> coercion API
 *           （javap 实测：只有 {@code featuresToDisable} / {@code featuresToEnable} /
 *           {@code modulesToInstall} 等，<b>无</b> {@code withCoercionConfig}）
 *           ⇒ <b>builder 路线在 Boot 3.5 上走不通</b>；
 *           但 {@code Jackson2ObjectMapperBuilder#postConfigurer(Consumer<ObjectMapper>)} <b>存在</b>
 *           ⇒ ⇒ 在 builder 构建完之后对 mapper 施加规则。</li>
 *       <li>⚠️ <b>不替换 Boot 的 mapper</b>：自己 new 一个 mapper 顶掉 ⇒ 各域
 *           {@code spring.jackson.*}（时区 / 命名策略 / 序列化开关）的绑定全部失效
 *           ⇒ 那是把「加两条规则」变成「换掉整个 mapper」⇒ 是行为变更，不是等价改造。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。两条域内补强（{@code Integer ← String = Fail}、
 *       {@code Float ← String = Fail}）+ 共享层的两条规则（{@code Textual ← 数字/布尔 = Fail}、
 *       {@code ACCEPT_FLOAT_AS_INT = false}）+ {@code FAIL_ON_TRAILING_TOKENS} 关闭，逐条一致。</li>
 *   <li><b>B 出处</b>：见上两个文件。</li>
 * </ol>
 *
 * <h2>⚠️ GAP-7：like 域的 2 条 Jackson 强转补强<b>不能丢</b></h2>
 * <p>共享层的 {@link Coercion} 只收了<b>通用</b>的两条规则；
 * B 侧 like 域在其之上<b>又叠加了 2 条</b>（{@code LikeGoStyleCoercion}）：
 * <ol>
 *   <li>{@code LogicalType.Integer ← String = Fail}
 *       —— 否则 {@code "action_type":"1"} 这类<b>字符串数字</b>会被静默读成 1
 *       （B 侧 go-zero 的 {@code strconv.ParseInt} 路径对字符串数字是<b>接受</b>的，
 *       但 B 侧 like 域明确把它 Fail 掉 ⇒ 照抄）；</li>
 *   <li>{@code LogicalType.Float ← String = Fail}
 *       —— {@code "weight":"0.5"} 同理。</li>
 * </ol>
 * ⇒ 丢了 ⇒ {@code author_id} 的字符串语义与字段校验时机漂移（映射表 §7.3 GAP-7）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String LIKE_PREFIX = "/like/v1/**";

    private final LikeJwtAuthInterceptor likeJwtAuthInterceptor;

    public WebMvcConfig(LikeJwtAuthInterceptor likeJwtAuthInterceptor) {
        this.likeJwtAuthInterceptor = likeJwtAuthInterceptor;
    }

    /**
     * 域内 Jackson 强转补强。
     *
     * <p>⚠️ <b>bean 名不能叫 {@code seaCoercionMapperCustomizer}</b>（那是共享层的名字）：
     * 同名 bean 会让其中一个被覆盖 ⇒ 其中一个域的补强静默失效
     * （<b>配置面看不出异常</b>，正是 AGENTS.md §3.3 点名的那类静默失效）。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer likeGoStyleCoercionCustomizer() {
        // B 侧 LikeGoStyleCoercion.applyTo(builder) 的逐条等价物。
        // ⚠️ Boot 3.5 上 builder 没有 withCoercionConfig ⇒ 走 postConfigurer 路线
        //    （理由见类注释「L4 差异」）。
        return builder -> builder.postConfigurer(mapper -> {
            // ① 共享层的两条通用规则（Textual ← 数字/布尔 = Fail；ACCEPT_FLOAT_AS_INT = false）
            Coercion.applyTo(mapper);
            // ② B 侧 LikeGoStyleCoercion 的第 1 条：Integer ← String = Fail
            mapper.coercionConfigFor(LogicalType.Integer)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            // ③ B 侧 LikeGoStyleCoercion 的第 2 条：Float ← String = Fail
            mapper.coercionConfigFor(LogicalType.Float)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            // ④ B 侧 LikeGoStyleCoercion 的第 3 条：关闭「尾部多余 token」检查
            //    （Go 的 encoding/json 解码完一个值后<b>忽略</b>尾随内容 ⇒ 对齐 Go）
            mapper.disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(likeJwtAuthInterceptor)
                .addPathPatterns(LIKE_PREFIX);
    }
}
