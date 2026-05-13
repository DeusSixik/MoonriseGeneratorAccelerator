package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$CarverChunkCache;
import dev.sixik.generator_accelerator.common.carver.CarverChunkPlan;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ProtoChunk.class)
public class MixinProtoChunk$carver_plan_cache implements GA$CarverChunkCache {

    @Unique
    private volatile CarverChunkPlan[] ga$carverChunkPlans;

    @Unique
    private long[] ga$carverChunkPlanSeeds;

    @Unique
    private boolean[] ga$carverChunkPlanValid;

    @Override
    public @Nullable CarverChunkPlan ga$getCarverChunkPlan(GenerationStep.Carving step, long seed) {
        this.ga$ensureCarverPlanCache();
        int index = step.ordinal();
        if (!this.ga$carverChunkPlanValid[index] || this.ga$carverChunkPlanSeeds[index] != seed) {
            return null;
        }

        return this.ga$carverChunkPlans[index];
    }

    @Override
    public void ga$setCarverChunkPlan(GenerationStep.Carving step, long seed, CarverChunkPlan plan) {
        this.ga$ensureCarverPlanCache();
        int index = step.ordinal();
        this.ga$carverChunkPlans[index] = plan;
        this.ga$carverChunkPlanSeeds[index] = seed;
        this.ga$carverChunkPlanValid[index] = true;
    }

    @Unique
    private void ga$ensureCarverPlanCache() {
        if (this.ga$carverChunkPlans != null) {
            return;
        }

        int size = GenerationStep.Carving.values().length;
        CarverChunkPlan[] plans = new CarverChunkPlan[size];
        long[] seeds = new long[size];
        boolean[] valid = new boolean[size];
        this.ga$carverChunkPlanSeeds = seeds;
        this.ga$carverChunkPlanValid = valid;
        this.ga$carverChunkPlans = plans;
    }
}
