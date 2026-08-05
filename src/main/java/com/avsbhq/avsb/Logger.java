package com.avsbhq.avsb;

import java.util.Locale;
import java.util.Map;

/**
 * Structured logger interface. Mirrors {@code Logger} in
 * {@code @avsbhq/core/src/types.ts}.
 *
 * <p>Pass an implementation to {@code AvsbServer.builder(...).logger(...)} to route SDK logs
 * into SLF4J, Log4j, {@code java.util.logging}, or anything else:
 *
 * <pre>{@code
 * org.slf4j.Logger slf4j = LoggerFactory.getLogger("avsb");
 * AvsbServer server = AvsbServer.builder(key)
 *         .logger(new Logger() {
 *             @Override public void debug(String m, Map<String, Object> d) { slf4j.debug(m); }
 *             @Override public void info(String m, Map<String, Object> d)  { slf4j.info(m); }
 *             @Override public void warn(String m, Map<String, Object> d)  { slf4j.warn(m); }
 *             @Override public void error(String m, Map<String, Object> d) { slf4j.error(m); }
 *         })
 *         .build();
 * }</pre>
 *
 * <p>The DEFAULT when you pass nothing is {@link #defaultLogger()}: warnings and errors to
 * standard error, debug and info dropped. It is not a no-op, deliberately. Every
 * diagnosable failure in the previous generation of these SDKs (wrong key, 404 datafile,
 * ignored option, dropped event) was whispered into a logger nobody had configured, so the
 * symptom people actually saw was "flags always return the default" with nothing to go on.
 */
public interface Logger {

    void debug(String message, Map<String, Object> data);
    void info(String message, Map<String, Object> data);
    void warn(String message, Map<String, Object> data);
    void error(String message, Map<String, Object> data);

    default void debug(String message) { debug(message, null); }
    default void info(String message)  { info(message, null); }
    default void warn(String message)  { warn(message, null); }
    default void error(String message) { error(message, null); }

    /** Return a child logger that prefixes every message with {@code prefix + " "}. */
    default Logger child(String prefix) {
        Logger parent = this;
        return new Logger() {
            private String tag(String msg) { return prefix + " " + msg; }
            @Override public void debug(String m, Map<String, Object> d) { parent.debug(tag(m), d); }
            @Override public void info(String m, Map<String, Object> d)  { parent.info(tag(m), d); }
            @Override public void warn(String m, Map<String, Object> d)  { parent.warn(tag(m), d); }
            @Override public void error(String m, Map<String, Object> d) { parent.error(tag(m), d); }
        };
    }

    /** No-op logger that discards everything. Use it to silence the SDK completely. */
    static Logger noOp() {
        return new Logger() {
            @Override public void debug(String m, Map<String, Object> d) {}
            @Override public void info(String m, Map<String, Object> d)  {}
            @Override public void warn(String m, Map<String, Object> d)  {}
            @Override public void error(String m, Map<String, Object> d) {}
        };
    }

    /** Console logger at every level: debug and info to stdout, warn and error to stderr. */
    static Logger console() {
        return new Logger() {
            @Override public void debug(String m, Map<String, Object> d) { System.out.println("[AVSB DEBUG] " + m + suffix(d)); }
            @Override public void info(String m, Map<String, Object> d)  { System.out.println("[AVSB INFO]  " + m + suffix(d)); }
            @Override public void warn(String m, Map<String, Object> d)  { System.err.println("[AVSB WARN]  " + m + suffix(d)); }
            @Override public void error(String m, Map<String, Object> d) { System.err.println("[AVSB ERROR] " + m + suffix(d)); }
        };
    }

    /**
     * The default logger: warnings and errors to standard error, debug and info dropped.
     *
     * <p>Drops to {@link #noOp()} when the process declares itself production through
     * {@code AVSB_ENV}, {@code APP_ENV}, {@code ENVIRONMENT}, or
     * {@code SPRING_PROFILES_ACTIVE} (any value containing "prod"), or through the
     * {@code avsb.env} system property. Spec section 1.2 asks for console-at-warn in
     * development and noop in production; the JVM has no build-time production flag, so
     * those four environment variables are the signal, and the default when nothing is set
     * is to speak up rather than stay silent.
     */
    static Logger defaultLogger() {
        return isProduction() ? noOp() : warnLevelConsole();
    }

    /** Warnings and errors to standard error; debug and info dropped. */
    static Logger warnLevelConsole() {
        return new Logger() {
            @Override public void debug(String m, Map<String, Object> d) {}
            @Override public void info(String m, Map<String, Object> d)  {}
            @Override public void warn(String m, Map<String, Object> d)  { System.err.println("[AVSB WARN]  " + m + suffix(d)); }
            @Override public void error(String m, Map<String, Object> d) { System.err.println("[AVSB ERROR] " + m + suffix(d)); }
        };
    }

    /** True when an environment signal names this a production process. */
    static boolean isProduction() {
        String[] candidates = {
                System.getProperty("avsb.env"),
                System.getenv("AVSB_ENV"),
                System.getenv("APP_ENV"),
                System.getenv("ENVIRONMENT"),
                System.getenv("SPRING_PROFILES_ACTIVE"),
        };
        for (String candidate : candidates) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).contains("prod")) {
                return true;
            }
        }
        return false;
    }

    private static String suffix(Map<String, Object> data) {
        return data != null && !data.isEmpty() ? " " + data : "";
    }
}
