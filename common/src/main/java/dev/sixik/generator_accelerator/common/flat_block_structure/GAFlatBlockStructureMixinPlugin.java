package dev.sixik.generator_accelerator.common.flat_block_structure;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

public class GAFlatBlockStructureMixinPlugin extends GAMixinPlugin {
    static final String LITHIUM = "net.caffeinemc.mods.lithium.common.LithiumMod";
    static final String LITHIUM_NO_LOCKING_LEVEL_CHUNK_SECTION =
            "net.caffeinemc.mods.lithium.mixin.chunk.no_locking.LevelChunkSectionMixin";
    static final String MOONRISE = "ca.spottedleaf.moonrise.common.PlatformHooks";
    static final String FLAT_BLOCK_ARRAY_MIXIN =
            "dev.sixik.generator_accelerator.common.flat_block_structure.mixin.MixinLevelChunkSection$flat_block_array";
    static final String FLAT_BLOCK_STATUS_HOOK_MIXIN =
            "dev.sixik.generator_accelerator.common.flat_block_structure.mixin.MixinChunkStatusTasks$inject_flat_block_structure";
    private static final boolean MOONRISE_PRESENT = isClassPresent(MOONRISE);

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enableFlatBlockStructurePatch;
    }

    @Override
    public void onLoad(String s) {
        // GA overwrites LevelChunkSection#setBlockState and removes the invocation
        // targeted by Lithium's no-locking redirect. This conflict belongs to the
        // flat block array patch and must not depend on the paletted-container patch.
        create(LITHIUM, new MixinApplier.Param("", LITHIUM_NO_LOCKING_LEVEL_CHUNK_SECTION));
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (shouldDisableForMoonrise(mixinClassName, MOONRISE_PRESENT)) {
            return false;
        }
        return super.shouldApplyMixin(targetClassName, mixinClassName);
    }

    static boolean shouldDisableForMoonrise(String mixinClassName, boolean moonrisePresent) {
        return moonrisePresent && (
                FLAT_BLOCK_ARRAY_MIXIN.equals(mixinClassName)
                        || FLAT_BLOCK_STATUS_HOOK_MIXIN.equals(mixinClassName)
        );
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, GAFlatBlockStructureMixinPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (LinkageError ignored) {
            // A partially linkable Moonrise installation is still unsafe for
            // GA's LevelChunkSection overwrite, so fail closed.
            return true;
        }
    }
}
