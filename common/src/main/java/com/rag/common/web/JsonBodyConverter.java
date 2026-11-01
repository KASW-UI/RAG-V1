package com.rag.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;

/**
 * 复刻 go-zero {@code rest/httpx/requests.go:117-124,153-155} 的
 * <b>「什么时候才读请求体」</b>判据 —— 这是 415 漂移的真正根因。
 *
 * <h2>Go 侧的判据（逐字）</h2>
 *
 * <pre>
 *   func ParseJsonBody(r *http.Request, v any) error {
 *       if withJsonBody(r) {
 *           reader := io.LimitReader(r.Body, maxBodyLen)
 *           return mapping.UnmarshalJsonReader(reader, v)   // 真读 body
 *       }
 *       return mapping.UnmarshalJsonMap(nil, v)             // ⭐ 完全不看 body
 *   }
 *
 *   func withJsonBody(r *http.Request) bool {
 *       return r.ContentLength &gt; 0 &amp;&amp; strings.Contains(r.Header.Get("Content-Type"), "application/json")
 *   }
 * </pre>
 *
 * <h2>⚠️ 最容易被误解的一点：Go 不是「宽松地接受非 JSON」，而是<b>无视整个请求体</b></h2>
 *
 * <p>判据不满足时 Go 走的是「用空 map 解析」，<b>不是</b>「勉强把 body 当 JSON 读」。
 * 可观察后果：
 * <pre>
 *   POST /like/v1/getlikecount
 *     Content-Type: text/plain
 *     body: {"target_type":"article","target_ids":["1"]}   ← 完全合法
 *   ⇒ Go: body 被丢弃 ⇒ target_ids 缺失 ⇒ 解析失败 ⇒ 200 + {"code":5001}
 *   ⇒ 「放宽 content type 就能兼容」是错的：那会把 Go 明确拒绝的请求变成成功。
 * </pre>
 *
 * <h2>为什么不能简单地「让 Spring 也吃非 JSON」</h2>
 *
 * <p>那样得到的是「解析 body」，与 Go 的「丢弃 body」在<b>必填字段齐全</b>时结果相反。
 * ⇒ 本类在<b>判据不满足</b>时直接返回 {@code null}，
 * 让上层 {@code GoStyleRequest.normalize(null, factory)} 产出零值 DTO 再跑必填校验 ——
 * 那正是 {@code UnmarshalJsonMap(nil, v)} 的等价物（实测：两侧的最终 code/msg 一致）。
 *
 * <h2>⚠️ 判据里的三个细节，每一个都会造成可观察差异</h2>
 *
 * <ol>
 *   <li><b>{@code strings.Contains(ct, "application/json")} 是字面子串匹配</b>，
 *       <b>不是</b> Spring 的媒体类型兼容判定。⇒ {@code application/vnd.api+json}
 *       在 Go 侧<b>不</b>包含该子串 ⇒ <b>body 被丢弃</b>；
 *       而 Spring 默认把它当 JSON 正常解析 ⇒ <b>这是一条独立于 415 的漂移</b>。</li>
 *   <li><b>{@code ContentLength > 0}</b>：chunked 请求在 Go 侧 {@code ContentLength == -1}
 *       ⇒ 判据为假 ⇒ <b>body 被丢弃</b>。与 MaxBytes 那条「chunked 绕过限制」同源，
 *       是同一个「Go 用 -1 表示未知」的连带后果。</li>
 *   <li><b>Content-Type 缺失</b>：同样判为假。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么必须复用 Boot 的 mapper Bean</h2>
 *
 * <p>各域 api 模块通过 {@code JsonMapperBuilderCustomizer}
 * 建立了一批<b>已用探针双侧对拍过</b>的语义：
 * {@code options=1|2|3|4}、{@code ,string} 的双向接受等（DRIFT-LIKEAPI-001/002、DRIFT-MSGAPI-001/002）。
 * 若这里用转换器的<b>无参构造器</b>，
 * 就会造出第二个<b>不带这些配置</b>的 mapper ⇒ 把已验证过的语义静默退回默认值。
 * ⇒ 只接受注入进来的 mapper（由 Boot 用同一个 builder 构建），
 * <b>不提供</b>无参构造器：让「忘注入」变成编译错误，而不是一条隐蔽的行为回归。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 idiomatization：Jackson 3 + Spring 7 → Jackson 2 + Spring 6.2 的 5 处回改</b>
 * ══════════════════════════════════════════════════════════════════════
 * <table border="1">
 *   <tr><th>项</th><th>RAG（Jackson 3 / Spring 7）</th><th>本仓（Jackson 2 / Spring 6.2）</th></tr>
 *   <tr><td>基类</td>
 *       <td>{@code org.springframework.http.converter.json.JacksonJsonHttpMessageConverter}</td>
 *       <td>{@code org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter}
 *           （Spring 6.2 <b>没有</b> {@code JacksonJsonHttpMessageConverter} 这个类 —— javap 实测）</td></tr>
 *   <tr><td>读方法签名</td>
 *       <td>{@code read(ResolvableType, HttpInputMessage, Map)}</td>
 *       <td>{@code read(Type, Class&lt;?&gt;, HttpInputMessage)}
 *           （Spring 7 才引入 {@code ResolvableType} 版；6.2 仍是 {@code (Type, Class, HttpInputMessage)}）</td></tr>
 *   <tr><td>mapper 类型</td>
 *       <td>{@code tools.jackson.databind.json.JsonMapper}</td>
 *       <td>{@code com.fasterxml.jackson.databind.ObjectMapper}
 *           ⚠️ <b>必须用 {@code ObjectMapper} 而不是 {@code JsonMapper}</b>：Boot 3.5 的
 *           {@code JacksonAutoConfiguration} 把 bean 的<b>声明类型</b>定为 {@code ObjectMapper}
 *           （运行时实例才是 {@code JsonMapper}）⇒ Spring 按<b>声明类型</b>做 predictive type 匹配
 *           ⇒ 按 {@code JsonMapper} 注入<b>匹配不上</b> ⇒ 启动期
 *           {@code NoSuchBeanDefinitionException}。</td></tr>
 *   <tr><td>{@code canWrite}</td>
 *       <td>覆写 {@code (ResolvableType, Class, MediaType)} + 转发用的 {@code (Class, MediaType)}</td>
 *       <td><b>同形</b>：Spring 6.2 的 {@code SmartHttpMessageConverter} 里
 *           {@code canWrite(ResolvableType, Class, MediaType)} 仍是<b>抽象</b>方法、
 *           两参版仍是 {@code default} 委托 ⇒ <b>两条都必须覆写</b>（RAG 2026-10-22 实测踩过：
 *           只覆写两参版 ⇒ admin-api 仍回归 4 条）</td></tr>
 *   <tr><td>包名</td>
 *       <td>{@code tools.jackson.databind.json.JsonMapper}</td>
 *       <td>{@code com.fasterxml.jackson.databind.ObjectMapper}</td></tr>
 * </table>
 *
 * <p><b>⚠️ 判据只有一份（X-9 教训）</b>：RAG 侧这段判据是本类的静态方法，
 * 但 RAG 仓里曾存在 **3 份「复制品」**（favorite / hot / article 各抄了一份
 * {@code contentType.contains("application/json")}）⇒ 它们当时取的是原始头值、判定与 Go 一致，
 * 但<b>没有调用本方法</b> ⇒ 哪天有人把其中一份改成「用 MediaType 解析」，
 * RAG 就会<b>内部自相矛盾</b>（闸门说不读、解析器说要读）。
 * ⇒ 本仓把判据收敛到 <b>{@link JsonBodyConverterConfig}</b> 一份
 * （§7「职责对不上 #5」的建议形态：配置类只保留配置常量与判据，注册动作交 auto-config），
 * 本类<b>只调用、不复制</b> ⇒ 域侧代码一律调 {@code JsonBodyConverterConfig.goReadsBody(...)}。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStyleJsonBodyConverter.java}
 * 整段抄（L2 · D13）＋ <b>Jackson 3 / Spring 7 → Jackson 2 / Spring 6.2 的 5 处回改</b>（L4）。
 */
