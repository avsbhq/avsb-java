package com.avsbhq.avsb;

/**
 * Result returned by {@link AvsbServer#init()} once the initial datafile load
 * completes. Mirrors {@code InitResult} (§2.3) in types.ts.
 */
public record InitResult(
        boolean success,
        Source source,
        Throwable error,
        boolean degraded
) {

    public enum Source { NETWORK, BOOTSTRAP, TIMEOUT, ERROR }

    public static InitResult ok(Source source) {
        return new InitResult(true, source, null, false);
    }

    public static InitResult degraded(Source source, Throwable error) {
        return new InitResult(true, source, error, true);
    }

    public static InitResult failure(Throwable error) {
        return new InitResult(false, Source.ERROR, error, false);
    }
}
