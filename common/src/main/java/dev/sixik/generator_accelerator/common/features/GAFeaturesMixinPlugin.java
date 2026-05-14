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

        create("biomesoplenty.core.BiomesOPlenty", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.biomesoplenty.BiomesOPlenty$WebbingFeatureMixin",
                ""));

        create("net.potionstudios.biomeswevegone.BiomesWeveGone",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.biomeswevegone.BiomesWeveGone$BasaltBarreraExtension$fast",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.biomeswevegone.BiomesWeveGone$BlendUtil$fast_edge",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.biomeswevegone.BiomesWeveGone$ChunkStatusTasksMixin$cache_biomes",
                        "net.potionstudios.biomeswevegone.mixin.ChunkStatusTasksMixin"),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.biomeswevegone.BiomesWeveGone$CragGardenExtension$fast",
                        ""));

        create("com.dtteam.dynamictrees.DynamicTrees",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$BasicBranchBlockMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$CaveRootedTreePlacementMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$CaveRootedTreeFeatureMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$CellKits$BasicSolverMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$ChunkTreeHelperMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$DynamicLeavesBlockMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$DynamicTreeFeatureMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$InflatorNodeMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$JoCodeMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$OverworldGroundFinderMixin",
                        ""),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees.DynamicTrees$SubterraneanGroundFinderMixin",
                        ""));

        create("net.orcinus.galosphere.Galosphere", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.galosphere.Galosphere$CrystalSpikeFeatureMixin",
                ""));

        create("com.github.alexmodguy.alexscaves.AlexsCaves", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.features.mixin.compats.alexscaves.AlexsCaves$ChunkGeneratorMixin$applyBiomeDecoration",
                "com.github.alexmodguy.alexscaves.mixin.ChunkGeneratorMixin"
        ));

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
        if (mixinClassName.equals("dev.sixik.generator_accelerator.common.features.mixin.features.MixinTreeFeature")) {
            return Boolean.getBoolean("ga.features.fastTreeFeature.enabled");
        }
        if (Boolean.getBoolean("ga.benchmark.featureVmOnly")) {
            return Boolean.getBoolean("ga.benchmark.featureVm") && isFeatureVmMixin(mixinClassName);
        }
        return true;
    }

    private boolean isFeatureVmMixin(String mixinClassName) {
        String prefix = "dev.sixik.generator_accelerator.common.features.mixin.";
        if (mixinClassName.equals(prefix + "MixinChunkAccess")
                || mixinClassName.equals(prefix + "MixinChunkAccess$fast_block_light_sources")) {
            return true;
        }
        if (mixinClassName.equals(prefix + "MixinChunkStatusTasks$generate_features")) {
            return true;
        }
        if (mixinClassName.startsWith(prefix + "place.")) {
            return true;
        }
        return mixinClassName.startsWith(prefix + "compats.alexscaves.")
                || mixinClassName.startsWith(prefix + "compats.artifacts.")
                || mixinClassName.startsWith(prefix + "compats.biomeswevegone.")
                || mixinClassName.startsWith(prefix + "compats.biomesoplenty.")
                || mixinClassName.startsWith(prefix + "compats.confluence.")
                || mixinClassName.startsWith(prefix + "compats.galosphere.")
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