public class JsonBodyConverter extends AbstractJackson2HttpMessageConverter {

    /**
     * Go {@code rest/httpx/requests.go:21} 的 {@code maxBodyLen = 8 << 20}（8 MiB）。
     *
     * <h3>⚠️ 为什么它<b>不可配置</b></h3>
     * <p>Go 侧它是<b>框架常量</b>（{@code const} 块，{@code requests.go:17-22}），
     * 既不是 {@code RestConf} 的字段、也不在任何 yaml / struct tag 里
     * ⇒ 做成 RAG 侧的 property 等于<b>凭空多出 Go 没有的开关</b>
     * ⇒ 同 {@code maxMemory} 的处理方式：照抄，不给开关。
     *
     * <h3>⚠️ 它为什么必须存在（2026-10-25 第十六轮 · 双侧探针实测）</h3>
     * <pre>
     * rest/httpx/requests.go:117-124
     *     func ParseJsonBody(r *http.Request, v any) error {
     *         if withJsonBody(r) {
     *             reader := io.LimitReader(r.Body, maxBodyLen)   // ← 静默截断在 8 MiB
     *             return mapping.UnmarshalJsonReader(reader, v)
     *         }
     *         return mapping.UnmarshalJsonMap(nil, v)
     *     }
     * </pre>
     * ⇒ Go 对 JSON body 有两道闸：<b>① 只读前 8 MiB</b> · <b>② 命中判据才读</b>。
     * ⇒ RAG 侧原先只复刻了 ② ⇒ <b>① 缺失</b> ⇒ 一个 &gt; 8 MiB 的 JSON body
     * 在 Go 侧被<b>截断</b>（⇒ 解析器拿到半个文档 ⇒ 报错），
     * 在 RAG 侧却被<b>整个读进堆里</b>并成功解析 ⇒ 两侧行为相反。
     * ⇒ 实测证据：{@code tools/json-body-probe/} 的用例 J20（9 MiB 单个 JSON 对象）
     * ⇒ Go = REJECTED（错误详情长度 8388643 = 8 MiB + 3 ⇒ 证实截断点精确在 8 MiB）。
     *
     * <h3>⚠️ 端到端可达性（别把它写成「端到端漂移」）</h3>
     * <p>Go 的 {@code MaxBytes} <b>默认 1 MiB 且挂在引擎上全局生效</b>
     * ⇒ 超过 1 MiB 的 body <b>先被 MaxBytes 拦成裸 413</b> ⇒ 根本走不到 {@code ParseJsonBody}
     * ⇒ ⇒ <b>本条是「解析层」对齐，不是「端到端」对齐</b>；
     * 端到端唯一可达的 8–10 MiB 段（article 的 MaxBytes = 10 MiB）已被它自己的
     * {@code JsonBodyLimitFilter} 覆盖。
     */
    public static final int GO_MAX_BODY_LEN = 8 * 1024 * 1024;

