package dev.sixik.generator_accelerator.mixins.common_mixin.accessor;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicLong;

@Mixin(LegacyRandomSource.class)
public interface MixinLegacyRandomSourceAccessor {

    @Accessor("seed")
    AtomicLong ga$getSeed();
}
