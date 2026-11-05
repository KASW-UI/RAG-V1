package com.rag.hot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>hot 域的 Spring MVC 装配点 —— <b>显式「不注册任何 interceptor」</b>。</b>
 *
 * <p><b>B 出处</b>：<b>无对应类</b>。
 * B 侧的 {@code service/hot/hot-api/} 全模块<b>零 interceptor、零 WebMvcConfig</b> ——
 * 唯一的端点事实是 {@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/controller/HotArticleController.java}
 * 的两处 mapping（{@code @RequestMapping("/hot/v1")} + {@code @GetMapping("/articles")}），
 * 且<b>没有任何</b> {@code rest.WithJwt} / {@code rest.WithJwtTransition} 之类的中间件挂载。
 *
 * <p><b>⚠️ 本类的存在理由（不是「多写了一个类」）</b>：
 * <ol>
 *   <li><b>文件树已含本文件</b>（{@code docs/File_Tree/hot/hot-service.md}）⇒
 *       {@code tools/difftest/check-docs-vs-tree.py} 要求<b>代码树与文件树一致</b>
 *       ⇒ 删文件会与文档对账失配 ⇒ 保留。</li>
 *   <li>本仓 10 个域是<b>同一套模板</b>生成的，{@code WebMvcConfig} 的原始 javadoc
 *       写的是「注册 JwtAuthInterceptor + public 路径白名单」—— 那是<b>message 域</b>的职责，
 *       对 hot 域<b>直接矛盾</b>（引入鉴权 = L2 行为变更）。</li>
 *   <li>⇒ 本类改为<b>显式的「零 interceptor」标记</b>：它实现 {@link WebMvcConfigurer}
 *       但 {@link #addInterceptors} <b>故意是空的</b> ⇒ ⇒
 *       <b>「hot 域全部端点免鉴权」这条决策在代码里是可见、可 review、可被断言锁住的</b>，
 *       而不是「碰巧没有这个类」。</li>
 * </ol>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>新增一个空实现类</b>（B 侧无对应物）。
 *       它<b>不注册任何 interceptor</b>、<b>不改变任何 Jackson 行为</b>、<b>不改变任何请求处理</b>
 *       ⇒ ⇒ 对可观察行为的影响 = <b>零</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>B 侧 hot-api 的 HTTP 请求链上<b>没有</b>任何本仓意义上的 interceptor
 *             ⇒ 本类不注册 ⇒ 两侧请求链<b>同形</b>；</li>
 *         <li>B 侧 hot-api 也<b>没有</b>域私有的 Jackson coercion 规则
 *             （hot 的请求体只有 query 参数，没有 JSON body）
 *             ⇒ 本类<b>不</b>加 {@code Jackson2ObjectMapperBuilderCustomizer}
 *             ⇒ 共享层的通用规则照常生效（那是 B 侧也有的公共层）。</li>
 *       </ul>
 *       ⚠️ <b>若将来有人给 hot 加鉴权</b>：那会改变 L2 语义
 *       （「公开热门列表突然要 token」）⇒ 必须走决策流程并在 hot 域台账登记，
 *       <b>不能</b>只在这里加一行 interceptor。</li>
 *   <li><b>B 出处</b>：<b>无</b> —— 本类是「B 侧无对应物」的显式标记，
 *       其依据是 B 侧 {@code service/hot/hot-api/} 全模块的<b>零 interceptor 事实</b>。</li>
 * </ol>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * <p><b>刻意为空</b> —— hot 域<b>不注册任何 interceptor</b>。
     *
     * <p>⚠️ <b>这个空方法就是「免鉴权」这条决策的落点</b>：它让「本域没有鉴权」
     * 成为<b>代码里可见的事实</b>，而不是「碰巧没人写」。
     *
     * @param registry Spring MVC 的 interceptor 注册表（<b>不写入任何东西</b>）
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 刻意为空 —— B 侧 hot-api 全模块零 interceptor（见类注释）。
        // ⚠️ 不要在这里加 JwtAuthInterceptor：那会把「公开热门列表」变成「需要 token」，
        //    属于 L2 行为变更，必须走决策流程 + 台账登记。
    }
}
