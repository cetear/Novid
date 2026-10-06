package com.example.ailab.web.logging;

import com.example.ailab.contract.error.DiagnosticFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 安全链外层记录认证拒绝及实际异步完成，只保存服务端生成的编号和路由模板。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var previous = MDC.getCopyOfContextMap();
        String id = UUID.randomUUID().toString();
        MDC.put("requestId", id);
        response.setHeader("X-Request-Id", id);
        long started = System.nanoTime();
        var completed = new AtomicBoolean();
        var failed = new AtomicBoolean();
        LOG.info("event=request.start method={}", method(request));
        Runnable finish = () -> {
            if (!completed.compareAndSet(false, true)) return;
            var old = MDC.getCopyOfContextMap();
            MDC.put("requestId", id);
            try {
                Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = template instanceof String value && value.matches("[A-Za-z0-9_/{}/.*:-]{1,200}") ? value : "UNMAPPED";
                int status = failed.get() ? 500 : response.getStatus();
                String message = "event=request.complete method={} route={} status={} durationMs={}";
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (status >= 500) LOG.error(message, method(request), route, status, duration);
                else if (status >= 400) LOG.warn(message, method(request), route, status, duration);
                else LOG.info(message, method(request), route, status, duration);
            } finally { restore(old); }
        };
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            failed.set(true);
            LOG.error("event=request.failed code={}", DiagnosticFailure.code(failure), DiagnosticFailure.sanitized(failure));
            throw failure;
        } finally {
            try {
                if (request.isAsyncStarted()) {
                    var listener = new AsyncListener() {
                        public void onComplete(AsyncEvent event) { finish.run(); }
                        public void onTimeout(AsyncEvent event) {
                            withId(id, () -> LOG.warn("event=request.timeout"));
                        }
                        public void onError(AsyncEvent event) {
                            withId(id, () -> LOG.warn("event=request.async_error type={}",
                                    event.getThrowable() == null ? "UNKNOWN" : event.getThrowable().getClass().getName()));
                        }
                        public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                    };
                    try { request.getAsyncContext().addListener(listener); }
                    catch (IllegalStateException alreadyCompleted) { finish.run(); }
                } else finish.run();
            } finally { restore(previous); }
        }
    }

    private static String method(HttpServletRequest request) {
        return Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").contains(request.getMethod())
                ? request.getMethod() : "OTHER";
    }

    private static void withId(String id, Runnable action) {
        var old = MDC.getCopyOfContextMap();
        MDC.put("requestId", id);
        try { action.run(); } finally { restore(old); }
    }

    private static void restore(Map<String, String> context) {
        if (context == null) MDC.clear(); else MDC.setContextMap(context);
    }
}
