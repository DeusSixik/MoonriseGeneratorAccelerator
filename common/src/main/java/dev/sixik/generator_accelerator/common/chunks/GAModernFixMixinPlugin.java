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
    private static final String WORLDGEN_ALLOCATION_MATERIAL_RULE_LIST = "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.MaterialRuleListMixin";
    private static final String WORLDGEN_ALLOCATION_NOISE_CHUNK = "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.NoiseChunkMixin";
    private static final String WORLDGEN_ALLOCATION_SEQUENCE_RULE = "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.SequenceRuleMixin";
    private static final String WORLDGEN_ALLOCATION_SURFACE_RULES = "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.SurfaceRulesMixin";
    private static final String WORLDGEN_ALLOCATION_SURFACE_RULES_CONTEXT = "org.embeddedt.modernfix.common.mixin.perf.worldgen_allocation.SurfaceRulesContextMixin";
    private static final String OPTIMIZE_SURFACE_BIOME_MANAGER = "org.embeddedt.modernfix.common.mixin.perf.optimize_surface_rules.BiomeManagerAccessor";
    private static final String OPTIMIZE_SURFACE_SEQUENCE_RULE_SOURCE = "org.embeddedt.modernfix.common.mixin.perf.optimize_surface_rules.SequenceRuleSourceMixin";
    private static final String OPTIMIZE_SURFACE_SYSTEM = "org.embeddedt.modernfix.common.mixin.perf.optimize_surface_rules.SurfaceSystemMixin";
    private static final String REDUCE_BLOCKSTATE_BLOCKS = "org.embeddedt.modernfix.common.mixin.perf.reduce_blockstate_cache_rebuilds.BlocksMixin";
    private static final String REDUCE_BLOCKSTATE_BASE = "org.embeddedt.modernfix.common.mixin.perf.reduce_blockstate_cache_rebuilds.BlockStateBaseMixin";
    private static final String REDUCE_BLOCKSTATE_CALLBACKS = "org.embeddedt.modernfix.common.mixin.perf.reduce_blockstate_cache_rebuilds.BlockCallbacksMixin";
    private static final String REDUCE_BLOCKSTATE_BEHAVIOUR_INVOKER = "org.embeddedt.modernfix.common.mixin.perf.reduce_blockstate_cache_rebuilds.BlockBehaviourInvoker";
    private static final String DYNAMIC_STRUCTURE_MANAGER = "org.embeddedt.modernfix.common.mixin.perf.dynamic_structure_manager.StructureManagerMixin";
    private static final String LITHIUM_CHUNK_HOLDER = "net.caffeinemc.mods.lithium.mixin.world.chunk_access.ChunkHolderMixin";
    private static final String LITHIUM_GENERATION_CHUNK_HOLDER = "net.caffeinemc.mods.lithium.mixin.world.chunk_access.GenerationChunkHolderAccessor";
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

        // These ModernFix modules touch the same worldgen/surface/blockstate/structure
        // hot paths that GA replaces with wider pipelines. Keep unrelated ModernFix
        // fixes enabled, but do not let duplicate hot-path rewrites stack on top of GA.
        create(MODERNFIX, disable(
                WORLDGEN_ALLOCATION_MATERIAL_RULE_LIST,
                WORLDGEN_ALLOCATION_NOISE_CHUNK,
                WORLDGEN_ALLOCATION_SEQUENCE_RULE,
                WORLDGEN_ALLOCATION_SURFACE_RULES,
                WORLDGEN_ALLOCATION_SURFACE_RULES_CONTEXT,
                OPTIMIZE_SURFACE_BIOME_MANAGER,
                OPTIMIZE_SURFACE_SEQUENCE_RULE_SOURCE,
                OPTIMIZE_SURFACE_SYSTEM,
                REDUCE_BLOCKSTATE_BLOCKS,
                REDUCE_BLOCKSTATE_BASE,
                REDUCE_BLOCKSTATE_CALLBACKS,
                REDUCE_BLOCKSTATE_BEHAVIOUR_INVOKER,
                DYNAMIC_STRUCTURE_MANAGER
        ));

        // Lithium's blocking chunk lookup fast path serializes GA's parallel worldgen
        // work. The two accessors are only used by that overwritten fast path.
        create(LITHIUM, disable(
                LITHIUM_CHUNK_HOLDER,
                LITHIUM_GENERATION_CHUNK_HOLDER,
                LITHIUM_SERVER_CHUNK_CACHE
        )
        );
    }

    private static MixinApplier.Param param(String mixinClass, String mixinDisable) {
        return new MixinApplier.Param(PACKAGE + mixinClass, mixinDisable);
    }

    private static MixinApplier.Param param(String mixinClass) {
        return new MixinApplier.Param(PACKAGE + mixinClass, "");
    }

    private static MixinApplier.Param disable(String... mixinDisable) {
        return new MixinApplier.Param("", mixinDisable);
    }
}
