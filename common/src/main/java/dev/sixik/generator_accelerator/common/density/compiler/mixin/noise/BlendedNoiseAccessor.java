package dev.sixik.generator_accelerator.common.density.compiler.mixin.noise;

import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessors for inlining {@link BlendedNoise#compute} (Minecraft 1.21.x field layout).
 */
@Mixin(value = BlendedNoise.class, priority = 2000)
public interface BlendedNoiseAccessor {
    @Accessor("minLimitNoise")
    PerlinNoise dfc$getMinLimitNoise();

    @Accessor("maxLimitNoise")
    PerlinNoise dfc$getMaxLimitNoise();

    @Accessor("mainNoise")
    PerlinNoise dfc$getMainNoise();

    @Accessor("xzMultiplier")
    double dfc$getXzMultiplier();

    @Accessor("yMultiplier")
    double dfc$getYMultiplier();

    @Accessor("xzFactor")
    double dfc$getXzFactor();

    @Accessor("yFactor")
    double dfc$getYFactor();

    @Accessor("smearScaleMultiplier")
    double dfc$getSmearScaleMultiplier();

    @Accessor("maxValue")
    double dfc$getMaxValue();
}
