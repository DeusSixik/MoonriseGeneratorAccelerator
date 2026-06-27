package dev.sixik.generator_accelerator.common.paletted_container.mixin;

import dev.sixik.generator_accelerator.api.mixin.annotation.CompatMixin;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

@CompatMixin(mode = CompatMixin.MatchMode.ANY, modClassNames = {
        "net.caffeinemc.mods.lithium.common.LithiumMod",
        "org.embeddedt.modernfix.ModernFix"
},
        disable = {
                "net.caffeinemc.mods.lithium.mixin.chunk.no_locking.LevelChunkSectionMixin",
                "net.caffeinemc.mods.lithium.mixin.chunk.no_locking.PalettedContainerMixin",
                "net.caffeinemc.mods.lithium.mixin.chunk.no_validation.SimpleBitStorageMixin",
                "net.caffeinemc.mods.lithium.mixin.chunk.no_validation.ZeroBitStorageMixin",
                "net.caffeinemc.mods.lithium.mixin.chunk.palette.PalettedContainer$StrategyMixin",
                "net.caffeinemc.mods.lithium.mixin.chunk.serialization.PalettedContainerMixin",
                "net.caffeinemc.mods.lithium.mixin.chunk.serialization.SimpleBitStorageMixin",
                "net.caffeinemc.mods.lithium.mixin.debug.palette.PalettedContainerMixin",
                "org.embeddedt.modernfix.common.mixin.perf.compact_bit_storage.PalettedContainerMixin"
        }
)
@Mixin(MinecraftServer.class)
public class MixinDisable {
}
