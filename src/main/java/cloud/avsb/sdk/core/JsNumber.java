package cloud.avsb.sdk.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JavaScript value coercion, ported exactly.
 *
 * <p>The reference evaluator is TypeScript, so every string the engine produces is a
 * JavaScript string and every numeric comparison is a JavaScript number comparison. Two
 * places make that visible to a Java port:
 *
 * <ol>
 *   <li><b>Reason strings.</b> The conformance corpus records reasons verbatim, and they
 *       interpolate numbers: {@code threshold 1000}, not {@code threshold 1000.0}.
 *       {@code String.valueOf(1000.0d)} in Java is {@code "1000.0"}, so a naive port fails
 *       the exact ordered reason comparison on every allocation case.</li>
 *   <li><b>Bucketing inputs.</b> The bucketing key is
 *       {@code String(resolvedAttribute) + flagKey}. If the hash attribute resolves to a
 *       number, Java's {@code "42.0"} and JavaScript's {@code "42"} hash differently, so
 *       the same user lands in a different variation depending on which SDK evaluated.
 *       That is the silent cross-language assignment skew this program exists to kill.</li>
 * </ol>
 *
 * <p>{@link #stringify(double)} implements ECMA-262 {@code Number::toString} radix 10:
 * the shortest decimal that round-trips, printed as a plain integer when it is integral
 * and within 1e21, and in exponential form outside the documented window.
 *
 * <p>Deliberate divergence, documented because it is unobservable in the corpus: Java
 * cannot tell an absent map entry from one whose value is {@code null}, so
 * {@link #stringOf(Object)} maps {@code null} to {@code "undefined"} and
 * {@link #numberOf(Object)} maps it to {@code NaN} (JavaScript would say {@code "null"}
 * and {@code 0} for an explicit JSON null). Both choices fail closed: an operator against
 * a missing attribute does not match.
 */
public final class JsNumber {

    private JsNumber() {
        // Static helpers only.
    }

    /** Shape of a decimal numeric literal JavaScript's {@code Number(string)} accepts. */
    private static final Pattern DECIMAL_LITERAL =
            Pattern.compile("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$");

    /** The most significant digits a double can need to round-trip. */
    private static final int MAX_SIGNIFICANT_DIGITS = 17;

    /**
     * Render a double exactly as JavaScript's {@code String(number)} would.
     *
     * <p>Examples that matter: {@code 1000.0 -> "1000"}, {@code 1234.5 -> "1234.5"},
     * {@code 0.25 -> "0.25"}, {@code 1e21 -> "1e+21"}, {@code 1e-7 -> "1e-7"},
     * {@code -0.0 -> "0"}, {@code NaN -> "NaN"}.
     */
    public static String stringify(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "Infinity";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Infinity";
        }
        // Covers both zeros: JavaScript prints negative zero as "0".
        if (value == 0.0d) {
            return "0";
        }
        if (value < 0.0d) {
            return "-" + stringify(-value);
        }

        // ECMA-262 picks digits s and exponent n such that s has as few digits as
        // possible while s * 10^(n-k) still reads back as exactly this double, with
        // ties broken toward the even digit. Rounding the exact binary expansion
        // half-even at increasing precision is that rule, spelled out.
        BigDecimal exact = new BigDecimal(value);
        BigDecimal shortest = exact;
        for (int precision = 1; precision <= MAX_SIGNIFICANT_DIGITS; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (candidate.doubleValue() == value) {
                shortest = candidate;
                break;
            }
        }

        BigDecimal stripped = shortest.stripTrailingZeros();
        String digits = stripped.unscaledValue().toString();
        int k = digits.length();
        // n is where the decimal point sits relative to the digit string.
        int n = k - stripped.scale();

        if (k <= n && n <= 21) {
            return digits + "0".repeat(n - k);
        }
        if (n > 0 && n <= 21) {
            return digits.substring(0, n) + "." + digits.substring(n);
        }
        if (n > -6 && n <= 0) {
            return "0." + "0".repeat(-n) + digits;
        }
        String mantissa = k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        int exponent = n - 1;
        return mantissa + "e" + (exponent >= 0 ? "+" : "-") + Math.abs(exponent);
    }

    /**
     * Coerce any deserialised JSON value the way JavaScript's {@code String(value)} does.
     *
     * <p>Arrays join on a comma with holes rendered empty, and plain objects stringify to
     * {@code "[object Object]"}. Those look strange in Java, and they are exactly what the
     * reference engine compares against, so a port that "fixes" them stops matching.
     */
    public static String stringOf(Object value) {
        if (value == null) {
            return "undefined";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Number n) {
            return stringify(n.doubleValue());
        }
        if (value instanceof List<?> list) {
            return joinArray(list);
        }
        if (value instanceof Map<?, ?>) {
            return "[object Object]";
        }
        return String.valueOf(value);
    }

    /**
     * Coerce any deserialised JSON value the way JavaScript's {@code Number(value)} does.
     * Anything unparseable becomes {@code NaN}, and every comparison against {@code NaN}
     * is false, so an unusable attribute never matches.
     */
    public static double numberOf(Object value) {
        if (value == null) {
            return Double.NaN;
        }
        if (value instanceof Boolean b) {
            return b ? 1.0d : 0.0d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            return parseNumericString(s);
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return 0.0d;
            }
            if (list.size() == 1) {
                return numberOf(list.get(0));
            }
            return Double.NaN;
        }
        return Double.NaN;
    }

    /** True when the value is truthy under JavaScript rules (spec section 1.4). */
    public static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d != 0.0d && !Double.isNaN(d);
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        // Empty arrays and empty objects are truthy in JavaScript. Python and Ruby
        // would disagree; the corpus locks the JavaScript answer.
        return true;
    }

    private static String joinArray(List<?> list) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Object element = list.get(i);
            // Array.prototype.join renders null and undefined as an empty string.
            if (element != null) {
                out.append(stringOf(element));
            }
        }
        return out.toString();
    }

    private static double parseNumericString(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            // Number("") is 0 in JavaScript, however odd that reads.
            return 0.0d;
        }
        if ("Infinity".equals(trimmed) || "+Infinity".equals(trimmed)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-Infinity".equals(trimmed)) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Long.parseLong(trimmed.substring(2), 16);
            }
            if (trimmed.startsWith("0o") || trimmed.startsWith("0O")) {
                return Long.parseLong(trimmed.substring(2), 8);
            }
            if (trimmed.startsWith("0b") || trimmed.startsWith("0B")) {
                return Long.parseLong(trimmed.substring(2), 2);
            }
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        // Guard before parseDouble: Java accepts "1d", "1f", and hex float literals that
        // JavaScript rejects, and accepting them would diverge silently.
        if (!DECIMAL_LITERAL.matcher(trimmed).matches()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
