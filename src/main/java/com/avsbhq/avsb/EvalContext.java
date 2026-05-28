package com.avsbhq.avsb;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents the evaluation identity supplied by the caller. Mirrors §2.1 of
 * the spec and the {@code EvalContext} union in {@code @avsbhq/core/src/types.ts}.
 *
 * <p>Two concrete implementations:
 * <ul>
 *   <li>{@link SingleContext} — a single kinded identity with a bucketing key
 *       and optional custom attributes.</li>
 *   <li>{@link MultiContext} — a container of named {@link SingleContext}s,
 *       one per context kind (e.g. {@code user}, {@code organization}).</li>
 * </ul>
 *
 * <p>Static factory helpers ({@code user}, {@code org}, {@code multi}) cover
 * the most common shapes and mirror {@code userContext} in
 * {@code @avsbhq/core/src/context.ts}.
 */
public sealed interface EvalContext permits EvalContext.SingleContext, EvalContext.MultiContext {

    /** The kind discriminator. For multi-contexts this always returns {@code "multi"}. */
    String kind();

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    /** Build a {@code user} single-context with no extra attributes. */
    static SingleContext user(String key) {
        return new SingleContext("user", key, null, Map.of());
    }

    /** Build a {@code user} single-context with custom attributes. */
    static SingleContext user(String key, Map<String, Object> attributes) {
        return new SingleContext("user", key, null, attributes);
    }

    /** Build a single-context of the given kind. */
    static SingleContext of(String kind, String key) {
        return new SingleContext(kind, key, null, Map.of());
    }

    /** Build a single-context of the given kind with custom attributes. */
    static SingleContext of(String kind, String key, Map<String, Object> attributes) {
        return new SingleContext(kind, key, null, attributes);
    }

    /** Build a multi-context from a map of kind → single-context. */
    static MultiContext multi(Map<String, SingleContext> contexts) {
        return new MultiContext(Map.copyOf(contexts));
    }

    // -------------------------------------------------------------------------
    // SingleContext
    // -------------------------------------------------------------------------

    /**
     * A single kinded identity. Mirrors {@code SingleContext} in types.ts.
     *
     * @param kind       Context kind — {@code "user"} is conventional.
     * @param key        Unique bucketing identifier within this kind.
     * @param name       Optional human-readable display name (not used in eval).
     * @param attributes Custom targeting attributes.
     */
    record SingleContext(
            String kind,
            String key,
            String name,
            Map<String, Object> attributes
    ) implements EvalContext {

        public SingleContext {
            if (kind == null || kind.isBlank()) throw new IllegalArgumentException("kind must not be blank");
            if (key  == null)                   throw new IllegalArgumentException("key must not be null");
            attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(attributes));
        }

        /**
         * Return the value of a custom attribute. Returns {@code null} when
         * the attribute is not set. The reserved key {@code "key"} returns
         * {@link #key()}, {@code "name"} returns {@link #name()}.
         */
        public Object getAttribute(String attrKey) {
            if ("key".equals(attrKey))  return key;
            if ("name".equals(attrKey)) return name;
            return attributes.get(attrKey);
        }
    }

    // -------------------------------------------------------------------------
    // MultiContext
    // -------------------------------------------------------------------------

    /**
     * A container of named single-context entries. Mirrors {@code MultiContext}
     * in types.ts — {@code kind} is always {@code "multi"}.
     *
     * @param contexts Map of context-kind to {@link SingleContext}.
     */
    record MultiContext(Map<String, SingleContext> contexts) implements EvalContext {

        public MultiContext {
            if (contexts == null || contexts.isEmpty()) {
                throw new IllegalArgumentException("MultiContext requires at least one sub-context");
            }
            contexts = Collections.unmodifiableMap(new HashMap<>(contexts));
        }

        @Override
        public String kind() {
            return "multi";
        }

        /** Return the sub-context for the given kind, or {@code null} if absent. */
        public SingleContext get(String kind) {
            return contexts.get(kind);
        }
    }
}
