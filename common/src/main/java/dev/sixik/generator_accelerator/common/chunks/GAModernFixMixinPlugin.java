package dev.sixik.generator_accelerator.common.chunks;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

import java.util.Arrays;

public class GAModernFixMixinPlugin extends GAMixinPlugin {

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }

    @Override
    public void onLoad(String s) {
        final String modernFixFoldersMixin = "dev.sixik.generator_accelerator.common.chunks.mixin.compats.modernfix";
        final String[] modernFixMixins = {
            "ModernFix$ChunkStatusTasksMixin",
            "ModernFix$ChunkHolderReleaseProtoChunksMixin",
            "ModernFix$GenerationChunkHolderAccessor",
            "ModernFixChunkMapReleaseProtoChunksMixin"
        };

        create("org.embeddedt.modernfix.ModernFix",
                Arrays.stream(modernFixMixins).map((value) -> new MixinApplier.Param(modernFixFoldersMixin + value, "")).toArray(MixinApplier.Param[]::new)
        );
    }
}
