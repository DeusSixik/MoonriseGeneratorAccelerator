package dev.sixik.generator_accelerator.common.surface.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sixik.generator_accelerator.common.surface_compiler.SurfaceMetrics;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionPlan;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceRuntime;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystem$new_build_surface {
    @WrapMethod(method = "buildSurface")
    private void ga$buildSurface(
            RandomState randomState,
            BiomeManager biomeManager,
            Registry<Biome> biomeRegistry,
            boolean useLegacyRandomSource,
            WorldGenerationContext context,
            ChunkAccess chunk,
            NoiseChunk noiseChunk,
            SurfaceRules.RuleSource ruleSource,
            Operation<Void> original
    ) {
        SurfaceExecutionPlan plan = SurfaceRuntime.prepare(ruleSource);

        if (plan.useVanillaCleanPath()) {
            long vanillaStart = SurfaceMetrics.startTimer();
            original.call(randomState, biomeManager, biomeRegistry, useLegacyRandomSource, context, chunk, noiseChunk, ruleSource);
            SurfaceMetrics.vanillaExecution(vanillaStart);
            return;
        }

        if (SurfaceRuntime.execute(plan, (SurfaceSystem) (Object) this, randomState, biomeManager, biomeRegistry, useLegacyRandomSource, context, chunk, noiseChunk, ruleSource)) {
            return;
        }

        long vanillaStart = SurfaceMetrics.startTimer();
        original.call(randomState, biomeManager, biomeRegistry, useLegacyRandomSource, context, chunk, noiseChunk, ruleSource);
        SurfaceMetrics.vanillaExecution(vanillaStart);
    }
}
