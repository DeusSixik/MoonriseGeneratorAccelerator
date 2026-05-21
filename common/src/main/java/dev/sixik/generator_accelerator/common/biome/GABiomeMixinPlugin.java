package dev.sixik.generator_accelerator.common.biome;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GABiomeMixinPlugin extends GAMixinPlugin {
    @Override
    public void onLoad(String s) {
        create("terrablender.core.TerraBlender",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$MixinParameterList$redirect_search",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$MixinNoiseBasedChunkGenerator$reuse_uniqueness",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$MixinLevelChunkSection$raw_biome_lookup",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$MixinMultiNoiseBiomeSource$raw_biome_resolver",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$MixinBiomeSource$cache_possible_biomes",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$Area$lock_free_cache",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$AreaContext$thread_local_random",
                        ""
                )
        );
        create("com.terraformersmc.biolith.impl.Biolith",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith.Biolith$MixinSearchTree$fast",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith.Biolith$SubBiomeRequestAccessor",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith.Biolith$SubBiomeRequestSet$fast_iter",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith.Biolith$AnyOfCriterion$fast_iter",
                        ""
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith.Biolith$AllOfCriterion$fast_iter",
                        ""
                )
        );
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableBiomePatch;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!super.shouldApplyMixin(targetClassName, mixinClassName)) {
            return false;
        }
        if (mixinClassName.equals("dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$MixinLevelChunkSection$raw_biome_lookup")) {
            return Boolean.parseBoolean(System.getProperty("ga.terrablender.rawBiomeLookup", "true"));
        }
        if (mixinClassName.equals("dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$MixinMultiNoiseBiomeSource$raw_biome_resolver")) {
            return Boolean.parseBoolean(System.getProperty("ga.terrablender.rawBiomeLookup", "true"));
        }
        if (mixinClassName.equals("dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$Area$lock_free_cache")) {
            return Boolean.parseBoolean(System.getProperty("ga.terrablender.lockFreeAreaCache", "true"))
                    && Boolean.parseBoolean(System.getProperty("ga.terrablender.threadLocalAreaContext", "true"));
        }
        if (mixinClassName.equals("dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender.Terrablender$AreaContext$thread_local_random")) {
            return Boolean.parseBoolean(System.getProperty("ga.terrablender.threadLocalAreaContext", "true"));
        }
        if (mixinClassName.equals("dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith.Biolith$SubBiomeRequestSet$fast_iter")) {
            return Boolean.parseBoolean(System.getProperty("ga.biolith.fastSubBiomeSelect", "true"));
        }
        if (mixinClassName.equals("dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith.Biolith$AnyOfCriterion$fast_iter")
                || mixinClassName.equals("dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith.Biolith$AllOfCriterion$fast_iter")) {
            return Boolean.parseBoolean(System.getProperty("ga.biolith.fastCriterionIter", "true"));
        }
        return true;
    }
}
