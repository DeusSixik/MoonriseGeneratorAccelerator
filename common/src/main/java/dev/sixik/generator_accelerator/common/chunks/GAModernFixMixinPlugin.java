package dev.sixik.generator_accelerator.common.chunks;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAModernFixMixinPlugin extends GAMixinPlugin {

    private static final String PACKAGE = "dev.sixik.generator_accelerator.common.chunks.mixin.compats.modernfix.";
    private static final String MODERNFIX = "org.embeddedt.modernfix.ModernFix";
    private static final String LITHIUM = "net.caffeinemc.mods.lithium.common.LithiumMod";
    private static final String CHUNK_MAP_LOAD = "org.embeddedt.modernfix.common.mixin.bugfix.chunk_deadlock.ChunkMapLoadMixin";
    private static final String RELEASE_PROTOCHUNKS_CHUNK_MAP = "org.embeddedt.modernfix.common.mixin.perf.release_protochunks.ChunkMapMixin";
    private static final String RELEASE_PROTOCHUNKS_CHUNK_HOLDER = "org.embeddedt.modernfix.common.mixin.perf.release_protochunks.ChunkHolderMixin";
    private static final String LITHIUM_SERVER_CHUNK_CACHE = "net.caffeinemc.mods.lithium.mixin.world.chunk_access.ServerChunkCacheMixin";


    @Override
    public boolean isConfigEnable(GAConfig config) {
        return true;
    }

    @Override
    public void onLoad(String s) {
        create(MODERNFIX,
                param("ModernFix$ChunkStatusTasksMixin", CHUNK_MAP_LOAD),
                param("ModernFix$ChunkHolderReleaseProtoChunksMixin", RELEASE_PROTOCHUNKS_CHUNK_HOLDER),
                param("ModernFix$GenerationChunkHolderAccessor"),
                param("ModernFixChunkMapReleaseProtoChunksMixin", RELEASE_PROTOCHUNKS_CHUNK_MAP)
        );

        // Lithium's blocking chunk lookup fast path serializes GA's parallel worldgen
        // work when ModernFix's full-chunk promotion shim is also active.
        createAll(new String[]{MODERNFIX, LITHIUM},
                new MixinApplier.Param("", LITHIUM_SERVER_CHUNK_CACHE)
        );
    }

    private static MixinApplier.Param param(String mixinClass, String mixinDisable) {
        return new MixinApplier.Param(PACKAGE + mixinClass, mixinDisable);
    }

    private static MixinApplier.Param param(String mixinClass) {
        return new MixinApplier.Param(PACKAGE + mixinClass, "");
    }
}
