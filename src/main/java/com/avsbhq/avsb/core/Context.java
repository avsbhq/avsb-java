package com.avsbhq.avsb.core;

import com.avsbhq.avsb.DecisionLogEntry;
import com.avsbhq.avsb.EvalContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Context utility helpers. Mirrors the helpers exported from
 * {@code @avsbhq/core/src/context.ts}.
 */
public final class Context {

    private Context() {}

    private static final java.util.Set<String> RESERVED = java.util.Set.of("kind", "key", "name", "_meta");

    /**
     * Resolve a sub-context by kind. For a {@link EvalContext.SingleContext}
     * returns the context itself when its kind matches, otherwise {@code null}.
     * For a {@link EvalContext.MultiContext} delegates to the contexts map.
     */
    public static EvalContext.SingleContext getContextByKind(EvalContext ctx, String kind) {
        return switch (ctx) {
            case EvalContext.SingleContext single -> kind.equals(single.kind()) ? single : null;
            case EvalContext.MultiContext multi   -> multi.get(kind);
        };
    }

    /**
     * Return the canonical bucketing key for a context. For a
     * {@link EvalContext.SingleContext} returns its {@code key}. For a
     * {@link EvalContext.MultiContext} looks up the {@code user} sub-context
     * (or the first available kind) and returns its {@code key}. Returns the
     * empty string when no key is resolvable.
     */
    public static String bucketingKey(EvalContext ctx, String kind) {
        EvalContext.SingleContext sub = getContextByKind(ctx, kind);
        return sub != null ? sub.key() : "";
    }

    public static String bucketingKey(EvalContext ctx) {
        return bucketingKey(ctx, "user");
    }

    /**
     * Summarise an {@link EvalContext} to a list of kind+key pairs. Attribute
     * values are intentionally omitted to avoid PII in decision-log entries.
     */
    public static List<DecisionLogEntry.ContextRef> contextSummary(EvalContext ctx) {
        return switch (ctx) {
            case EvalContext.SingleContext single -> List.of(new DecisionLogEntry.ContextRef(single.kind(), single.key()));
            case EvalContext.MultiContext multi -> {
                List<DecisionLogEntry.ContextRef> out = new ArrayList<>();
                for (EvalContext.SingleContext sub : multi.contexts().values()) {
                    out.add(new DecisionLogEntry.ContextRef(sub.kind(), sub.key()));
                }
                yield List.copyOf(out);
            }
        };
    }

    /**
     * Resolve a dotted attribute path against an {@link EvalContext}. Mirrors
     * {@code resolveDottedAttribute} in {@code evaluate.ts}.
     *
     * <ul>
     *   <li>{@code "user.key"} → key of the user sub-context.</li>
     *   <li>{@code "organization.tier"} → {@code tier} attribute on the
     *       organization sub-context.</li>
     *   <li>{@code "userId"} (no dot) → legacy fallback to the primary
     *       context's key.</li>
     *   <li>Any other undotted path → attribute on the primary context.</li>
     * </ul>
     *
     * Returns {@code null} when the path cannot be resolved.
     */
    public static Object resolveDottedAttribute(EvalContext ctx, String dottedPath) {
        if (dottedPath == null || dottedPath.isEmpty()) return null;

        int dotIdx = dottedPath.indexOf('.');
        if (dotIdx > 0) {
            String kind = dottedPath.substring(0, dotIdx);
            String attr = dottedPath.substring(dotIdx + 1);
            EvalContext.SingleContext sub = getContextByKind(ctx, kind);
            if (sub == null) return null;
            return readSubContextAttribute(sub, attr);
        }

        // Non-dotted — legacy fallback
        EvalContext.SingleContext primary = primaryContext(ctx);
        if (primary == null) return null;
        if ("userId".equals(dottedPath)) return primary.key();
        return readSubContextAttribute(primary, dottedPath);
    }

    /** Read a single attribute from a sub-context, honouring reserved field aliases. */
    public static Object readSubContextAttribute(EvalContext.SingleContext sub, String attribute) {
        if ("key".equals(attribute))  return sub.key();
        if ("name".equals(attribute)) return sub.name();
        if (RESERVED.contains(attribute)) return null;
        return sub.attributes().get(attribute);
    }

    /**
     * Return the primary single-context for an {@link EvalContext}. For a
     * {@link EvalContext.MultiContext} this is the {@code user} sub-context
     * when present, otherwise the first declared sub-context.
     */
    public static EvalContext.SingleContext primaryContext(EvalContext ctx) {
        return switch (ctx) {
            case EvalContext.SingleContext single -> single;
            case EvalContext.MultiContext multi -> {
                EvalContext.SingleContext user = multi.get("user");
                if (user != null) yield user;
                yield multi.contexts().values().stream().findFirst().orElse(null);
            }
        };
    }
}
