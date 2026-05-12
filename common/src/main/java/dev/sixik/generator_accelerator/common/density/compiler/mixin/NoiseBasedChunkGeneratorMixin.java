package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Debug mixin.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseBasedChunkGeneratorMixin {
    private static final boolean GA$OVERRIDE_NOISE_EXECUTOR =
            Boolean.getBoolean("ga.scheduler.overrideNoiseExecutor");

    @Redirect(method = "fillFromNoise", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    public <U> CompletableFuture<U> bts$fillFromNoise(Supplier<U> supplier, Executor executor) {
        if (!GA$OVERRIDE_NOISE_EXECUTOR) {
            return CompletableFuture.supplyAsync(supplier, executor);
        }
        return GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, supplier);
    }
}
