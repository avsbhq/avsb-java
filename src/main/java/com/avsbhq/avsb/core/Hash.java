package com.avsbhq.avsb.core;

/**
 * MurmurHash3_x86_32 — deterministic 32-bit hash for visitor bucketing.
 *
 * <p>This is an exact Java port of {@code murmurhash3} in
 * {@code @avsbhq/core/src/hash.ts}. It must produce byte-identical outputs
 * so conformance fixtures validated against the TypeScript reference pass
 * without adjustment.
 *
 * <p>Java {@code int} is signed 32-bit two's-complement, which matches
 * JavaScript's {@code Math.imul} semantics. The critical difference is that
 * Java's {@code >>} is a signed shift whereas JavaScript's {@code >>>} is
 * unsigned — we use {@code >>>} throughout to match the TS source.
 */
public final class Hash {

    private Hash() {}

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    /**
     * Hash {@code key} using MurmurHash3_x86_32 with the given {@code seed}.
     *
     * @param key  UTF-16 string (characters accessed via {@link String#charAt}).
     * @param seed Initial hash seed (default 0).
     * @return Unsigned 32-bit hash value expressed as a Java {@code int}
     *         (may be negative — treat as unsigned when computing bucket
     *         percentages by masking with {@code 0xFFFFFFFFL}).
     */
    public static int murmurhash3(String key, int seed) {
        int h1 = seed;
        int len = key.length();
        int nblocks = len >> 2;

        // Body — process 4-byte blocks
        for (int i = 0; i < nblocks; i++) {
            int i4 = i * 4;
            int k1 = (key.charAt(i4) & 0xff)
                    | ((key.charAt(i4 + 1) & 0xff) << 8)
                    | ((key.charAt(i4 + 2) & 0xff) << 16)
                    | ((key.charAt(i4 + 3) & 0xff) << 24);

            k1 = imul(k1, C1);
            k1 = (k1 << 15) | (k1 >>> 17);
            k1 = imul(k1, C2);

            h1 ^= k1;
            h1 = (h1 << 13) | (h1 >>> 19);
            h1 = imul(h1, 5) + 0xe6546b64;
        }

        // Tail — handle remaining bytes
        int tail = nblocks * 4;
        int k1 = 0;

        switch (len & 3) {
            case 3:
                k1 ^= (key.charAt(tail + 2) & 0xff) << 16;
                // fall through
            case 2:
                k1 ^= (key.charAt(tail + 1) & 0xff) << 8;
                // fall through
            case 1:
                k1 ^= key.charAt(tail) & 0xff;
                k1 = imul(k1, C1);
                k1 = (k1 << 15) | (k1 >>> 17);
                k1 = imul(k1, C2);
                h1 ^= k1;
        }

        // Finalization mix — force all bits to avalanche
        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 = imul(h1, 0x85ebca6b);
        h1 ^= h1 >>> 13;
        h1 = imul(h1, 0xc2b2ae35);
        h1 ^= h1 >>> 16;

        // Match TypeScript's `h1 >>> 0` — convert to unsigned representation
        // by returning the raw int (callers must use Integer.toUnsignedLong
        // or `& 0xFFFFFFFFL` when the sign bit matters for comparison).
        return h1;
    }

    /** Convenience overload with seed = 0, matching TypeScript default. */
    public static int murmurhash3(String key) {
        return murmurhash3(key, 0);
    }

    /**
     * Return the hash as an unsigned long. Use this for bucket calculations
     * so negative ints don't produce unexpected modulo results.
     */
    public static long murmurhash3Unsigned(String key) {
        return Integer.toUnsignedLong(murmurhash3(key));
    }

    /**
     * Simulate JavaScript's {@code Math.imul} — 32-bit integer multiplication
     * that discards the upper 32 bits of the 64-bit product. Java's {@code *}
     * already does this for {@code int * int} (overflow wraps), so this is
     * just a named alias to make the intent explicit.
     */
    static int imul(int a, int b) {
        return a * b;
    }

    /**
     * Compute the bucket index (0–9999) for a given hash input.
     * This matches the pattern {@code murmurhash3(input) % 10_000} in the
     * TypeScript evaluator, using unsigned modulo to avoid negative results.
     */
    public static int bucket(String input) {
        return (int) (murmurhash3Unsigned(input) % 10_000L);
    }
}
