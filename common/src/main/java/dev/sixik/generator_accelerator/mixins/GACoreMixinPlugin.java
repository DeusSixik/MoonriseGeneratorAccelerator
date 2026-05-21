package dev.sixik.generator_accelerator.mixins;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GACoreMixinPlugin extends GAMixinPlugin {
    private static final String LITHIUM = "net.caffeinemc.mods.lithium.common.LithiumMod";
    private static final String LITHIUM_FLOWING_FLUID = "net.caffeinemc.mods.lithium.mixin.block.fluid.flow.FlowingFluidMixin";
    private static final String[] C2ME_WORLDGEN_GENERAL_RANDOM_MIXINS = {
            "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinAtomicSimpleRandomFactory",
            "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinRedirectAtomicSimpleRandom",
            "com.ishland.c2me.opts.worldgen.general.mixin.random_instances.MixinRedirectAtomicSimpleRandomStatic"
    };
    private static final String[] C2ME_NOISE_MATH_MIXINS = {
            "com.ishland.c2me.opts.math.mixin.MixinChunkNoiseSampler",
            "com.ishland.c2me.opts.math.mixin.MixinOctavePerlinNoiseSampler",
            "com.ishland.c2me.opts.math.mixin.MixinPerlinNoiseSampler",
            "com.ishland.c2me.opts.natives_math.mixin.df.MixinDFTNoise",
            "com.ishland.c2me.opts.natives_math.mixin.df.MixinDFTShift",
            "com.ishland.c2me.opts.natives_math.mixin.df.MixinDFTShiftA",
            "com.ishland.c2me.opts.natives_math.mixin.df.MixinDFTShiftB",
            "com.ishland.c2me.opts.natives_math.mixin.df.MixinBiomeAccess",
            "com.ishland.c2me.opts.natives_math.mixin.df.MixinDFTypesEndIslands",
            "com.ishland.c2me.opts.natives_math.mixin.df.MixinDoublePerlinNoiseSampler",
            "com.ishland.c2me.opts.natives_math.mixin.df.MixinInterpolatedNoiseSampler"
    };
    private static final String[] C2ME_DFC_MIXINS = {
            "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSampler",
            "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSampler1",
            "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerCache2D",
            "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerCacheOnce",
            "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerCellCache",
            "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerDensityInterpolator",
            "com.ishland.c2me.opts.dfc.mixin.MixinChunkNoiseSamplerFlatCache",
            "com.ishland.c2me.opts.dfc.mixin.MixinDFTBinaryOperation",
            "com.ishland.c2me.opts.dfc.mixin.MixinDFTWrapping",
            "com.ishland.c2me.opts.dfc.mixin.MixinNoiseConfig",
            "com.ishland.c2me.opts.dfc.mixin.MixinSplineImplementation"
    };
    private static final String[] C2ME_WORLDGEN_VANILLA_MIXINS = {
            "com.ishland.c2me.opts.worldgen.vanilla.mixin.aquifer.MixinAquiferSamplerImpl",
            "com.ishland.c2me.opts.worldgen.vanilla.mixin.structure_weight_sampler.MixinStructureWeightSampler"
    };
    private static final String[] C2ME_BLEND_AND_STRUCTURE_MIXINS = {
            "com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization.MixinBlender",
            "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading.MixinStructure",
            "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading.MixinStructurePalettedBlockInfoList",
            "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading.MixinStructureChecker"
    };

    @Override
    public void onLoad(String mixinPackage) {
        // Register all C2ME worldgen cancellations from the core plugin so Mixinsquared
        // sees them before C2ME's own configs apply. Late registration in feature-local
        // plugins is not sufficient for constructor/injector conflicts on NoiseChunk.
        create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param("", C2ME_WORLDGEN_GENERAL_RANDOM_MIXINS));
        create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param("", C2ME_NOISE_MATH_MIXINS));
        create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param("", C2ME_DFC_MIXINS));
        create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param("", C2ME_WORLDGEN_VANILLA_MIXINS));
        create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param("", C2ME_BLEND_AND_STRUCTURE_MIXINS));

        // GA overwrites FlowingFluid's spread/occlusion hot path. Lithium injects into
        // the same methods, so keep Lithium's unrelated patches and drop only this one.
        create(LITHIUM, new MixinApplier.Param("", LITHIUM_FLOWING_FLUID));
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }
}
