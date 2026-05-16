package dev.sixik.generator_accelerator.common.worldgen.optimizer;

public final class WorldgenParitySampler {
    public WorldgenOptimizerDecision evaluate(WorldgenGeneratedPlan plan, WorldgenParitySample sample) {
        if (sample == null || sample.matched()) {
            WorldgenOptimizerMetrics.recordParityMatch();
            return WorldgenOptimizerDecision.admit(plan);
        }
        WorldgenOptimizerMetrics.recordParityMismatch();
        return WorldgenOptimizerDecision.deopt(plan, WorldgenDeoptReason.PARITY_MISMATCH,
                "parity mismatch for " + sample.unitId());
    }
}
