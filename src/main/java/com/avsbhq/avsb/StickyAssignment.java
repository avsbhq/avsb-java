package com.avsbhq.avsb;

/**
 * A persisted sticky-bucket assignment. Mirrors {@code StickyAssignment}
 * (§2.4) in types.ts.
 *
 * @param variationId The variation id that was assigned.
 * @param ruleId      The rule that produced this assignment.
 * @param ruleType    The type of that rule.
 * @param assignedAt  ms-epoch when the assignment was first made.
 */
public record StickyAssignment(
        String variationId,
        String ruleId,
        RuleType ruleType,
        long assignedAt
) {}
