package dev.sixik.generator_accelerator.common.features;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;

public record FastTarget(
        int singleStateId,
        int[] validStateIds,
        boolean[] validStateIdMask,
        RuleTest fallbackRule,
        BlockState placementState,
        int placementStateId
) {

    public boolean matchesStateId(int currentStateId) {
        if (this.singleStateId >= 0) {
            return currentStateId == this.singleStateId;
        }

        if (this.validStateIdMask != null) {
            return currentStateId >= 0
                    && currentStateId < this.validStateIdMask.length
                    && this.validStateIdMask[currentStateId];
        }

        if (this.validStateIds != null) {
            for (int i = 0; i < this.validStateIds.length; i++) {
                if (currentStateId == this.validStateIds[i]) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean requiresFallbackState() {
        return this.fallbackRule != null;
    }
}
