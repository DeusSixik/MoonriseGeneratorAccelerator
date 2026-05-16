package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.common.worldgen.parallel.GAChunkStatusPipeline;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator$parallel_create_biomes {
    @Redirect(
            method = "createBiomes",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private <T> CompletableFuture<T> ga$createBiomesOnGaLane(Supplier<T> supplier, Executor executor) {
        if (!GAChunkStatusPipeline.biomesEnabled()) {
            return CompletableFuture.supplyAsync(supplier, executor);
        }
        if (GAChunkStatusPipeline.inlineOnCurrentLane()) {
            return GAChunkStatusPipeline.supplyInlineOnCurrentLane(supplier);
        }
        return GAScheduler.supplyAsync(GAScheduler.Lane.WORKSPACE, supplier);
    }
}
