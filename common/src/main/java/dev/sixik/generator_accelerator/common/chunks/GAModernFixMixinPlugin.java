package dev.sixik.generator_accelerator.common.chunks;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

import java.util.Arrays;
import java.util.Map;

public class GAModernFixMixinPlugin extends GAMixinPlugin {

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }

    @Override
    public void onLoad(String s) {
        final String modernFixFoldersMixin = "dev.sixik.generator_accelerator.common.chunks.mixin.compats.modernfix";

        Map<String, String> modernFixMap = Map.of(
                "ModernFix$ChunkStatusTasksMixin", "org.embeddedt.modernfix.common.mixin.bugfix.chunk_deadlock.ChunkMapLoadMixin",
                "ModernFix$ChunkHolderReleaseProtoChunksMixin", "",
                "ModernFix$GenerationChunkHolderAccessor", "",
                "ModernFixChunkMapReleaseProtoChunksMixin", ""
        );

        create("org.embeddedt.modernfix.ModernFix",
                modernFixMap.entrySet().stream().map((entry) -> new MixinApplier.Param(modernFixFoldersMixin + entry.getKey(), entry.getValue())).toArray(MixinApplier.Param[]::new)
        );
    }
}
