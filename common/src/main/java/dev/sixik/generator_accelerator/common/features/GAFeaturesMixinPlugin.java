package dev.sixik.generator_accelerator.common.features;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAFeaturesMixinPlugin extends GAMixinPlugin {

    @Override
    public void onLoad(String s) {
        GeneratorAccelerator.platform = isLoaded("net.fabricmc.api.ModInitializer") ? GeneratorAccelerator.Platform.FABRIC : GeneratorAccelerator.Platform.NEOFORGE;

        create("org.confluence.mod.Confluence",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.confluence.Confluence$FeaturePlacementCompatMixin",
                        ""
                ),
                new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.confluence.Confluence$PlacedFeatureMixin$fix",
                "org.confluence.mod.mixin.level.PlacedFeatureMixin"
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.confluence.Confluence$SecretFlagPlacementMixin",
                        ""
                )
        );

        create("io.wispforest.owo.Owo", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.owo.Mixin$OWO$OreFeature",
                "io.wispforest.owo.mixin.Copenhagen"
        ));

        create("artifacts.Artifacts", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.artifacts.Artifacts$CampsiteHeightRangePlacementMixin",
                ""));

        create("net.blay09.mods.waystones.Waystones", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.waystones.Waystones$WaystonePlacementMixin",
                ""));

        create("com.telepathicgrunt.repurposedstructures.RepurposedStructures",
                new MixinApplier.Param("dev.sixik.generator_accelerator.common.features.mixin.compats.repurposedstructures.Repurposedstructures$MinDistanceFromWorldOriginPlacementMixin", ""),
                new MixinApplier.Param("dev.sixik.generator_accelerator.common.features.mixin.compats.repurposedstructures.Repurposedstructures$MinusEightPlacementMixin", ""),
                new MixinApplier.Param("dev.sixik.generator_accelerator.common.features.mixin.compats.repurposedstructures.Repurposedstructures$SnapToLowerNonAirPlacementMixin", "")
        );

        create("net.countered.terrainslabs.TerrainSlabs",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.terrainslabs.Mixin$TerrainSlabs$OreFeature",
                        "net.countered.terrainslabs.mixin.feature.MixinOreFeature"
                )
        );
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableFeaturesPatch;
    }

    private boolean isLoaded(String modClassPath) {
        if(modClassPath.isEmpty()) return true;

        try {
            Class.forName(modClassPath, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
    }
}
