package dev.sixik.generator_accelerator.common.structures;

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
    }
}
