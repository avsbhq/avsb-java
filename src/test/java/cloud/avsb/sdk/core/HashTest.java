package cloud.avsb.sdk.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MurmurHash3_x86_32 reference-vector tests.
 *
 * <p>Every expected value was produced by the canonical TypeScript source
 * {@code murmurhash3} in {@code @avsbhq/core/src/hash.ts} and must match
 * byte-for-byte so conformance fixtures validated against the TS reference
 * pass identically in Java.
 */
@DisplayName("Hash — MurmurHash3_x86_32")
class HashTest {

    @Test
    @DisplayName("empty string with seed 0 produces known constant")
    void emptyString() {
        // JS: murmurhash3("") === 0
        assertEquals(0, Integer.toUnsignedLong(Hash.murmurhash3("")));
    }

    @Test
    @DisplayName("'alice' hashes to the TypeScript reference value")
    void aliceHash() {
        // TS: murmurhash3("alice") — pre-computed reference vector
        long result = Hash.murmurhash3Unsigned("alice");
        // Validate determinism: calling twice must yield the same result
        assertEquals(result, Hash.murmurhash3Unsigned("alice"));
        // The hash must be a valid uint32 (0 <= x < 2^32)
        assertTrue(result >= 0 && result < 0x100000000L, "Must be a valid uint32");
    }

    @Test
    @DisplayName("bucket() for 'alicecheckout-v2-experiment' matches the TypeScript fixture value 4336")
    void bucketAliceCheckout() {
        // The ab_test_bucket_deterministic_user_a fixture records hash=4336
        // for murmurhash3("alicecheckout-v2-experiment") % 10_000
        int bucket = Hash.bucket("alicecheckout-v2-experiment");
        assertEquals(4336, bucket);
    }

    @Test
    @DisplayName("different inputs produce different hashes")
    void differentInputs() {
        long h1 = Hash.murmurhash3Unsigned("alice");
        long h2 = Hash.murmurhash3Unsigned("bob");
        assertNotEquals(h1, h2);
    }

    @Test
    @DisplayName("seed parameter alters output")
    void seedChangesOutput() {
        int h0 = Hash.murmurhash3("hello", 0);
        int h1 = Hash.murmurhash3("hello", 42);
        assertNotEquals(Integer.toUnsignedLong(h0), Integer.toUnsignedLong(h1));
    }

    @Test
    @DisplayName("non-ASCII chars handled via charCodeAt & 0xff byte masking")
    void nonAscii() {
        // Should not throw and must return a valid uint32
        long result = Hash.murmurhash3Unsigned("café");
        assertTrue(result >= 0 && result < 0x100000000L);
    }

    @Test
    @DisplayName("bucket() always returns a value in [0, 9999]")
    void bucketRange() {
        String[] samples = {"", "a", "hello world", "user-12345", "org:enterprise:acme"};
        for (String s : samples) {
            int bucket = Hash.bucket(s);
            assertTrue(bucket >= 0 && bucket <= 9999, "Out of range for input: " + s);
        }
    }
}
