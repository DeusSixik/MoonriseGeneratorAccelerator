package dev.sixik.generator_accelerator.mixins;

import com.mojang.logging.LogUtils;
import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.common.surface.GASurfaceMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;
import org.slf4j.Logger;

import java.util.Arrays;

public class GAMainMixinPlugin extends GAMixinPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MODERN_FIX_ENTRY = "org.embeddedt.modernfix.ModernFix";

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }

    @Override
    public void onLoad(String s) {
        GeneratorAccelerator.platform = isLoaded("net.fabricmc.api.ModInitializer") ? GeneratorAccelerator.Platform.FABRIC : GeneratorAccelerator.Platform.NEOFORGE;
        create("terrablender.core.TerraBlender", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.mixins.common_mixin.biome.compat.terrablender.Terrablender$MixinParameterList$redirect_search",
                ""
        ));


        create("org.confluence.mod.Confluence",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.confluence.Confluence$PlacedFeatureMixin$fix",
                        "org.confluence.mod.mixin.level.PlacedFeatureMixin"
                ),
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.confluence.Confluence$SecretFlagPlacementMixin",
                        ""
                )
        );


        create("io.wispforest.owo.Owo", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.owo.Mixin$OWO$OreFeature",
                "io.wispforest.owo.mixin.Copenhagen"
        ));

        String[] mixins = new String[] {
                "dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.bitstorages.MixinSimpleBitStorage",
                "dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.bitstorages.ZeroBitStorage",
                "dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch.MixinCrudeIncrementalIntIdentityHashBiMap",
                "dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch.MixinHashMapPalette",
                "dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch.MixinLinearPalette",
                "dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch.MixinPalette",
                "dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch.MixinPalettedContainer",
                "dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch.MixinPalettedContainer$Data",
                "dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch.MixinSingleValuePalette"
        };

        MixinApplier.Param[] array = Arrays.stream(mixins).map((value) -> new MixinApplier.Param("", value)).toArray(MixinApplier.Param[]::new);
        create("ca.spottedleaf.moonrise.common.PlatformHooks", array);

        create("me.jellysquid.mods.lithium.common.LithiumMod",
                new MixinApplier.Param(
                        "",
                        "me.jellysquid.mods.lithium.mixin.chunk.no_validation.EmptyPaletteStorageMixin"
                ),
                new MixinApplier.Param(
                        "",
                        "me.jellysquid.mods.lithium.mixin.chunk.no_validation.PackedIntegerArrayMixin"
                )
        );

        create("org.violetmoon.zeta.Zeta", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.mixins.common_mixin.structures.compats.zeta.Zeta$StructurePiece$Fix",
                "org.violetmoon.zeta.mixin.mixins.StructurePieceMixin"
        ));

        create("net.hibiscus.naturespirit.NatureSpirit", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.mixins.common_mixin.surface.compats.natures_spirit.NaturesSpirit$SurfaceBuilderMixin$fix_compat",
                "net.hibiscus.naturespirit.mixin.SurfaceBuilderMixin"
        ));

        create("net.potionstudios.biomeswevegone.BiomesWeveGone", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.mixins.common_mixin.surface.compats.biomeswevegone.BiomesWeveGone$SurfaceBuilder$fix_compat",
                "net.potionstudios.biomeswevegone.mixin.SurfaceSystemMixin"
        ));

        create("com.teamabnormals.blueprint.core.Blueprint", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.mixins.common_mixin.surface.compats.blueprints.Blueprint$StructurePieceMixin",
                "com.teamabnormals.blueprint.core.mixin.StructurePieceMixin"
        ));

        create("artifacts.Artifacts", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.artifacts.Artifacts$CampsiteHeightRangePlacementMixin",
                ""));

        create("net.blay09.mods.waystones.Waystones", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.waystones.Waystones$WaystonePlacementMixin",
                ""));

        create("com.telepathicgrunt.repurposedstructures.RepurposedStructures",
                new MixinApplier.Param("dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.repurposedstructures.Repurposedstructures$MinDistanceFromWorldOriginPlacementMixin", ""),
                new MixinApplier.Param("dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.repurposedstructures.Repurposedstructures$MinusEightPlacementMixin", ""),
                new MixinApplier.Param("dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.repurposedstructures.Repurposedstructures$SnapToLowerNonAirPlacementMixin", "")
        );

        create("net.countered.terrainslabs.TerrainSlabs",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.terrainslabs.Mixin$TerrainSlabs$OreFeature",
                        "net.countered.terrainslabs.mixin.feature.MixinOreFeature"
                )
        );
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (shouldDeferBuildSurfaceToModernFix(mixinClassName)) {
            LOGGER.info("Skipping {} while ModernFix is present (compat with optimize_surface_rules on buildSurface)", mixinClassName);
            return false;
        }
        return super.shouldApplyMixin(targetClassName, mixinClassName);
    }

    private static boolean shouldDeferBuildSurfaceToModernFix(String mixinClassName) {
        if (!isModernFixOnClasspath()) {
            return false;
        }
        String n = mixinClassName.indexOf('/') >= 0
                ? mixinClassName.replace('/', '.')
                : mixinClassName;
        return n.contains("SurfaceSystem$new_build_surface")
                || n.contains("BiomesWeveGone$SurfaceBuilder$fix_compat")
                || n.contains("NaturesSpirit$SurfaceBuilderMixin$fix_compat");
    }

    private static boolean isModernFixOnClasspath() {
        try {
            Class.forName(MODERN_FIX_ENTRY, false, GASurfaceMixinPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return true;
        }
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
