package cloud.avsb.sdk.middleware.quarkus;

import cloud.avsb.sdk.EvalContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Quarkus JAX-RS {@link ContainerRequestFilter} that binds an
 * {@link EvalContext} per request.
 *
 * <p>Subclass to extract the user identity from headers, JWT, or session.
 */
@Provider
public class AvsbRequestFilter implements ContainerRequestFilter {

    private static final ThreadLocal<EvalContext> CONTEXT_HOLDER = new ThreadLocal<>();

    public static EvalContext currentContext() {
        return CONTEXT_HOLDER.get();
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        EvalContext ctx = buildContext(requestContext);
        if (ctx != null) CONTEXT_HOLDER.set(ctx);
    }

    protected EvalContext buildContext(ContainerRequestContext requestContext) {
        // Default: use a sentinel anonymous key. Override to inject real user identity.
        return EvalContext.user("anonymous");
    }
}
