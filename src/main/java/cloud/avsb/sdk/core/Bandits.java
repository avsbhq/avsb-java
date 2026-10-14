package cloud.avsb.sdk.core;

import cloud.avsb.sdk.EvalContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Bandit evaluation engine: epsilon-greedy, Thompson sampling, UCB1.
 *
 * <p>Mirrors {@code @avsbhq/core/src/bandits.ts}. All algorithms are pure functions of
 * {@code (perAction, actionIds, hashBucket)}; no {@link Math#random()} is used. Every
 * stochastic step is seeded from {@link Hash#murmurhash3} so a {@code (banditKey, context)}
 * pair always produces the same decision, which is what makes bandit decisions replayable
 * during incident triage and assertable in fixtures.
 *
 * <p>Seeds are handled as UNSIGNED 32-bit values. The reference hash returns
 * {@code h1 >>> 0}, so a seed with the high bit set stringifies as a large positive number
 * in JavaScript and as a negative number in Java. Feeding the signed form into the
 * seed string produced a different Gaussian draw, so Thompson sampling picked a different
 * action on the JVM than everywhere else.
 */
public final class Bandits {

    private Bandits() {
        // Static helpers only.
    }

    private static final int MAX_BUCKET = 10_000;
    private static final long HASH_RANGE = 0x100000000L; // 2^32

    /** Epsilon used when the bandit config does not publish one. */
    public static final double DEFAULT_EXPLORATION_RATE = 0.1;

    /** Model version reported for the store-free cold-start fallback. */
    public static final String COLD_START_MODEL_VERSION = "cold-start";

    // -------------------------------------------------------------------------
    // Model + store shapes
    // -------------------------------------------------------------------------

    /**
     * Per-action reward statistics.
     *
     * @param mean     Observed mean reward.
     * @param variance Observed variance. Defaults to 1 for an unseen action so Thompson
     *                 sampling still has a defined scale.
     * @param samples  Observation count. Zero means never sampled.
     */
    public record BanditPerActionStats(double mean, double variance, int samples) {}

    /**
     * A learned bandit model.
     *
     * @param contextualModels Optional per-context-bucket override maps, keyed by the
     *                         {@code key=value;key=value} bucket string derived from the
     *                         actions' declared {@code contextAttributes}.
     */
    public record BanditModel(
            String version,
            String algorithm,
            Map<String, BanditPerActionStats> perAction,
            Map<String, Map<String, BanditPerActionStats>> contextualModels
    ) {

        public BanditModel(String version, String algorithm,
                           Map<String, BanditPerActionStats> perAction) {
            this(version, algorithm, perAction, Map.of());
        }
    }

    /**
     * Where learned models live. Models are client-layer state (they are learned, not
     * published in the datafile), so the SDK takes a store rather than reading the
     * datafile.
     */
    public interface ModelStore {

        /** Synchronous hot-path read. Null means "no model for this bandit". */
        BanditModel getModel(String banditKey);

        /** Fire and forget: implementations may batch or persist asynchronously. */
        void updateModel(String banditKey, BanditModel model);
    }

    /** Outcome of a bandit dispatch. */
    public record Decision(
            Datafile.BanditAction action,
            double actionProbability,
            Double optimalityGap,
            String modelVersion
    ) {}

    public record EpsilonGreedyResult(String actionId, double actionProbability, boolean exploreFired) {}

    public record ThompsonSampleResult(String actionId, double actionProbability) {}

    public record Ucb1Result(String actionId, double actionProbability) {}

    // -------------------------------------------------------------------------
    // Seeded pseudo-randomness (matches bandits.ts)
    // -------------------------------------------------------------------------

    /** Derive a value in (0, 1] from an unsigned seed and a salt integer. */
    static double uniformFromSeed(long unsignedSeed, int salt) {
        int hashed = Hash.murmurhash3(unsignedSeed + ":" + salt);
        long unsigned = Integer.toUnsignedLong(hashed);
        // The +1 keeps the result strictly above zero: Box-Muller takes log(u1).
        return (unsigned + 1.0) / (HASH_RANGE + 1.0);
    }

    /**
     * Produce a single standard-normal sample deterministically from {@code unsignedSeed}.
     * Mirrors {@code seededGaussian} in bandits.ts.
     *
     * @param unsignedSeed a murmur hash treated as unsigned; pass
     *                     {@code Integer.toUnsignedLong(hash)}.
     */
    public static double seededGaussian(long unsignedSeed) {
        double u1 = uniformFromSeed(unsignedSeed, 1);
        double u2 = uniformFromSeed(unsignedSeed, 2);
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    // -------------------------------------------------------------------------
    // Epsilon-greedy
    // -------------------------------------------------------------------------

    /**
     * Select an action using the epsilon-greedy algorithm.
     *
     * @param perAction       Per-action reward statistics.
     * @param actionIds       Available action ids; sorted alphabetically internally.
     * @param hashBucket      Pre-computed 0..9999 bucket value.
     * @param explorationRate Exploration fraction. Pass 0 to turn exploration off.
     */
    public static EpsilonGreedyResult epsilonGreedy(
            Map<String, BanditPerActionStats> perAction,
            List<String> actionIds,
            int hashBucket,
            double explorationRate
    ) {
        if (actionIds.isEmpty()) throw new IllegalArgumentException("epsilonGreedy: actions list is empty");

        List<String> sorted = sortedActionIds(actionIds);
        int n = sorted.size();
        double bucketFraction = hashBucket / (double) MAX_BUCKET;
        boolean exploreFired = bucketFraction < explorationRate;

        if (exploreFired) {
            String action = sorted.get(hashBucket % n);
            return new EpsilonGreedyResult(action, explorationRate / n, true);
        }

        // Exploit: highest mean, alphabetical tie-break. Under cold start every mean is
        // zero, so the lowest action id wins, deterministically.
        String bestId = null;
        double bestMean = Double.NEGATIVE_INFINITY;
        int bestCount = 0;
        for (String id : sorted) {
            double mean = statsFor(perAction, id).mean();
            if (mean > bestMean) { bestMean = mean; bestId = id; bestCount = 1; }
            else if (mean == bestMean) { bestCount++; }
        }

        double exploitMass = (1 - explorationRate) / bestCount;
        return new EpsilonGreedyResult(bestId, exploitMass + explorationRate / n, false);
    }

    // -------------------------------------------------------------------------
    // Thompson sampling
    // -------------------------------------------------------------------------

    public static ThompsonSampleResult thompsonSample(
            Map<String, BanditPerActionStats> perAction,
            List<String> actionIds,
            int hashBucket
    ) {
        if (actionIds.isEmpty()) throw new IllegalArgumentException("thompsonSample: actions list is empty");

        List<String> sorted = sortedActionIds(actionIds);
        String bestId = sorted.get(0);
        double bestSample = Double.NEGATIVE_INFINITY;

        for (String id : sorted) {
            BanditPerActionStats stats = statsFor(perAction, id);
            long seed = Integer.toUnsignedLong(Hash.murmurhash3(hashBucket + ":" + id));
            double z = seededGaussian(seed);
            double stddev = Math.sqrt(stats.variance() / Math.max(stats.samples(), 1));
            double sample = stats.mean() + z * stddev;
            if (sample > bestSample) { bestSample = sample; bestId = id; }
        }

        // The true posterior selection probability needs integration over the joint
        // posterior, which is not worth it in the hot path. 1/N is the documented
        // placeholder; analytics recompute offline from the model plus the seed.
        return new ThompsonSampleResult(bestId, 1.0 / sorted.size());
    }

    // -------------------------------------------------------------------------
    // UCB1
    // -------------------------------------------------------------------------

    public static Ucb1Result ucb1(
            Map<String, BanditPerActionStats> perAction,
            List<String> actionIds
    ) {
        if (actionIds.isEmpty()) throw new IllegalArgumentException("ucb1: actions list is empty");

        List<String> sorted = sortedActionIds(actionIds);

        // Cold start: sample every action at least once. With several unseen actions the
        // lowest id wins, so the choice is deterministic.
        for (String id : sorted) {
            if (statsFor(perAction, id).samples() == 0) {
                return new Ucb1Result(id, 1.0);
            }
        }

        long totalSamples = 0;
        for (String id : sorted) totalSamples += statsFor(perAction, id).samples();
        double lnTotal = Math.log(Math.max(totalSamples, 1));

        String bestId = sorted.get(0);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (String id : sorted) {
            BanditPerActionStats stats = statsFor(perAction, id);
            double score = stats.mean() + Math.sqrt(2 * lnTotal / Math.max(stats.samples(), 1));
            if (score > bestScore) { bestScore = score; bestId = id; }
        }

        return new Ucb1Result(bestId, 1.0);
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    /**
     * Run a bandit and return the chosen action, or null when the supplied store has no
     * model for {@code banditKey}.
     *
     * @param banditKey addresses the model store: the shared bandit's key, or the rule id
     *                  for an inline config.
     * @param store     learned models. Use {@link #coldStartStore(Datafile.BanditConfig)}
     *                  when the caller configured none, so the algorithms run their
     *                  documented cold-start policies instead of the rule vanishing.
     */
    public static Decision evaluateBandit(
            String banditKey,
            Datafile.BanditConfig config,
            EvalContext ctx,
            ModelStore store
    ) {
        BanditModel model = store.getModel(banditKey);
        if (model == null) {
            return null;
        }

        List<Datafile.BanditAction> actions = config.actions() == null ? List.of() : config.actions();
        List<String> actionIds = new ArrayList<>(actions.size());
        for (Datafile.BanditAction action : actions) {
            actionIds.add(action.id());
        }

        // Contextual lookup: when the model declares per-bucket maps and this request's
        // bucket exists, that map replaces the top-level statistics.
        String bucketKey = deriveContextBucket(config, ctx);
        Map<String, BanditPerActionStats> perAction = model.perAction() == null
                ? Map.of()
                : model.perAction();
        if (!bucketKey.isEmpty() && model.contextualModels() != null) {
            Map<String, BanditPerActionStats> contextual = model.contextualModels().get(bucketKey);
            if (contextual != null) {
                perAction = contextual;
            }
        }

        int hashBucket = Hash.bucket(banditKey + ":" + Context.bucketingKey(ctx));

        String algorithm = config.algorithm() == null ? "" : config.algorithm();
        String actionId;
        double actionProbability;
        Double optimalityGap;

        switch (algorithm) {
            case "epsilon-greedy" -> {
                double epsilon = config.explorationRate() != null
                        ? config.explorationRate()
                        : DEFAULT_EXPLORATION_RATE;
                EpsilonGreedyResult result = epsilonGreedy(perAction, actionIds, hashBucket, epsilon);
                actionId = result.actionId();
                actionProbability = result.actionProbability();
                optimalityGap = result.exploreFired() ? epsilon : null;
            }
            case "thompson-sampling" -> {
                ThompsonSampleResult result = thompsonSample(perAction, actionIds, hashBucket);
                actionId = result.actionId();
                actionProbability = result.actionProbability();
                optimalityGap = null;
            }
            case "ucb1" -> {
                Ucb1Result result = ucb1(perAction, actionIds);
                actionId = result.actionId();
                actionProbability = result.actionProbability();
                optimalityGap = null;
            }
            default -> throw new IllegalArgumentException(
                    "evaluateBandit: unknown algorithm \"" + algorithm + "\". Known algorithms:"
                            + " epsilon-greedy, thompson-sampling, ucb1.");
        }

        Datafile.BanditAction chosen = null;
        for (Datafile.BanditAction action : actions) {
            if (action.id().equals(actionId)) {
                chosen = action;
                break;
            }
        }
        if (chosen == null) {
            return null;
        }
        return new Decision(chosen, actionProbability, optimalityGap, model.version());
    }

    /**
     * A store that hands back a zero-observation model for any bandit key, so the
     * algorithms run their cold-start policies rather than the rule being skipped. This is
     * what the conformance corpus locks: the same datafile and context must produce the
     * same action in every SDK with no model present.
     */
    public static ModelStore coldStartStore(Datafile.BanditConfig config) {
        BanditModel model = new BanditModel(
                COLD_START_MODEL_VERSION,
                config.algorithm(),
                Map.of(),
                Map.of());
        return new ModelStore() {
            @Override
            public BanditModel getModel(String banditKey) {
                return model;
            }

            @Override
            public void updateModel(String banditKey, BanditModel updated) {
                // Nothing to persist: the cold-start model is derived, not stored.
            }
        };
    }

    /** In-memory model store, for tests and single-process deployments. */
    public static ModelStore inMemoryStore(Map<String, BanditModel> initial) {
        Map<String, BanditModel> models = new LinkedHashMap<>();
        if (initial != null) {
            models.putAll(initial);
        }
        return new ModelStore() {
            @Override
            public synchronized BanditModel getModel(String banditKey) {
                return models.get(banditKey);
            }

            @Override
            public synchronized void updateModel(String banditKey, BanditModel model) {
                models.put(banditKey, model);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Return a stable alphabetically-sorted copy of the action ids. */
    private static List<String> sortedActionIds(List<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        return sorted;
    }

    private static BanditPerActionStats statsFor(
            Map<String, BanditPerActionStats> perAction, String actionId) {
        BanditPerActionStats existing = perAction.get(actionId);
        // Cold-start defaults: zero mean, zero samples, variance 1 so Thompson sampling
        // has a defined scale on an unseen action.
        return existing != null ? existing : new BanditPerActionStats(0, 1, 0);
    }

    /**
     * Derive the contextual-model bucket string: every {@code contextAttributes} key
     * declared by any action, sorted, joined as {@code key=value;key=value}. An empty
     * result means "no contextual lookup, use the top-level statistics".
     */
    private static String deriveContextBucket(Datafile.BanditConfig config, EvalContext ctx) {
        TreeSet<String> keys = new TreeSet<>();
        List<Datafile.BanditAction> actions = config.actions() == null ? List.of() : config.actions();
        for (Datafile.BanditAction action : actions) {
            if (action.contextAttributes() == null) continue;
            keys.addAll(action.contextAttributes().keySet());
        }
        if (keys.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) out.append(';');
            Object value = Context.resolveDottedAttribute(ctx, key);
            out.append(key).append('=');
            if (value instanceof String || value instanceof Number) {
                out.append(JsNumber.stringOf(value));
            }
            // Anything else contributes an empty value, matching the reference, which only
            // stringifies strings and numbers.
        }
        return out.toString();
    }
}
