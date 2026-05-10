package dev.sixik.generator_accelerator.common.features.pipeline.ore;

import dev.sixik.generator_accelerator.common.features.FastTarget;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

public final class OreTargetMatcher {

    private OreTargetMatcher() {
    }

    public static boolean matches(FastTarget target, int currentStateId) {
        return target.matchesStateId(currentStateId);
    }

    public static boolean matches(FastTarget target, int currentStateId, BlockState currentState, RandomSource random) {
        if (target.matchesStateId(currentStateId)) {
            return true;
        }
        return target.requiresFallbackState()
                && currentState != null
                && random != null
                && target.fallbackRule().test(currentState, random);
    }

    public static int firstMatch(FastTarget[] targets, int currentStateId) {
        for (int i = 0; i < targets.length; i++) {
            if (targets[i].matchesStateId(currentStateId)) {
                return i;
            }
        }
        return -1;
    }

    public static int firstMatch(FastTarget[] targets, int currentStateId, BlockState currentState, RandomSource random) {
        for (int i = 0; i < targets.length; i++) {
            if (matches(targets[i], currentStateId, currentState, random)) {
                return i;
            }
        }
        return -1;
    }
}
