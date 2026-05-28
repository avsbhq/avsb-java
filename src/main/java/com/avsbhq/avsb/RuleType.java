package com.avsbhq.avsb;

/**
 * The type of rule that produced a flag evaluation. Mirrors §2.7 of the spec
 * and the {@code RuleType} union in {@code @avsbhq/core/src/types.ts}.
 */
public enum RuleType {
    TARGETED_DELIVERY,
    AB_TEST,
    HOLDOUT,
    BANDIT;

    /** Wire-format name used in JSON fixtures and API responses. */
    public String wireValue() {
        return switch (this) {
            case TARGETED_DELIVERY -> "targeted_delivery";
            case AB_TEST           -> "ab_test";
            case HOLDOUT           -> "holdout";
            case BANDIT            -> "bandit";
        };
    }

    /** Parse a wire-format string. Returns {@code null} for unknown values. */
    public static RuleType fromWire(String wire) {
        if (wire == null) return null;
        return switch (wire) {
            case "targeted_delivery" -> TARGETED_DELIVERY;
            case "ab_test"           -> AB_TEST;
            case "holdout"           -> HOLDOUT;
            case "bandit"            -> BANDIT;
            default                  -> null;
        };
    }
}
