package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$CarverChunkCache;
import dev.sixik.generator_accelerator.common.carver.CarverChunkPlan;
import dev.sixik.generator_accelerator.common.carver.CarverChunkPlanCacheEntry;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(ProtoChunk.class)
public class MixinProtoChunk$carver_plan_cache implements GA$CarverChunkCache {

    @Unique
    private volatile AtomicReferenceArray<CarverChunkPlanCacheEntry> ga$carverChunkPlanCache;

    @Override
    public @Nullable CarverChunkPlan ga$getCarverChunkPlan(GenerationStep.Carving step, long seed) {
        int index = step.ordinal();
        CarverChunkPlanCacheEntry entry = this.ga$getCarverPlanCache().get(index);
        if (entry == null || entry.seed() != seed) {
            return null;
        }

        return entry.plan();
    }

    @Override
    public void ga$setCarverChunkPlan(GenerationStep.Carving step, long seed, CarverChunkPlan plan) {
        int index = step.ordinal();
        this.ga$getCarverPlanCache().set(index, new CarverChunkPlanCacheEntry(seed, plan));
    }

    @Unique
    private AtomicReferenceArray<CarverChunkPlanCacheEntry> ga$getCarverPlanCache() {
        AtomicReferenceArray<CarverChunkPlanCacheEntry> cache = this.ga$carverChunkPlanCache;
        if (cache != null) {
            return cache;
        }

        synchronized (this) {
            cache = this.ga$carverChunkPlanCache;
            if (cache == null) {
                cache = new AtomicReferenceArray<>(GenerationStep.Carving.values().length);
                this.ga$carverChunkPlanCache = cache;
            }
            return cache;
        }
    }
}