    /**
     * Go {@code io.LimitReader(r.Body, maxBodyLen)} 的等价物 —— <b>最多</b>读
     * {@link #GO_MAX_BODY_LEN} 字节。
     *
     * <h3>⚠️ 为什么不能简单用 {@code in.readNBytes(GO_MAX_BODY_LEN)} 代替</h3>
     * <p>两者<b>判定结果</b>相同，但<b>读取时机</b>不同，差别是实质性的：
     * <ul>
     *   <li>{@code readNBytes(n)} 会<b>一直读到 n 字节或 EOF</b> ⇒ 客户端「发了一个小 JSON
     *       然后停住」时，本实现会<b>一直等</b>，Go 却早就返回了
     *       （Go 的 {@code json.Decoder.Decode} 只读它需要的那个值）。</li>
     *   <li>本包装是<b>流式</b>的：Jackson 边解析边从底层流取字节，
     *       解析完一个完整 JSON 值就停 ⇒ 与 Go 的行为同形。</li>
     * </ul>
     *
     * @param in 底层请求流（不可为 null）
     * @return 一个最多只从 {@code in} 取 {@link #GO_MAX_BODY_LEN} 字节的流
     */
    public static InputStream goLimitedBody(InputStream in) {
        return new GoLimitedInputStream(in, GO_MAX_BODY_LEN);
    }

