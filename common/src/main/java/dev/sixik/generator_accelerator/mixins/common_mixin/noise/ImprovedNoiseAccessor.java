package dev.sixik.generator_accelerator.mixins.common_mixin.noise;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ImprovedNoise.class)
public interface ImprovedNoiseAccessor {

    @Accessor("p")
    byte[] ga$getP();

    @Accessor("xo")
    double ga$getXo();

    @Accessor("yo")
    double ga$getYo();

    @Accessor("zo")
    double ga$getZo();
}