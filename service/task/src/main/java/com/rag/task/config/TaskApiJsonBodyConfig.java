package com.rag.task.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.lang.reflect.Type;
import java.util.List;

/**
 * <p><b>让 {@code @RequestBody} 无视 Content-Type 照样读 body</b> —— 对齐 Go 的裸 decoder。</p>
 *
 * <h2>为什么需要它：Go 侧根本没有「媒体类型协商」这回事</h2>
 * <p>Go {@code api/internal/handler/task/gettaskhandler.go:19-20}：
 * <pre>
 *   decoder := json.NewDecoder(r.Body)
 *   decoder.UseNumber()
 *   if err := decoder.Decode(&amp;req); err != nil { ... }
 * </pre>
 * 裸 {@code encoding/json}，<b>没有任何 Content-Type 判据</b>。于是
 * <pre>
 *   POST /taskcenter/v1/task/get
 *     Content-Type: text/plain                （或干脆不写）
 *     body: {"id":"123"}
 *   ⇒ Go: 解析成功 → 200 {"tasks":[...]}
 * </pre>
 *
 * <h2>Spring 默认会把它变成 415（这就是不写本类时的漂移）</h2>
 * {@code @RequestBody} 走 {@code RequestResponseBodyMethodProcessor}，
 * 先在已注册的 {@code HttpMessageConverter} 里找「能读 {@code text/plain} 且目标是 POJO」的：
 * {@code StringHttpMessageConverter} 只支持 {@code String}/{@code byte[]} ⇒ 找不到
 * ⇒ {@code HttpMediaTypeNotSupportedException}（415）。
 * <b>Go 返回 200、RAG 返回 415</b> —— 一条可观察的 L1 差异，且只在「客户端 Content-Type
 * 写错时」出现，恰恰是最容易在联调时踩到的那一类。
 *
 * <h2>🔴 本类<b>不是</b>共享层的 {@code com.rag.common.web.JsonBodyConverter}</h2>
 * <p>共享的那个复刻的是 go-zero <b>{@code httpx.Parse}</b> 的 {@code withJsonBody} 判据
 * （{@code ContentLength > 0 && Content-Type 含 "application/json"}），
 * <b>不满足时丢弃整个 body</b>。那对 like / message / users 域是<b>正确</b>的（它们都走
 * {@code httpx.Parse}），搬到 task-api 就是错的：task-api 走裸 decoder，
 * 任何 Content-Type 都<b>要读</b>。共享实现在判据不满足时返回 {@code null}
 * ⇒ DTO 变成零值 ⇒ {@code id} 缺失 ⇒ 400，而 Go 是 200。
 *
 * <h2>🔴 本类用「同名 bean」让共享层的 auto-config 退让</h2>
 * <p>共享层的 {@code com.rag.common.autoconfigure.JsonBodyConverterAutoConfiguration} 声明：
 * <pre>
 *   @Bean
 *   @ConditionalOnMissingBean(name = "seaJsonBodyConverterConfigurer")
 *   public WebMvcConfigurer seaJsonBodyConverterConfigurer(ObjectMapper objectMapper) { … }
 * </pre>
 * ⇒ 本类的 bean 名<b>刻意就叫</b> {@code seaJsonBodyConverterConfigurer}
 * （见类上的 {@code @Configuration("seaJsonBodyConverterConfigurer")}）
 * ⇒ ⇒ 共享层的那个 bean <b>不会</b>被创建 ⇒ 共享的 {@code JsonBodyConverter} <b>不注册</b>
 * ⇒ ⇒ 本类的转换器独占「读侧」这个位置。
 * <p>⚠️ <b>为什么不用 {@code spring.autoconfigure.exclude}</b>：那会把同一个 auto-config 里的
 * <b>另一个 bean</b>（{@code seaCoercionMapperCustomizer}，即共享层的 Jackson 强转规则）
 * 一起关掉 ⇒ 那是<b>跨域能力</b>，不该由本域的一个 HTTP 需求顺手关掉。
 * <p>⚠️ <b>这条耦合有牙齿</b>：如果共享层哪天改了这个 bean 名，本域会<b>静默</b>地
 * 同时注册两个转换器 ⇒ 共享的那个排在前面 ⇒ 非 JSON body 被丢弃 ⇒ 400 取代 200。
 * ⇒ 由 {@code http/TaskApiWireContractRealTomcatTest#nonJsonContentTypeIsStillParsed}
 * （端点级、真请求）钉住 ⇒ 并由 {@code tools/difftest/task/mutate-task.py} 的
 * <b>M-1 变异</b>（改掉本类的 bean 名 ⇒ 该用例**立刻转红**）证明这条判据**有牙齿**。
 *
 * <h2>⚠️ 必须插在 Boot 的 Jackson 转换器<b>之前</b></h2>
 * <p>{@code extendMessageConverters} 给的是「已注册列表」，Spring 按顺序取第一个
 * {@code canRead} 为真的转换器。排在 Boot 的 Jackson 转换器<b>后面</b>就永远轮不到它，
 * 415 依旧会抛。
 * <p>位置与共享实现一致：插到<b>第一个</b> {@link MappingJackson2HttpMessageConverter} 的下标处，
 * 而不是 {@code add(0, …)}——后者会盖过 {@code ByteArray}/{@code String} 转换器。
 *
 * <h2>⚠️ 只认领<b>读</b>侧，写侧一律交回 Spring 默认转换器</h2>
 * <p>本模块的失败响应是 {@code text/plain}（见 {@code advice/TaskApiExceptionHandler}）。
 * 若转换器的写侧也认领 {@code MediaType.ALL}，它会抢在
 * {@code StringHttpMessageConverter} 之前把裸文案<b>按 JSON 字符串写出</b> ⇒
 * body 从 {@code user_id invalid\n} 变成 {@code "user_id invalid"\n}，
 * <b>状态码与 Content-Type 都对、只有 body 被转义</b>——极具迷惑性的一类回归。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>Jackson 3 → Jackson 2 的 API 面回改</b>（D14）：
 *             B 侧 {@code tools.jackson.databind.json.JsonMapper} ＋
 *             {@code JacksonJsonHttpMessageConverter}（Spring 7 专有）⇒
 *             本仓 {@code com.fasterxml.jackson.databind.ObjectMapper} ＋
 *             {@link MappingJackson2HttpMessageConverter}（spring-web 6.2.16 专有）；</li>
 *         <li><b>共享层 auto-config 的退让机制</b>：B 侧用
 *             {@code @ComponentScan(excludeFilters = …)} 排除共享配置；
 *             本仓共享层是 <b>starter</b>（D3）⇒ 组件扫描排除<b>管不到</b> auto-config
 *             ⇒ 改用「同名 bean 让 {@code @ConditionalOnMissingBean} 落空」；
 *             （共享层那条注释里也写明了 Spring 7 才引入
 *             {@code JacksonJsonHttpMessageConverter}）</li>
 *         <li>包名压平（{@code …/task/api/config} → {@code com.rag.task.config}，D7）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 认领的媒体类型、插入位置、
 *       写侧三条入口的关闭，逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/config/TaskApiJsonBodyConfig.java:68-69（类声明）
 * B 出处：…/config/TaskApiJsonBodyConfig.java:73-80（注入 Boot 的 mapper）
 * B 出处：…/config/TaskApiJsonBodyConfig.java:82-97（extendMessageConverters + 插入位置）
 * B 出处：…/config/TaskApiJsonBodyConfig.java:104-108（转换器：认领 MediaType.ALL）
 * B 出处：…/config/TaskApiJsonBodyConfig.java:116-119（canRead 无条件 true）
 * B 出处：…/config/TaskApiJsonBodyConfig.java:129-137（写侧两个 canWrite 都关掉）
 *       </pre></li>
 * </ol>
 */
