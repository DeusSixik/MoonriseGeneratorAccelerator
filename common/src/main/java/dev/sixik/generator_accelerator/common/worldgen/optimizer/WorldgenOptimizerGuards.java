package dev.sixik.generator_accelerator.common.worldgen.optimizer;

import dev.sixik.generator_accelerator.common.worldgen.profile.WorldgenUnitProfile;

import java.util.Objects;

public final class WorldgenOptimizerGuards {
    public WorldgenOptimizerDecision verify(WorldgenGeneratedPlan plan, WorldgenUnitProfile currentProfile) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(currentProfile, "currentProfile");
        for (WorldgenOptimizerGuard guard : plan.guards()) {
            if (!guard.matches(actualValue(currentProfile, guard.name()))) {
                return WorldgenOptimizerDecision.deopt(plan, WorldgenDeoptReason.GUARD_MISMATCH,
                        "guard mismatch: " + guard.name());
            }
        }
        return WorldgenOptimizerDecision.admit(plan);
    }

    private static String actualValue(WorldgenUnitProfile profile, String name) {
        return switch (name) {
            case "className" -> profile.className();
            case "bytecodeHash" -> profile.bytecodeHash();
            case "configHash" -> profile.configHash();
            case "registryEpoch" -> Long.toString(profile.registryEpoch());
            case "entryPointMethod" -> profile.entryPointMethod();
            default -> "";
        };
    }
}
