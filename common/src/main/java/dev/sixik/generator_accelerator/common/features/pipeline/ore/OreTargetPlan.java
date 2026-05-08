package dev.sixik.generator_accelerator.common.features.pipeline.ore;

import dev.sixik.generator_accelerator.common.features.FastTarget;

public record OreTargetPlan(
        FastTarget[] targets,
        boolean hasFallbackTargets,
        boolean placementMayBeAir
) {

    public static final OreTargetPlan EMPTY = new OreTargetPlan(new FastTarget[0], false, false);
}
