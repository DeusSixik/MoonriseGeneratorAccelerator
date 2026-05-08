package dev.sixik.generator_accelerator.common.chunks;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

import java.util.Arrays;
import java.util.Map;

public class GAModernFixMixinPlugin extends GAMixinPlugin {

    private static final String PACKAGE = "dev.sixik.generator_accelerator.common.chunks.mixin.compats.modernfix.";
    private static final String MODERNFIX = "org.embeddedt.modernfix.ModernFix";
    private static final String CHUNK_MAP_LOAD = "org.embeddedt.modernfix.common.mixin.bugfix.chunk_deadlock.ChunkMapLoadMixin";


    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }

    @Override
    public void onLoad(String s) {
        create(MODERNFIX,
                param("ModernFix$ChunkStatusTasksMixin", CHUNK_MAP_LOAD),
                param("ModernFix$ChunkHolderReleaseProtoChunksMixin"),
                param("ModernFix$GenerationChunkHolderAccessor"),
                param("ModernFixChunkMapReleaseProtoChunksMixin")
        );
    }

    private static MixinApplier.Param param(String mixinClass, String mixinDisable) {
        return new MixinApplier.Param(PACKAGE + mixinClass, mixinDisable);
    }

    private static MixinApplier.Param param(String mixinClass) {
        return new MixinApplier.Param(PACKAGE + mixinClass, "");
    }
}
