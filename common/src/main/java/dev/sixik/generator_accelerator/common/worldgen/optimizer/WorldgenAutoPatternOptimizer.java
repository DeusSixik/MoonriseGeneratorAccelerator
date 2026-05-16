package dev.sixik.generator_accelerator.common.worldgen.optimizer;

import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenUnitProfile;

import java.util.Objects;
import java.util.Optional;

public final class WorldgenAutoPatternOptimizer {
    private final WorldgenPatternRecognizer recognizer;
    private final WorldgenOptimizerGuards guards;
    private final WorldgenParitySampler paritySampler;

    public WorldgenAutoPatternOptimizer() {
        this(new WorldgenPatternRecognizer(), new WorldgenOptimizerGuards(), new WorldgenParitySampler());
    }

    public WorldgenAutoPatternOptimizer(
            WorldgenPatternRecognizer recognizer,
            WorldgenOptimizerGuards guards,
            WorldgenParitySampler paritySampler
    ) {
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.guards = Objects.requireNonNull(guards, "guards");
        this.paritySampler = Objects.requireNonNull(paritySampler, "paritySampler");
    }

    public Optional<WorldgenGeneratedPlan> plan(WorldgenUnitProfile profile) {
        Optional<WorldgenGeneratedPlan> plan = recognizer.recognize(profile);
        if (plan.isPresent()) {
            WorldgenOptimizerMetrics.recordRecognized(plan.get(), profile.namespace());
        } else {
            WorldgenDeoptReason reason = profile != null && recognizer.hasHardUnsafe(profile)
                    ? WorldgenDeoptReason.UNSAFE_EFFECT
                    : WorldgenDeoptReason.UNRECOGNIZED_PATTERN;
            WorldgenOptimizerMetrics.recordFallback(reason, reason.name());
        }
        return plan;
    }

    public WorldgenOptimizerDecision admit(WorldgenGeneratedPlan plan, WorldgenUnitProfile currentProfile) {
        WorldgenOptimizerDecision decision = guards.verify(plan, currentProfile);
        recordDecision(decision);
        return decision;
    }

    public WorldgenOptimizerDecision parity(WorldgenGeneratedPlan plan, WorldgenParitySample sample) {
        WorldgenOptimizerDecision decision = paritySampler.evaluate(plan, sample);
        recordDecision(decision);
        return decision;
    }

    public WorldgenOptimizerDecision deopt(WorldgenGeneratedPlan plan, WorldgenDeoptReason reason, String message) {
        WorldgenOptimizerDecision decision = WorldgenOptimizerDecision.deopt(plan, reason, message);
        recordDecision(decision);
        return decision;
    }

    public static void recordDecision(WorldgenOptimizerDecision decision) {
        if (decision == null) return;
        if (decision.action() == WorldgenOptimizerAction.DEOPT) {
            WorldgenOptimizerMetrics.recordDeopt(decision.deoptReason(), decision.reason());
        } else if (decision.action() == WorldgenOptimizerAction.FALLBACK) {
            WorldgenOptimizerMetrics.recordFallback(decision.deoptReason(), decision.reason());
        }
    }
}
