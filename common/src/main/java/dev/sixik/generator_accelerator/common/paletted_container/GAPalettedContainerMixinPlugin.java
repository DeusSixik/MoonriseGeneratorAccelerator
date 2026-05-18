package dev.sixik.generator_accelerator.common.paletted_container;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

import java.util.Arrays;

public class GAPalettedContainerMixinPlugin extends GAMixinPlugin {
    private static final String LITHIUM = "net.caffeinemc.mods.lithium.common.LithiumMod";
    private static final String MODERNFIX = "org.embeddedt.modernfix.ModernFix";
    private static final String LITHIUM_NO_LOCKING_LEVEL_CHUNK_SECTION = "net.caffeinemc.mods.lithium.mixin.chunk.no_locking.LevelChunkSectionMixin";
    private static final String LITHIUM_NO_LOCKING_PALETTED_CONTAINER = "net.caffeinemc.mods.lithium.mixin.chunk.no_locking.PalettedContainerMixin";
    private static final String LITHIUM_NO_VALIDATION_SIMPLE_BIT_STORAGE = "net.caffeinemc.mods.lithium.mixin.chunk.no_validation.SimpleBitStorageMixin";
    private static final String LITHIUM_NO_VALIDATION_ZERO_BIT_STORAGE = "net.caffeinemc.mods.lithium.mixin.chunk.no_validation.ZeroBitStorageMixin";
    private static final String LITHIUM_PALETTE_STRATEGY = "net.caffeinemc.mods.lithium.mixin.chunk.palette.PalettedContainer$StrategyMixin";
    private static final String LITHIUM_SERIALIZATION_PALETTED_CONTAINER = "net.caffeinemc.mods.lithium.mixin.chunk.serialization.PalettedContainerMixin";
    private static final String LITHIUM_SERIALIZATION_SIMPLE_BIT_STORAGE = "net.caffeinemc.mods.lithium.mixin.chunk.serialization.SimpleBitStorageMixin";
    private static final String LITHIUM_DEBUG_PALETTE = "net.caffeinemc.mods.lithium.mixin.debug.palette.PalettedContainerMixin";

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enablePalettedContainerPatch;
    }

    @Override
    public void onLoad(String s) {
        String[] mixins = new String[] {
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.bitstorages.MixinSimpleBitStorage",
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.bitstorages.MixinZeroBitStorage",
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.patch.MixinCrudeIncrementalIntIdentityHashBiMap",
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.patch.MixinHashMapPalette",
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.patch.MixinLinearPalette",
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.patch.MixinPalette",
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.patch.MixinPalettedContainer",
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.patch.MixinPalettedContainer$Data",
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.patch.MixinSingleValuePalette"
        };

        MixinApplier.Param[] array = Arrays.stream(mixins).map((value) -> new MixinApplier.Param("", value)).toArray(MixinApplier.Param[]::new);
        create("ca.spottedleaf.moonrise.common.PlatformHooks", array);

        create(LITHIUM,
                new MixinApplier.Param(
                "",
                        LITHIUM_NO_VALIDATION_SIMPLE_BIT_STORAGE
                ),
                new MixinApplier.Param(
                "",
                        LITHIUM_NO_VALIDATION_ZERO_BIT_STORAGE
                ),
                new MixinApplier.Param(
                "",
                        LITHIUM_NO_LOCKING_LEVEL_CHUNK_SECTION,
                        LITHIUM_NO_LOCKING_PALETTED_CONTAINER
                ),
                new MixinApplier.Param(
                "",
                        LITHIUM_PALETTE_STRATEGY
                ),
                new MixinApplier.Param(
                "",
                        LITHIUM_SERIALIZATION_PALETTED_CONTAINER,
                        LITHIUM_SERIALIZATION_SIMPLE_BIT_STORAGE,
                        LITHIUM_DEBUG_PALETTE
                )
        );

        create(MODERNFIX, new MixinApplier.Param(
                "",
                "org.embeddedt.modernfix.common.mixin.perf.compact_bit_storage.PalettedContainerMixin"
        ));
    }
}
