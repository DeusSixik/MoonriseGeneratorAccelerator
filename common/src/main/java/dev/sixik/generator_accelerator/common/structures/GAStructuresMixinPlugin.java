package dev.sixik.generator_accelerator.common.structures;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAStructuresMixinPlugin extends GAMixinPlugin {
    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableStructuresPatch;
    }

    @Override
    public void onLoad(String s) {
        create("org.violetmoon.zeta.Zeta", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.structures.mixin.compats.zeta.Zeta$StructurePiece$Fix",
                "org.violetmoon.zeta.mixin.mixins.StructurePieceMixin"
        ));

        create("com.teamabnormals.blueprint.core.Blueprint", new MixinApplier.Param(
                "dev.sixik.generator_accelerator.common.structures.mixin.compats.blueprints.Blueprint$StructurePieceMixin",
                "com.teamabnormals.blueprint.core.mixin.StructurePieceMixin"
        ));

        create(GeneratorAccelerator.C2ME_MOD, new MixinApplier.Param("",
                "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading.MixinStructure",
                "com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading.MixinStructurePalettedBlockInfoList"
        ));
    }
}
