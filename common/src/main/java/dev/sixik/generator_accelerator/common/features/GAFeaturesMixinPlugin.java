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
                new MixinApplier.Param("dev.sixik.generator_accelerator.common.features.mixin.compats.repurposedstructures.Repurposedstructures$SnapToLowerNonAirPlacementMixin", ""),
                new MixinApplier.Param("dev.sixik.generator_accelerator.common.features.mixin.compats.repurposedstructures.Repurposedstructures$NoVinesInStructuresMixin", "com.telepathicgrunt.repurposedstructures.mixins.features.NoVinesInStructuresMixin")
        );

        create("net.countered.terrainslabs.TerrainSlabs",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.terrainslabs.Mixin$TerrainSlabs$OreFeature",
                        "net.countered.terrainslabs.mixin.feature.MixinOreFeature"
                )
        );

        create("io.wispforest.accessories_compat.curios.wrapper.AccessoriesBasedStackHandler",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.accessories.AccessoriesBasedStackHandlerMixin",
                        ""
                )
        );
        this.create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param(
                "",
                "com.ishland.c2me.opts.allocs.mixin.object_pooling_caching.MixinOreFeature"
        ));


    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableFeaturesPatch;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!super.shouldApplyMixin(targetClassName, mixinClassName)) {
            return false;
        }
        if (Boolean.getBoolean("ga.benchmark.featureVmOnly")) {
            return Boolean.getBoolean("ga.benchmark.featureVm") && isFeatureVmMixin(mixinClassName);
        }
        return true;
    }

    private boolean isFeatureVmMixin(String mixinClassName) {
        String prefix = "dev.sixik.generator_accelerator.common.features.mixin.";
        if (mixinClassName.equals(prefix + "MixinChunkAccess")) {
            return true;
        }
        if (mixinClassName.equals(prefix + "MixinChunkStatusTasks$generate_features")) {
            return true;
        }
        if (mixinClassName.startsWith(prefix + "place.")) {
            return true;
        }
        return mixinClassName.startsWith(prefix + "compats.artifacts.")
                || mixinClassName.startsWith(prefix + "compats.confluence.")
                || mixinClassName.startsWith(prefix + "compats.oreberries.")
                || mixinClassName.startsWith(prefix + "compats.repurposedstructures.")
                || mixinClassName.startsWith(prefix + "compats.roots.")
                || mixinClassName.startsWith(prefix + "compats.waystones.")
                || mixinClassName.startsWith(prefix + "compats.yungs.");
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
