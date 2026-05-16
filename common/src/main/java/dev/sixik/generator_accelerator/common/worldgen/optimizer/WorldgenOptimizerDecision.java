package dev.sixik.generator_accelerator.common.worldgen.optimizer;

public record WorldgenOptimizerDecision(
        WorldgenOptimizerAction action,
        WorldgenGeneratedPlan plan,
        WorldgenDeoptReason deoptReason,
        String reason
) {
    public WorldgenOptimizerDecision {
        action = action == null ? WorldgenOptimizerAction.FALLBACK : action;
        deoptReason = deoptReason == null ? WorldgenDeoptReason.NONE : deoptReason;
        reason = reason == null ? "" : reason;
    }

    public static WorldgenOptimizerDecision admit(WorldgenGeneratedPlan plan) {
        return new WorldgenOptimizerDecision(WorldgenOptimizerAction.ADMIT_FAST_PATH, plan, WorldgenDeoptReason.NONE,
                plan == null ? "" : plan.reason());
    }

    public static WorldgenOptimizerDecision fallback(WorldgenDeoptReason reason, String message) {
        return new WorldgenOptimizerDecision(WorldgenOptimizerAction.FALLBACK, null, reason, message);
    }

    public static WorldgenOptimizerDecision deopt(WorldgenGeneratedPlan plan, WorldgenDeoptReason reason, String message) {
        return new WorldgenOptimizerDecision(WorldgenOptimizerAction.DEOPT, plan, reason, message);
    }
}
