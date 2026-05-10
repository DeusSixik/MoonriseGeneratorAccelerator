package dev.sixik.generator_accelerator.common.density.compiler.mixin;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.compat.FabricBiomeApiClimateRebind;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.pipeline.RouterPipeline;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RandomState.class, priority = 2000)
public abstract class RandomStateMixin {

    @Mutable
    @Shadow @Final
    private NoiseRouter router;

    @Mutable
    @Shadow @Final
    private Climate.Sampler sampler;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dfc$compileWiredRouter(NoiseGeneratorSettings settings,
                                        HolderGetter<NormalNoise.NoiseParameters> noises,
                                        long levelSeed,
                                        CallbackInfo ci) {
        if (DensityFunctionCompiler.isModLoaded("dfc_c2me")) {
            return;
        }

        long start = System.nanoTime();
        NoiseRouter wiredRouter = this.router;
        Climate.Sampler wiredSampler = this.sampler;

        NoiseRouter compiledRouter;
        try {
            compiledRouter = RouterPipeline.compile(wiredRouter);
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "RouterPipeline.compile threw for wired router (settings={}); leaving vanilla router in place",
                    settings, t);
            compiledRouter = wiredRouter;
        }

        Climate.Sampler compiledSampler;
        try {
            compiledSampler = RouterPipeline.compileSampler(wiredSampler);
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "RouterPipeline.compileSampler threw for wired sampler (settings={}); leaving vanilla sampler in place",
                    settings, t);
            compiledSampler = wiredSampler;
        }

        this.router = compiledRouter;
        this.sampler = compiledSampler;
        FabricBiomeApiClimateRebind.propagateToCompiledSampler(wiredSampler, compiledSampler, levelSeed);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        DensityFunctionCompiler.LOGGER.info(
                "DFC compiled NoiseRouter + Climate.Sampler for RandomState(seed={}) in {}ms",
                Long.toHexString(levelSeed), elapsedMs);
    }
}