    /**
     * {@link #goLimitedBody(InputStream)} 的实现 —— Go {@code io.LimitReader} 的 Java 等价物。
     *
     * <p>⚠️ <b>刻意不</b>实现 {@code mark/reset}：Go 的 {@code io.LimitReader} 同样不支持
     * {@code io.Seeker}；而 {@code java.io.InputStream} 的默认实现会抛
     * {@code UnsupportedOperationException} ⇒ 与 Go 的「不支持」同形。
     */
    private static final class GoLimitedInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        GoLimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0L) {
                return -1;
            }
            int b = delegate.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0L) {
                return -1;
            }
            int n = delegate.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            if (remaining <= 0L) {
                return 0L;
            }
            long skipped = delegate.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(delegate.available(), remaining);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * @param mapper Boot 装配的<b>同一个</b> mapper（带各域 {@code JsonMapperBuilderCustomizer}
     *                施加的全部语义）
     */
    public JsonBodyConverter(ObjectMapper mapper) {
        super(mapper);
        // 认领所有媒体类型，让 Spring 不再在到达转换器之前就抛 415。
        // ⚠️ 这必须配合 WebMvcConfigurer 里的「插到 Boot 的 Jackson 转换器之前」，
        //    否则它排在后面，永远不会被选中。
        setSupportedMediaTypes(List.of(MediaType.ALL));
    }

    /**
     * Spring 6.2 的签名是 {@code read(Type, Class<?>, HttpInputMessage)}
     * （Spring 7 才改成 {@code read(ResolvableType, HttpInputMessage, Map)}）。
     * ⚠️ 这里用 <b>编译期实测</b>的签名（{@code javap} {@code AbstractJackson2HttpMessageConverter}），
     * 不是照抄旧版教程 —— 写错签名会直接编译失败，反而是好事。
     */
    @Override
    public Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {

        // ⚠️ 2026-10-25（T-1 修复）：这里必须取**原始头值**，不能取 `getContentType().toString()`。
        //
        //   Go: `strings.Contains(r.Header.Get("Content-Type"), "application/json")`
        //       `r.Header.Get` 返回的是**客户端发来的原始字符串** ⇒ `strings.Contains`
        //       **大小写敏感** ⇒ `Content-Type: Application/JSON` ⇒ Go **不读 body**。
        //
        //   而 `HttpHeaders.getContentType()` 会把头**解析成 MediaType**，
        //   其 `toString()` 已把 type/subtype **小写化** ⇒ `contains` 变成大小写**不**敏感
        //   ⇒ `Application/JSON` 在本实现会被**当成 JSON 读进去** ⇒ 与 Go 相反。
        //
        //   ⇒ 改用 `getFirst(CONTENT_TYPE)` 取原始值 ⇒ 判定回到与 Go 同形。
        //
        //   ⚠️ **「判定」这一层修好之后，「解析」那一层还差一步**：
        //   `super.read(...)` 内部仍会调 `getHeaders().getContentType()`
        //   做 MediaType 解析 ⇒ **畸形头（如 `application/json, application/xml`）会抛
        //   `InvalidMediaTypeException`**，而 Go 只做字面 Contains、不会抛。
        //   ⇒ 下方的 {@link SanitizedJsonContentTypeInputMessage} 就是补这一步的。
        String contentType = inputMessage.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        long contentLength = inputMessage.getHeaders().getContentLength();

        // ⚠️ 判据的**唯一真源**在 JsonBodyConverterConfig（X-9 教训：不复制品）
        if (!JsonBodyConverterConfig.goReadsBody(contentType, contentLength)) {
            // Go: mapping.UnmarshalJsonMap(nil, v) —— **不看 body**
            // ⚠️ 刻意**不**消费请求流：Go 侧同样不读，body 留在那里由容器处理。
            //    这里读了反而会让下游（如 MaxBytes 之后的日志/审计）看到空 body。
            return null;
        }
        // ⚠️ 2026-10-25（T-1 残余 ① 修复）：Go 的 ParseJsonBody 一旦判定命中，就
        //   **只做字面 Contains，然后照常读 body** —— 它**从不解析 MIME**：
        //
        //     if withJsonBody(r) {
        //         reader := io.LimitReader(r.Body, maxBodyLen)
        //         return mapping.UnmarshalJsonReader(reader, v)   // ← 不看 Content-Type
        //     }
        //
        //   而 Spring 的解析路径**一定会**把 Content-Type 解析成 MediaType
        //   （getJavaType / charset）⇒ 畸形头会抛 InvalidMediaTypeException（unchecked）
        //   ⇒ 请求 500 ⇒ 与 Go 相反。
        //
        //   ⇒ 办法：把 message **包一层**，让 Spring 看到一个**已净化的** Content-Type，
        //     而**判定**仍然用原始值（上面那行）⇒ 两层各归各：
        //       判定层 = Go 的字面 Contains（原始值）
        //       解析层 = 一个一定能被 Spring 解析的 application/json
        //
        //   ⚠️ 顺带把 charset 也去掉：Go 的 encoding/json **只认 UTF-8**（无 charset 概念）
        //   ⇒ 客户端写 `charset=gbk` 时 Go 解析失败（非法 UTF-8）⇒ 去掉 charset 后
        //   Jackson 按 UTF-8 读 ⇒ 与 Go 同形。
        return super.read(type, contextClass, new SanitizedJsonContentTypeInputMessage(inputMessage));
    }

    /**
     * 把 {@link HttpInputMessage} 的 {@code Content-Type} 换成<b>已净化的</b> {@code application/json}
     * （<b>不带 charset</b>），其余头与 body 原样透传。
     *
     * <p>⚠️ <b>为什么不能让 Spring 看见客户端的原始 Content-Type</b>：Spring 的解析路径一定会
     * 把它当 MIME 解析 ⇒ 畸形头抛 {@code InvalidMediaTypeException} ⇒ 请求 500；
     * 而 Go 只做字面 {@code Contains}、**从不解析 MIME** ⇒ 不会抛。
     *
     * <p>⚠️ <b>为什么去掉 charset</b>：Go 的 {@code encoding/json} 只认 UTF-8
     * ⇒ 客户端声明 {@code charset=gbk} 时 Go 因非法 UTF-8 而**解析失败**；
     * 若把 charset 透传给 Spring，Jackson 会按 GBK 解码 ⇒ 变成「成功」⇒ 与 Go 相反。
     * ⇒ 去掉 charset ⇒ Jackson 按 UTF-8 读 ⇒ 与 Go 同形。
     *
     * <p>⚠️ <b>残余</b>：Jackson 会按 BOM/内容<b>自动探测</b> UTF-8/16/32 ⇒ 带 BOM 的 UTF-16
     * 仍能被本实现解析，而 Go 会拒绝 ⇒ 一条极窄的残余，已登记在
     * {@code docs/DRIFT/global/common-tails.md}「T-1 残余 ②」。
     */
    private static final class SanitizedJsonContentTypeInputMessage implements HttpInputMessage {
        private final HttpInputMessage delegate;
        private final HttpHeaders headers;

        SanitizedJsonContentTypeInputMessage(HttpInputMessage delegate) {
            this.delegate = delegate;
            HttpHeaders original = delegate.getHeaders();
            HttpHeaders copy = new HttpHeaders();
            original.forEach((name, values) -> {
                if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                    copy.put(name, values);
                }
            });
            // ⚠️ 刻意**不带 charset**（理由见类注释）
            copy.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            long len = original.getContentLength();
            if (len >= 0L) {
                copy.setContentLength(len);
            }
            this.headers = copy;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            // ⚠️ 2026-10-25（第十六轮）：这里必须加 Go 的 maxBodyLen 上限。
            //
            //   Go: rest/httpx/requests.go:119
            //       reader := io.LimitReader(r.Body, maxBodyLen)   // maxBodyLen = 8 << 20
            //
            //   ⇒ Go 对 JSON body **只读前 8 MiB** ⇒ 一个 > 8 MiB 的 JSON body
            //     在 Go 侧被**静默截断** ⇒ 解析器拿到半个文档 ⇒ 报错（域自己的错误形状）。
            //   ⇒ 而若不加上限，本实现会**整个读进堆里**并成功解析 ⇒ 两侧行为相反。
            //
            //   ⇒ 这里用 goLimitedBody（流式）而不是 readNBytes：Go 的 json.Decoder
            //     只读它需要的那个值，不会为一个小 JSON 等满 8 MiB（详见该方法的注释）。
            return JsonBodyConverter.goLimitedBody(delegate.getBody());
        }
    }

    /**
     * 🔴 <b>响应侧一律不认领</b>（2026-10-22 实测加的，代价是一条 admin-api 回归）。
     *
     * <p><b>为什么必须写这一条</b>：构造函数里的 {@code setSupportedMediaTypes(ALL)}
     * 对<b>读写两侧同时生效</b>。只覆写 {@code canRead} 是不够的 ——
     * 实测 admin-api 因此回归 4 条：它的 {@code GlobalExceptionHandler} 返回
     * {@code ResponseEntity<String>} + {@code Content-Type: text/plain}
     * （Go 的 {@code http.Error} 语义，DRIFT-016），本转换器抢在 {@code StringHttpMessageConverter}
     * 之前把它<b>按 JSON 字符串写出</b> ⇒ body 从
     * {@code field "uid" is not set} 变成 {@code "field \"uid\" is not set\n"}。
     *
     * <p>⚠️ 现象极具迷惑性：<b>状态码与 Content-Type 都对</b>（仍是 400 + text/plain），
     * 只有 body 被 JSON 转义了 —— 看响应头会以为"没问题"。
     * ⇒ 与 §3.3 同款：<b>响应对了不代表 body 对</b>。
     *
     * <p>本类<b>只</b>需要改读侧行为，写侧一律交回 Spring 的默认转换器。
     *
     * <p>⚠️ <b>⚠️ L4 差异（本仓与 RAG 的覆写点不同，不是抄漏）</b>：
     * RAG（Spring 7）覆写的是 <b>{@code canWrite(ResolvableType, Class, MediaType)}</b>，
     * 因为 Spring 7 的 {@code JacksonJsonHttpMessageConverter} <b>实现 {@code SmartHttpMessageConverter}</b>。
     * <br>而 Spring 6.2 的 {@code AbstractJackson2HttpMessageConverter}
     * （javap 实测类声明：{@code extends AbstractGenericHttpMessageConverter<Object>}）
     * <b>只实现 {@code GenericHttpMessageConverter}</b> ⇒ <b>根本没有</b>
     * {@code canWrite(ResolvableType, …)} / {@code canRead(ResolvableType, …)} 这两个方法。
     * <br>⇒ <b>Spring 6.2 真正被调用的是「Type 版」三参</b>（{@code javap -c} 反编译
     * {@code AbstractMessageConverterMethodProcessor} / {@code AbstractMessageConverterMethodArgumentResolver}，
     * 分支顺序实测为 <b>Generic → Smart → PLAIN</b>）：
     * <pre>
     *   写：GenericHttpMessageConverter.canWrite(Type, Class&lt;?&gt;, MediaType)   ← 本类走这条
     *       SmartHttpMessageConverter.canWrite(ResolvableType, Class, MediaType)  （本类不在此分支）
     *       HttpMessageConverter.canWrite(Class&lt;?&gt;, MediaType)                （兜底，走不到）
     *   读：GenericHttpMessageConverter.canRead(Type, Class&lt;?&gt;, MediaType)    ← 本类走这条
     *       SmartHttpMessageConverter.canRead(ResolvableType, MediaType)         （本类不在此分支）
     *       HttpMessageConverter.canRead(Class&lt;?&gt;, MediaType)                 （兜底，走不到）
     * </pre>
     * ⇒ <b>语义等价论证</b>：Spring 6.2 的分支顺序保证「Generic 优先」
     * ⇒ 本类覆写的 Type 版<b>就是被调用的那一版</b>
     * ⇒ 读侧仍<b>无条件认领</b>（= RAG 的 {@code canRead → true}）
     * ⇒ 写侧仍<b>一律拒领</b>（= RAG 的 {@code canWrite → false}）⇒ <b>行为等价</b>。
     * <br>⇒ ⚠️ 2 参版<b>一并覆写</b>（RAG 也覆写了转发用的 2 参版）——
     * 不是因为它会被调用，而是为了让「谁被调用」这件事<b>不依赖框架的分支顺序</b>。
     */
    @Override
    public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
        return false;
    }

    /**
     * ⚠️ <b>这一条是上面那条的转发，不要只留其中之一。</b>
     *
     * <p>2026-10-22 实测踩过：先前只覆写了 <b>两参</b> {@code canWrite(Class, MediaType)}，
     * admin-api <b>仍然回归</b> —— 因为 {@code SmartHttpMessageConverter} 里
     * {@code canWrite(ResolvableType, Class, MediaType)} 是<b>抽象</b>方法、
     * 两参版只是 {@code default} 委托给<b>它</b>，而 {@code MessageWriter} 调的正是那个抽象方法。
     * {@code AbstractSmartHttpMessageConverter} 的两参实现体（javap 实测）第一件事就是
     * {@code ResolvableType.forClass(clazz)} 然后 {@code invokevirtual canWrite(ResolvableType,...)}。
     * ⇒ 覆写「委托的那一环」等于没覆写。
     * <p>同理 {@code canRead} 必须覆写<b>真正被调用的那一环</b>（本类 = Type 版三参）。
     */
    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return false;
    }

    /**
     * 无条件认领所有媒体类型：把「要不要读 body」的判断<b>全部交给 {@link #read}</b>。
     *
     * <p>若不在这里放开，Spring 会在<b>选择转换器阶段</b>就因媒体类型不匹配而抛
     * {@code HttpMediaTypeNotSupportedException}（415），根本到不了 {@link #read}。
     *
     * <p>⚠️ 覆写点说明见 {@link #canWrite(Type, Class, MediaType)} 的 javadoc：
     * Spring 6.2 走的是 <b>Type 版三参</b>（{@code GenericHttpMessageConverter} 分支优先）。
     */
    @Override
    public boolean canRead(Type type, Class<?> clazz, MediaType mediaType) {
        return true;
    }

    /**
     * 2 参的读侧版本 —— 一并覆写，理由同 {@link #canWrite(Class, MediaType)}。
     */
    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return true;
    }
}
