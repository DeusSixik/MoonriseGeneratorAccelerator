package dev.sixik.generator_accelerator.common.paletted_container;

import dev.sixik.generator_accelerator.api.mixin.GAMixinPlugin;
import dev.sixik.generator_accelerator.api.mixin.MixinApplier;
import dev.sixik.generator_accelerator.config.GAConfig;

import java.util.Arrays;

public class GAPalettedContainerMixinPlugin extends GAMixinPlugin {
    private static final String LITHIUM = "net.caffeinemc.mods.lithium.common.LithiumMod";
    private static final String MODERNFIX = "org.embeddedt.modernfix.ModernFix";

    @Override
    public boolean isConfigEnable(GAConfig config) {
        return config.enablePalettedContainerPatch;
    }

    @Override
    public void onLoad(String s) {
        String[] mixins = new String[] {
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.bitstorages.MixinSimpleBitStorage",
                "dev.sixik.generator_accelerator.common.paletted_container.mixin.bitstorages.ZeroBitStorage",
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
                        "net.caffeinemc.mods.lithium.mixin.chunk.no_validation.SimpleBitStorageMixin"
                ),
                new MixinApplier.Param(
                "",
                        "net.caffeinemc.mods.lithium.mixin.chunk.no_validation.ZeroBitStorageMixin"
                ),
                new MixinApplier.Param(
                "",
                        "net.caffeinemc.mods.lithium.mixin.chunk.no_locking.PalettedContainerMixin"
                ),
                new MixinApplier.Param(
                "",
                        "net.caffeinemc.mods.lithium.mixin.chunk.palette.PalettedContainer$StrategyMixin"
                ),
                new MixinApplier.Param(
                "",
                        "net.caffeinemc.mods.lithium.mixin.chunk.serialization.PalettedContainerMixin",
                        "net.caffeinemc.mods.lithium.mixin.chunk.serialization.SimpleBitStorageMixin"
                )
        );

        create(MODERNFIX, new MixinApplier.Param(
                "",
                "org.embeddedt.modernfix.common.mixin.perf.compact_bit_storage.PalettedContainerMixin"
        ));
    }
}
