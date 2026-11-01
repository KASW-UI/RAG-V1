package com.rag.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 🔴 <b>Go 风格的 form 值上限闸门</b> —— 对齐 go-zero {@code rest/httpx} 的
 * {@code maxFormParamCount = 2048} ＋ Go 标准库 {@code net/url} 的三条解析错误。
 *
 * <h2>🔴 为什么它是 {@link HandlerInterceptor} 而不是 servlet {@code Filter}</h2>
 * <p><b>因为 Go 的拒绝形状是「按域而异」的</b>（见 {@link LimitMode}）：
 * <ul>
 *   <li>{@link LimitMode#RAW} 的域 ⇒ 400 + Go 原文 ⇒ 共享层<b>自己写</b>即可；</li>
 *   <li>{@link LimitMode#ENVELOPE} 的域 ⇒ 200 + <b>该域自己的</b>错误码表文案
 *       ⇒ 共享层<b>写不出来</b>（文案在域里）⇒ 必须<b>抛给该域的兜底 handler</b> 去渲染。</li>
 * </ul>
 * <p>⇒ servlet {@code Filter} 抛出的异常<b>到不了</b> {@code @RestControllerAdvice}
 * （Filter 在 DispatcherServlet <b>之外</b>）⇒ ⇒ 必须用 Interceptor：
 * {@code DispatcherServlet.doDispatch} 把 {@code applyPreHandle} 放在 try 块里
 * ⇒ 抛出的异常会走 {@code processHandlerException} ⇒ {@code ExceptionHandlerExceptionResolver}
 * ⇒ <b>命中各域的 {@code @ExceptionHandler(Exception.class)} 兜底</b>
 * ⇒ 那里正是「查自己的表、渲染 200 + {code:5001, 自己的文案, data:null}」的地方。
 *
 * <h2>🔴 为什么「抛异常」不会把 Go 原文泄露到 wire 上</h2>
 * <p>Go 侧同理：{@code httpx.ErrorCtx} → {@code SetErrorHandler} 只取 code/msg，
 * <b>错误原文不进 wire</b> ⇒ RAG 侧也必须这样 ⇒ 本类刻意<b>不</b>继承
 * {@code BusinessException}（否则各域的 {@code @ExceptionHandler(BusinessException.class)}
 * 会拿 {@code getMessage()} 当信封 msg ⇒ 把 Go 原文塞进去 ⇒ 详见
 * {@link LimitExceededException} 的类注释）。
 *
 * <h2>🔴 为什么只统计 query string，不碰 body</h2>
 * <p>Go 侧 {@code r.Form} 是「query + body（仅 urlencoded / multipart 字段）」的合并结果
 * （{@code httpx/util.go:23-31}），但本闸门<b>只数 query</b>，理由与
 * {@link GetFormValuesFilter} 的类注释一致：
 * <ul>
 *   <li><b>multipart</b>：Tomcat 的 {@code Connector.maxPartCount} 默认 <b>50</b>
 *       （{@code catalina/connector/Connector.java:253}）⇒ multipart 表单字段 ≤ 50 ⇒ 数不数都一样；</li>
 *   <li><b>urlencoded</b>：全仓没有任何 api 端点接受 {@code application/x-www-form-urlencoded} 请求体；</li>
 *   <li>⚠️ <b>不能用 {@code getParameterMap()} 补齐 body</b>：它会让容器提前解析 multipart body
 *       ⇒ 改变 body 的消费时机 ⇒ 打断 {@code task-api} 的 {@code GoStyleRawBodyArgumentProcessor}。</li>
 * </ul>
 *
 * <h2>⚠️ 与 Tomcat 容器上限的关系</h2>
 * <p>Tomcat 自己也有 {@code maxParameterCount}（默认 1000），且它<b>数的是参数总数（含空值）</b>
 * ⇒ 与 Go 的「非空值」语义不同 ⇒ 若不先把容器上限抬起来，<b>Tomcat 会先于本闸门拒绝</b>
 * （且拒绝形状是 400 + HTML 错误页 ⇒ 与 Go 不同）。
 * ⇒ ⇒ 容器上限由 {@link GoStyleTomcatParameterLimitCustomizer} 一并抬到
 * {@link GoStyleTomcatParameterLimitCustomizer#MAX_PARAMETER_COUNT}（10001）。
 *
 * <h2>⚠️ 它与「鉴权」的先后（一个已知的有界差异）</h2>
 * <p>Go 侧：{@code rest.WithJwt} 是<b>中间件</b>（先跑），{@code httpx.Parse} 在 <b>handler 内</b>（后跑）
 * ⇒ Go 是「<b>先鉴权，后解析</b>」。
 * <br>本拦截器取 {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE}（先于各域的鉴权拦截器）
 * ⇒ RAG 是「<b>先解析，后鉴权</b>」⇒ 一个「既无合法 token、又带 2049 个参数」的请求：
 * Go 返 <b>401</b>、RAG 返 <b>400/5001</b>。
 * <br>⚠️ 这是<b>本条修之前就存在</b>的差异（article 的同名 filter 一直跑在鉴权之前），
 * 本轮<b>刻意不</b>改（避免扩大 scope）⇒ 记为有界残留，见台账。
 * <p>⚠️ <b>2026-10-30 · G4 lane：本类属 api 侧 rest 中间件 ⇒ 已挂 （D1 合并后已移除，见类注释）</b>
 * ⇒ ⇒ 此前「rpc 域不注册本类」的依据是<b>域侧不扫 {@code com.rag.common} 这道约定</b>（不是本类的条件）
 * ⇒ ⇒ 现由共享层自己兜住 ⇒ ⇒ Go 出处与语义等价论证见该注解。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStyleFormValueLimitInterceptor.java}
 * §1.12 第 267 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
/**
 * <p><b>⚠️ D1 落地差异（去 {@code @ConditionalOnApiSide}）</b>：RAG 侧本类挂了
 * {@code @ConditionalOnApiSide}（{@code rag.side=api} ⇒ 只在 api 侧注册）⇒
 * ⇒ 但 RAG-V1 <b>D1 把 api + rpc 合并为单一进程</b> ⇒ ⇒ <b>不存在「api 侧」这个维度</b>
 * ⇒ ⇒ 该条件注解在本仓<b>没有对应物</b>（映射表 §1.12 第 282 行：<b>「不是缺口」</b>）
 * ⇒ ⇒ 本类<b>无条件注册</b>（由 {@code common/autoconfigure/WebAutoConfiguration} 统一装配）。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ValueLimitInterceptor implements HandlerInterceptor {

    private final LimitMode.Mode mode;

    public ValueLimitInterceptor(
            @Value("${" + LimitMode.PROPERTY + ":off}") String mode) {
        // 🔴 非法值在**构造期**就炸 ⇒ 启动失败 ⇒ 不会「静默装了个不生效的闸门」
        this.mode = LimitMode.parse(mode);
    }

    /** 本域当前生效的模式（供日志与守门测试断言）。 */
    public LimitMode.Mode mode() {
        return mode;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (mode == LimitMode.Mode.OFF) {
            return true;
        }
        String violation = GetFormValuesFilter.goParseViolation(request.getQueryString());
        if (violation == null) {
            return true;
        }
        if (mode == LimitMode.Mode.ENVELOPE) {
            // 🔴 抛给该域的兜底 handler ⇒ 由它查**自己的**错误码表渲染 200 + {code:5001,…}
            throw new LimitExceededException(violation);
        }
        // RAW：Go 的 http.Error 形状（400 + text/plain + nosniff + Go 原文 + "\n"）
        GetFormValuesFilter.writeGoStyleBadRequest(response, violation);
        return false;
    }
}
