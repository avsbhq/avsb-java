package com.avsbhq.avsb;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A type token describing what a caller expects a flag to resolve to.
 *
 * <p>This exists because Java erases generics, and that erasure used to make the SDK throw
 * in the customer's code instead of falling back. The old evaluator did this:
 *
 * <pre>{@code
 * T typed;
 * try { typed = (T) raw; } catch (ClassCastException e) { typed = defaultValue; }
 * }</pre>
 *
 * <p>The cast cannot throw there. {@code T} is erased, so the assignment is unchecked and
 * the catch block is dead code. The {@link ClassCastException} was instead thrown later, at
 * the customer's own {@code flag.value()} call site, when the JVM inserted its checkcast:
 * a JSON number reaching a {@code Flag<Double>} blew up at the unbox, in application code,
 * with a stack trace that pointed at the application rather than at the misconfigured flag.
 *
 * <p>With a type token the check happens at EVALUATION time, in the SDK, where it can be
 * logged with the flag key and the declared type, and where the documented fallback is a
 * real code path.
 *
 * <p>Behaviour of every typed getter, stated once and true for all of them:
 *
 * <ul>
 *   <li>The value resolves and the type matches: you get it.</li>
 *   <li>The type does NOT match, or the datafile declares a different type than the getter
 *       asked for: the SDK logs a warning naming the flag, the declared type, and what it
 *       actually got, then returns YOUR default value with
 *       {@link EvaluationSource#NOT_FOUND} and one reason explaining the mismatch. It never
 *       throws, and it never coerces (a string flag read through
 *       {@code getBoolFlag} does not become {@code true}).</li>
 *   <li>The SDK has no datafile yet: your default value with
 *       {@link EvaluationSource#NOT_READY}. See {@link Flag#exists()}.</li>
 * </ul>
 *
 * @param <T> the Java type the caller will read out of {@link Flag#value()}.
 */
public final class FlagValueType<T> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Outcome of coercing a raw datafile value into {@code T}. */
    public record Coerced<T>(boolean matched, T value, String mismatch) {

        static <T> Coerced<T> ok(T value) {
            return new Coerced<>(true, value, null);
        }

        static <T> Coerced<T> mismatch(String reason) {
            return new Coerced<>(false, null, reason);
        }
    }

    /** {@code Flag<Boolean>}: accepts only a JSON boolean. */
    public static final FlagValueType<Boolean> BOOLEAN = new FlagValueType<>(
            "boolean",
            raw -> raw instanceof Boolean b
                    ? Coerced.ok(b)
                    : Coerced.mismatch("resolved to " + describe(raw) + ", not a boolean"));

    /**
     * {@code Flag<String>}: accepts only a JSON string. A number or boolean is a mismatch,
     * not something to stringify: silently coercing is how {@code bool("false")} became
     * true in other SDKs.
     */
    public static final FlagValueType<String> STRING = new FlagValueType<>(
            "string",
            raw -> raw instanceof String s
                    ? Coerced.ok(s)
                    : Coerced.mismatch("resolved to " + describe(raw) + ", not a string"));

    /**
     * {@code Flag<Double>}: accepts any JSON number and widens it to {@link Double}.
     *
     * <p>Widening rather than preserving Integer is deliberate. JavaScript has one numeric
     * type, so the reference engine cannot distinguish {@code 3} from {@code 3.0}; a Java
     * SDK that handed back an {@link Integer} for one datafile and a {@link Double} for
     * another would make {@code Flag<Double>} unsafe to read. Every number flag is a
     * {@code double} here.
     */
    public static final FlagValueType<Double> NUMBER = new FlagValueType<>(
            "number",
            raw -> raw instanceof Number n
                    ? Coerced.ok(n.doubleValue())
                    : Coerced.mismatch("resolved to " + describe(raw) + ", not a number"));

    /**
     * {@code Flag<Object>}: a JSON flag's document as deserialised, which is a
     * {@link Map}, a {@link List}, a boxed primitive, or null.
     *
     * <p>Use {@link #json(Class)} when you want it mapped into a class of your own.
     */
    public static final FlagValueType<Object> JSON = new FlagValueType<>(
            "json", Coerced::ok);

    /**
     * {@code Flag<T>} for a JSON flag mapped into your own type.
     *
     * <pre>{@code
     * record Pricing(String plan, double amount) {}
     *
     * Flag<Pricing> pricing = server.getJsonFlag(
     *         "pricing", new Pricing("standard", 9.99),
     *         FlagValueType.json(Pricing.class), ctx);
     * }</pre>
     *
     * <p>A document that will not map into {@code as} is a mismatch: you get your default
     * plus a reason, not a deserialisation exception.
     */
    public static <T> FlagValueType<T> json(Class<T> as) {
        if (as == null) throw new IllegalArgumentException("json(as): target class must not be null");
        return new FlagValueType<>("json", raw -> {
            if (raw == null) {
                return Coerced.mismatch("resolved to null, which cannot map to "
                        + as.getSimpleName());
            }
            if (as.isInstance(raw)) {
                return Coerced.ok(as.cast(raw));
            }
            try {
                return Coerced.ok(MAPPER.convertValue(raw, as));
            } catch (IllegalArgumentException e) {
                return Coerced.mismatch("resolved to " + describe(raw) + ", which does not map to "
                        + as.getSimpleName() + " (" + e.getMessage() + ")");
            }
        });
    }

    private final String declaredType;
    private final Function<Object, Coerced<T>> coercion;

    private FlagValueType(String declaredType, Function<Object, Coerced<T>> coercion) {
        this.declaredType = declaredType;
        this.coercion = coercion;
    }

    /**
     * The datafile {@code type} this token corresponds to: {@code boolean}, {@code string},
     * {@code number}, or {@code json}.
     */
    public String declaredType() {
        return declaredType;
    }

    /** Coerce a raw datafile value, reporting a mismatch rather than throwing. */
    public Coerced<T> coerce(Object raw) {
        return coercion.apply(raw);
    }

    /**
     * True when the datafile's declared flag type is the one this token asks for. An absent
     * or blank declaration is accepted, because older datafiles did not carry one.
     */
    public boolean matchesDeclaredType(String datafileType) {
        if (datafileType == null || datafileType.isBlank()) {
            return true;
        }
        return declaredType.equals(datafileType);
    }

    /** A short, non-throwing description of a value, for mismatch messages. */
    private static String describe(Object raw) {
        if (raw == null) return "null";
        if (raw instanceof Boolean) return "a boolean";
        if (raw instanceof Number) return "a number";
        if (raw instanceof String) return "a string";
        if (raw instanceof List<?>) return "a JSON array";
        if (raw instanceof Map<?, ?>) return "a JSON object";
        return "a " + raw.getClass().getSimpleName();
    }
}