@Configuration("seaJsonBodyConverterConfigurer")
public class TaskApiJsonBodyConfig implements WebMvcConfigurer {

    private final ObjectMapper objectMapper;

    /**
     * ⚠️ 注入 Boot 配置好的 {@link ObjectMapper}，<b>不是</b>自己 {@code new} 一个。
     * 保持与本模块其它 JSON 行为走<b>同一个</b> mapper 实例。
     *
     * <p>⚠️ <b>类型必须是 {@code ObjectMapper} 而不是 {@code JsonMapper}</b>：
     * Boot 3.5 的 {@code JacksonAutoConfiguration} 把 bean 的<b>声明类型</b>定为
     * {@code ObjectMapper}（运行时实例才是 {@code JsonMapper}）⇒ Spring 按<b>声明类型</b>
     * 做 predictive type 匹配 ⇒ 按 {@code JsonMapper} 注入<b>匹配不上</b>
     * ⇒ 启动期 {@code NoSuchBeanDefinitionException}。
     *
     * @param objectMapper Boot 装配的 Jackson 2 mapper
     */
    public TaskApiJsonBodyConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        int insertAt = -1;
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof MappingJackson2HttpMessageConverter) {
                insertAt = i;
                break;
            }
        }
        if (insertAt < 0) {
            // Boot 没注册 Jackson 转换器（例如 web 栈被排除）。不擅自插入：
            // 那会造出一个「本模块根本没打算支持 JSON」的转换器。
            return;
        }
        converters.add(insertAt, new ContentTypeAgnosticJsonBodyConverter(objectMapper));
    }

    /**
     * 认领<b>全部</b>媒体类型的<b>读</b>侧 Jackson 转换器。
     *
     * <p>与共享层 {@code JsonBodyConverter} 的唯一区别：它不丢弃任何 body。
     */
    static final class ContentTypeAgnosticJsonBodyConverter extends MappingJackson2HttpMessageConverter {

        ContentTypeAgnosticJsonBodyConverter(ObjectMapper mapper) {
            super(mapper);
            setSupportedMediaTypes(List.of(MediaType.ALL));
        }

        /**
         * 无条件认领所有媒体类型 —— 判据只有一个：<b>这里就是终点</b>，
         * 内容全部交给 {@code super.read} 按 JSON 解析。
         * <p>如果不放开，Spring 会在「选择转换器」阶段就抛 415，{@link #read} 根本不会被调用。
         *
         * <p>⚠️ <b>覆写点必须是 {@code Type} 版三参</b>，不是 {@code ResolvableType} 版三参 ——
         * javap 实测（Spring 6.2.16）本类的继承链是
         * {@code MappingJackson2HttpMessageConverter → AbstractJackson2HttpMessageConverter
         * → AbstractGenericHttpMessageConverter → AbstractHttpMessageConverter}，
         * 而 {@code GenericHttpMessageConverter extends HttpMessageConverter}
         * （<b>不是</b> {@code SmartHttpMessageConverter}）⇒ 链上根本没有
         * {@code canRead(ResolvableType, MediaType)} 这个方法。
         */
        @Override
        public boolean canRead(Type type, Class<?> clazz, MediaType mediaType) {
            return true;
        }

        /** 2 参的读侧版本 —— 一并覆写，理由同 {@link #canRead(Type, Class, MediaType)}。 */
        @Override
        public boolean canRead(Class<?> clazz, MediaType mediaType) {
            return true;
        }

        // ---------- 写侧：两个入口全部关掉 ----------

        /**
         * ⚠️ 三参 {@code canWrite(Type, Class, MediaType)} 是
         * {@code GenericHttpMessageConverter} 的<b>抽象</b>方法，
         * {@code AbstractMessageConverterMethodArgumentResolver} / {@code RequestResponseBodyMethodProcessor}
         * 选转换器时走的正是它；两参版是 {@code HttpMessageConverter} 的<b>兜底</b>。
         * ⇒ 只关两参版会「看着关了其实没关」。
         *
         * <p>（B 侧 task-api 是在 Boot 4 / Spring 7 上覆写
         * {@code canWrite(ResolvableType, Class, MediaType)} —— 那是 {@code SmartHttpMessageConverter}
         * 分支；本仓 Boot 3.5 / Spring 6.2 上 {@code MappingJackson2HttpMessageConverter}
         * <b>不在</b>该分支 ⇒ 覆写点必须换成 {@code Type} 版，否则编译不过。
         * <b>行为等价</b>：两侧都是「写侧一律拒领」。）
         */
        @Override
        public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
            return false;
        }

        @Override
        public boolean canWrite(Class<?> clazz, MediaType mediaType) {
            return false;
        }
    }
}
