package dev.sixik.generator_accelerator.common.flat_block_structure;

import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.common.paletted_container.GAPalettedContainerMixinPlugin;
import dev.sixik.generator_accelerator.config.GAConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GAFlatBlockStructureMixinPluginTest {

    @Test
    void lithiumLevelChunkSectionConflictBelongsToFlatBlockPatch() {
        CapturingFlatPlugin flatPlugin = new CapturingFlatPlugin();
        flatPlugin.onLoad("");

        assertTrue(flatPlugin.disabledMixins.contains(
                GAFlatBlockStructureMixinPlugin.LITHIUM_NO_LOCKING_LEVEL_CHUNK_SECTION));
    }

    @Test
    void flatAndPalettedPatchesAreGatedIndependently() {
        GAConfig config = new GAConfig();
        config.enableFlatBlockStructurePatch = false;
        config.enablePalettedContainerPatch = true;

        assertFalse(new GAFlatBlockStructureMixinPlugin().isConfigEnable(config));
        assertTrue(new GAPalettedContainerMixinPlugin().isConfigEnable(config));
    }

    @Test
    void moonriseDisablesTheCompleteFlatBlockMixinPair() {
        assertTrue(GAFlatBlockStructureMixinPlugin.shouldDisableForMoonrise(
                GAFlatBlockStructureMixinPlugin.FLAT_BLOCK_ARRAY_MIXIN,
                true
        ));
        assertTrue(GAFlatBlockStructureMixinPlugin.shouldDisableForMoonrise(
                GAFlatBlockStructureMixinPlugin.FLAT_BLOCK_STATUS_HOOK_MIXIN,
                true
        ));
        assertFalse(GAFlatBlockStructureMixinPlugin.shouldDisableForMoonrise(
                GAFlatBlockStructureMixinPlugin.FLAT_BLOCK_ARRAY_MIXIN,
                false
        ));
        assertFalse(GAFlatBlockStructureMixinPlugin.shouldDisableForMoonrise(
                "dev.sixik.generator_accelerator.UnrelatedMixin",
                true
        ));
    }

    private static final class CapturingFlatPlugin extends GAFlatBlockStructureMixinPlugin {
        private final List<String> disabledMixins = new ArrayList<>();

        @Override
        public void create(String modClass, MixinApplier.Param... params) {
            Arrays.stream(params)
                    .flatMap(param -> Arrays.stream(param.mixinDisable()))
                    .forEach(disabledMixins::add);
        }
    }
}
