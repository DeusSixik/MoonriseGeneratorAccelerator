package dev.sixik.generator_accelerator.mixins.common_mixin.accessor;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldgenRandom.class)
public interface MixinWorldgenRandomAccessor {

    @Accessor("randomSource")
    RandomSource ga$getRandomSource();
}
