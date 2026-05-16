package dev.sixik.generator_accelerator.common.surface;

import com.mojang.logging.LogUtils;
import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;
import org.slf4j.Logger;

public class GASurfaceMixinPlugin extends GAMixinPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MODERN_FIX_ENTRY = "org.embeddedt.modernfix.ModernFix";

    @Override
    public void onLoad(String s) {
        create("net.hibiscus.naturespirit.NatureSpirit", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.surface.mixin.compats.natures_spirit.NaturesSpirit$SurfaceBuilderMixin$fix_compat",
                "net.hibiscus.naturespirit.mixin.SurfaceBuilderMixin"
        ));

        create("net.potionstudios.biomeswevegone.BiomesWeveGone", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.surface.mixin.compats.biomeswevegone.BiomesWeveGone$SurfaceBuilder$fix_compat",
                surfaceCompatDisable("net.potionstudios.biomeswevegone.mixin.SurfaceSystemMixin")
        ));

        create("com.terraformersmc.biolith.impl.Biolith",
                new MixinApplier.Param(
                        "dev.sixik.generator_accelerator.common.surface.mixin.compats.biolith.Mixin$Biolith$SurfaceSystem",
                        surfaceCompatDisable("com.terraformersmc.biolith.impl.mixin.MixinSurfaceBuilder")
                )
        );

        this.create(GeneratorAccelerator.C2ME_MOD,
                new MixinApplier.Param("",
                        "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.MixinMaterialRulesSequenceMaterialRule",
                        "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.MixinMaterialRulesSequenceBlockStateRule",
                        "com.ishland.c2me.opts.allocs.mixin.surfacebuilder.MixinMaterialRuleContext"
                ));

    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (shouldDeferBuildSurfaceToModernFix(mixinClassName)) {
            LOGGER.info("Skipping {} while ModernFix is present (compat with optimize_surface_rules on buildSurface)", mixinClassName);
            return false;
        }
        return super.shouldApplyMixin(targetClassName, mixinClassName);
    }

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableSurfacePatch;
    }

    private static boolean shouldDeferBuildSurfaceToModernFix(String mixinClassName) {
        if (!isModernFixOnClasspath()) {
            return false;
        }
        String n = mixinClassName.indexOf('/') >= 0
                ? mixinClassName.replace('/', '.')
                : mixinClassName;
        return n.contains("SurfaceSystem$new_build_surface")
                || n.contains("Mixin$Biolith$SurfaceSystem")
                || n.contains("BiomesWeveGone$SurfaceBuilder$fix_compat")
                || n.contains("NaturesSpirit$SurfaceBuilderMixin$fix_compat");
    }

    private static String[] surfaceCompatDisable(String mixinClassName) {
        return isModernFixOnClasspath() ? new String[0] : new String[]{mixinClassName};
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
}
