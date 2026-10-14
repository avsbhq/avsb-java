package cloud.avsb.sdk.core;

import cloud.avsb.sdk.EvalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Context — helpers")
class ContextTest {

    @Test
    @DisplayName("getContextByKind returns single context when kind matches")
    void getContextByKindSingle() {
        EvalContext.SingleContext user = EvalContext.user("alice");
        EvalContext.SingleContext found = Context.getContextByKind(user, "user");
        assertSame(user, found);
    }

    @Test
    @DisplayName("getContextByKind returns null when kind mismatches")
    void getContextByKindMismatch() {
        EvalContext.SingleContext user = EvalContext.user("alice");
        assertNull(Context.getContextByKind(user, "organization"));
    }

    @Test
    @DisplayName("getContextByKind resolves from multi-context")
    void getContextByKindMulti() {
        EvalContext.SingleContext user = EvalContext.user("alice");
        EvalContext.SingleContext org  = EvalContext.of("organization", "acme", Map.of("tier", "enterprise"));
        EvalContext multi = EvalContext.multi(Map.of("user", user, "organization", org));
        assertSame(org, Context.getContextByKind(multi, "organization"));
    }

    @Test
    @DisplayName("resolveDottedAttribute resolves 'user.key' from multi-context")
    void resolveDottedUserKey() {
        EvalContext.SingleContext user = EvalContext.user("alice");
        EvalContext.SingleContext org  = EvalContext.of("organization", "acme");
        EvalContext multi = EvalContext.multi(Map.of("user", user, "organization", org));
        assertEquals("alice", Context.resolveDottedAttribute(multi, "user.key"));
    }

    @Test
    @DisplayName("resolveDottedAttribute resolves nested attribute via dotted path")
    void resolveDottedNestedAttr() {
        EvalContext.SingleContext org = EvalContext.of("organization", "acme", Map.of("tier", "enterprise"));
        EvalContext multi = EvalContext.multi(Map.of(
                "user", EvalContext.user("alice"),
                "organization", org));
        assertEquals("enterprise", Context.resolveDottedAttribute(multi, "organization.tier"));
    }

    @Test
    @DisplayName("resolveDottedAttribute returns null for missing kind in multi-context")
    void resolveDottedMissingKind() {
        EvalContext multi = EvalContext.multi(Map.of("user", EvalContext.user("alice")));
        assertNull(Context.resolveDottedAttribute(multi, "organization.tier"));
    }

    @Test
    @DisplayName("bucketingKey returns primary key for single context")
    void bucketingKeySingle() {
        assertEquals("alice", Context.bucketingKey(EvalContext.user("alice")));
    }

    @Test
    @DisplayName("primaryContext returns user sub-context from multi-context")
    void primaryContextMulti() {
        EvalContext.SingleContext user = EvalContext.user("alice");
        EvalContext.SingleContext org  = EvalContext.of("organization", "acme");
        EvalContext multi = EvalContext.multi(Map.of("user", user, "organization", org));
        assertSame(user, Context.primaryContext(multi));
    }
}
