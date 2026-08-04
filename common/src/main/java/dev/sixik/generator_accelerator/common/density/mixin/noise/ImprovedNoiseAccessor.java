package dev.sixik.generator_accelerator.common.density.mixin.noise;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to {@link ImprovedNoise}'s permutation table and coordinate offsets
 * for native noise snapshots ({@code dfc-natives}).
 */
@Mixin(value = ImprovedNoise.class, priority = 2000)
public interface ImprovedNoiseAccessor {

    @Accessor("p")
    byte[] dfc$getPermutation();

    @Accessor("xo")
    double dfc$getXo();

    @Accessor("yo")
    double dfc$getYo();

    @Accessor("zo")
    double dfc$getZo();
}
