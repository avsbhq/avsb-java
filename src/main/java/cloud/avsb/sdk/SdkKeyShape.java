package cloud.avsb.sdk;

import java.util.regex.Pattern;

/**
 * SDK key shape validation at construction (spec section 1.2).
 *
 * <p>Real keys are minted as {@code sdk_<environmentKey>_<id>}, where the environment key
 * matches {@code ^[a-z][a-z0-9_]*$} (it may itself contain underscores, which is why this
 * validates with one anchored pattern instead of splitting on {@code _}) and the id is a
 * cuid2 of 24 lowercase alphanumerics.
 *
 * <p>A bad key used to be invisible: the CDN 404s, the datafile fetch returns nothing, and
 * every flag silently serves its default. The four most common bad values are a pasted
 * dashboard URL, a truncated copy, a snippet key, and an API token pasted into the wrong
 * field, and all four are recognisable on sight. So the message names what it got, what the
 * shape is, and where the real key lives.
 *
 * <p>This NEVER throws and never blocks construction. A key we do not recognise may still be
 * valid, because the mint format can grow; the datafile fetch is the real arbiter.
 */
public final class SdkKeyShape {

    private SdkKeyShape() {
        // Static helpers only.
    }

    /** Matches {@code sdk_<envKey>_<id>}; the trailing id segment carries no underscore. */
    private static final Pattern SDK_KEY_PATTERN =
            Pattern.compile("^sdk_[a-z][a-z0-9_]*_[a-z0-9]{8,}$");

    /** A real-shaped example, so the message is comparable against what the user pasted. */
    private static final String EXAMPLE_KEY = "sdk_production_ttqm0eaj4vth1krcb2xn";

    /**
     * Outcome of a shape check.
     *
     * @param valid   true when the key looks like a minted SDK key.
     * @param message an actionable message to log, or null when valid.
     */
    public record Result(boolean valid, String message) {}

    /**
     * Redact all but the recognisable head of a key so a message can be pasted into a bug
     * report. The environment name is the useful half ("you used the development key"); the
     * id tail is not.
     */
    public static String describe(String sdkKey) {
        if (sdkKey == null || sdkKey.isEmpty()) return "(empty)";
        if (sdkKey.length() <= 12) return sdkKey;
        return sdkKey.substring(0, 12) + "..." + sdkKey.substring(sdkKey.length() - 4);
    }

    /** Validate the shape, returning the message to log rather than logging it. */
    public static Result validate(String sdkKey) {
        if (sdkKey != null && SDK_KEY_PATTERN.matcher(sdkKey).matches()) {
            return new Result(true, null);
        }
        return new Result(false,
                "AvsB SDK key \"" + describe(sdkKey) + "\" does not look like an SDK key: "
                        + diagnose(sdkKey) + ". Keys look like " + EXAMPLE_KEY
                        + " (sdk_<environment>_<id>). Copy the key for the environment you want"
                        + " from app.avsb.cloud, then Environments in the project sidebar. The SDK will"
                        + " keep going with the value you passed, so if the datafile request 404s"
                        + " next, this is why.");
    }

    private static String diagnose(String sdkKey) {
        if (sdkKey == null) {
            return "no SDK key was passed at all";
        }
        String trimmed = sdkKey.trim();
        if (trimmed.isEmpty()) {
            return "no SDK key was passed at all";
        }
        if (!trimmed.equals(sdkKey)) {
            return "it has leading or trailing whitespace, which usually means a copy-paste"
                    + " picked up a line break";
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return "it looks like a URL, not a key. Copy the key itself, not the address of the"
                    + " page it is on";
        }
        if (trimmed.startsWith("avsb_pat_") || trimmed.startsWith("avsb_svc_")) {
            return "it looks like an API token. API tokens talk to the management API; SDKs need"
                    + " the environment SDK key";
        }
        if (trimmed.startsWith("sdk-") || trimmed.startsWith("pub_") || trimmed.startsWith("srv_")) {
            return "that key prefix has never existed in A vs B. There is one key format for SDKs";
        }
        if (!trimmed.startsWith("sdk_")) {
            return "it does not start with \"sdk_\"";
        }
        if (trimmed.split("_").length < 3) {
            return "it is missing the environment segment, so it may have been truncated";
        }
        return "the id after the environment name is too short or has characters a generated id"
                + " never contains, so it may have been truncated or edited";
    }
}
