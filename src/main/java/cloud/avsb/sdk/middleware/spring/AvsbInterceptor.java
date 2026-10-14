package cloud.avsb.sdk.middleware.spring;

import cloud.avsb.sdk.EvalContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC interceptor that binds an {@link EvalContext} to a thread-local
 * for the duration of a request.
 *
 * <p>Subclass this interceptor and override {@link #buildContext} to extract
 * the user identity from your session/JWT/cookie before the controller runs.
 *
 * <pre>{@code
 * @Bean
 * public AvsbInterceptor avsbInterceptor() {
 *     return new AvsbInterceptor() {
 *         @Override
 *         protected EvalContext buildContext(HttpServletRequest req) {
 *             String userId = (String) req.getSession().getAttribute("userId");
 *             return EvalContext.user(userId);
 *         }
 *     };
 * }
 * }</pre>
 */
public class AvsbInterceptor implements HandlerInterceptor {

    private static final ThreadLocal<EvalContext> CONTEXT_HOLDER = new ThreadLocal<>();

    /** Return the evaluation context bound for the current request, or {@code null}. */
    public static EvalContext currentContext() {
        return CONTEXT_HOLDER.get();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        EvalContext ctx = buildContext(request);
        if (ctx != null) CONTEXT_HOLDER.set(ctx);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CONTEXT_HOLDER.remove();
    }

    /**
     * Override this method to extract an {@link EvalContext} from the incoming
     * request. The default implementation returns an anonymous user context
     * keyed on the remote IP address.
     */
    protected EvalContext buildContext(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return EvalContext.user(ip != null ? ip : "anonymous");
    }
}
