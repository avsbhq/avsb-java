package com.avsbhq.avsb;

import java.util.Map;

/**
 * Structured logger interface. Mirrors {@code Logger} (§2.13) in types.ts.
 *
 * <p>Pass a custom implementation to {@link AvsbServer.ServerBuilder#logger}
 * to route SDK logs into your application logging framework (SLF4J, Log4j,
 * java.util.logging, etc.).
 *
 * <p>The default no-op implementation discards all messages.
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

    /** No-op logger that discards all messages. */
    static Logger noOp() {
        return new Logger() {
            @Override public void debug(String m, Map<String, Object> d) {}
            @Override public void info(String m, Map<String, Object> d)  {}
            @Override public void warn(String m, Map<String, Object> d)  {}
            @Override public void error(String m, Map<String, Object> d) {}
        };
    }

    /** Console logger that prints to {@code System.out}/{@code System.err}. */
    static Logger console() {
        return new Logger() {
            @Override public void debug(String m, Map<String, Object> d) { System.out.println("[AVSB DEBUG] " + m + (d != null ? " " + d : "")); }
            @Override public void info(String m, Map<String, Object> d)  { System.out.println("[AVSB INFO]  " + m + (d != null ? " " + d : "")); }
            @Override public void warn(String m, Map<String, Object> d)  { System.out.println("[AVSB WARN]  " + m + (d != null ? " " + d : "")); }
            @Override public void error(String m, Map<String, Object> d) { System.err.println("[AVSB ERROR] " + m + (d != null ? " " + d : "")); }
        };
    }
}
