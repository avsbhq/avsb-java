package com.avsbhq.avsb.middleware.micronaut;

import com.avsbhq.avsb.EvalContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;

/**
 * Micronaut HTTP server filter that binds an {@link EvalContext} per request.
 *
 * <p>Subclass this filter to extract the user identity from your request
 * headers or session.
 */
@Filter("/**")
public class AvsbFilter implements HttpServerFilter {

    private static final ThreadLocal<EvalContext> CONTEXT_HOLDER = new ThreadLocal<>();

    public static EvalContext currentContext() {
        return CONTEXT_HOLDER.get();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        EvalContext ctx = buildContext(request);
        if (ctx != null) CONTEXT_HOLDER.set(ctx);
        try {
            return chain.proceed(request);
        } finally {
            CONTEXT_HOLDER.remove();
        }
    }

    protected EvalContext buildContext(HttpRequest<?> request) {
        String remoteAddress = request.getRemoteAddress().getAddress().getHostAddress();
        return EvalContext.user(remoteAddress != null ? remoteAddress : "anonymous");
    }
}
