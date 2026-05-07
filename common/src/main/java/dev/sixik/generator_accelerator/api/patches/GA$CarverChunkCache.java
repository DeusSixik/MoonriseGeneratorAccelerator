package dev.sixik.generator_accelerator.api.patches;

import dev.sixik.generator_accelerator.common.carver.CarverChunkPlan;
import net.minecraft.world.level.levelgen.GenerationStep;
import org.jetbrains.annotations.Nullable;

public interface GA$CarverChunkCache {

    @Nullable
    CarverChunkPlan ga$getCarverChunkPlan(GenerationStep.Carving step, long seed);

    void ga$setCarverChunkPlan(GenerationStep.Carving step, long seed, CarverChunkPlan plan);
}
