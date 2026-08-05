package com.avsbhq.avsb.core;

import com.avsbhq.avsb.ExposureEvent;

import java.util.function.Consumer;

/**
 * Optional inputs to {@link Evaluator#evaluateFlag}.
 *
 * <p>Kept as one value rather than more positional parameters so adding an input later
 * cannot silently reorder an existing call.
 *
 * @param onExposure        Called once per served ab_test, bandit, or holdout decision.
 *                          Null suppresses exposure recording, which is what
 *                          {@code DISABLE_EXPOSURE} callers and preview evaluations want.
 * @param banditModelStore  Learned bandit models. Null means the evaluator builds a
 *                          deterministic cold-start model from the datafile's bandit config
 *                          alone, so a bandit rule dispatches instead of disappearing.
 */
public record EvaluateOptions(
        Consumer<ExposureEvent> onExposure,
        Bandits.ModelStore banditModelStore
) {

    /** No exposure recording and no learned models. */
    public static final EvaluateOptions NONE = new EvaluateOptions(null, null);

    /** Record exposures, cold-start bandits. */
    public static EvaluateOptions withExposure(Consumer<ExposureEvent> onExposure) {
        return new EvaluateOptions(onExposure, null);
    }

    /** Record exposures against learned bandit models. */
    public static EvaluateOptions of(
            Consumer<ExposureEvent> onExposure, Bandits.ModelStore banditModelStore) {
        return new EvaluateOptions(onExposure, banditModelStore);
    }
}
