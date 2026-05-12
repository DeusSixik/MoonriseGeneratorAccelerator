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
        return true;
    }
}
