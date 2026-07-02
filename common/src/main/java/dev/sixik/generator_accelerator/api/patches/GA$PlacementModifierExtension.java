package dev.sixik.generator_accelerator.api.patches;

import dev.sixik.generator_accelerator.api.exceptions.MethodNotImplementedException;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

public interface GA$PlacementModifierExtension {
    ThreadLocal<LongArrayList> GA$LEGACY_OUTPUT = ThreadLocal.withInitial(LongArrayList::new);

    static GA$PlacementModifierExtension get(PlacementModifier modifier) {
        return (GA$PlacementModifierExtension) modifier;
    }

    default boolean ga$hasFastPositions() {
        return false;
    }

    /**
     * Data-oriented position generation used by the Feature Placement VM.
     */
    default void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        LongArrayList legacyOutput = GA$LEGACY_OUTPUT.get();
        legacyOutput.clear();
        try {
            generatePositionsFast(context, random, packedPos, legacyOutput);
            for (int i = 0; i < legacyOutput.size(); i++) {
                output.add(legacyOutput.getLong(i));
            }
        } finally {
            if (legacyOutput.elements().length > 131_072) {
                legacyOutput.size(0);
                legacyOutput.trim(16_384);
            }
            legacyOutput.clear();
        }
    }

    /**
     * Legacy bridge kept for old GA adapters that have not moved to raw VM buffers yet.
     */
    default void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        throw new MethodNotImplementedException(getClass(), "generatePositionsFast(PlacementContext, RandomSource, long, LongArrayList output)");
    }
}
