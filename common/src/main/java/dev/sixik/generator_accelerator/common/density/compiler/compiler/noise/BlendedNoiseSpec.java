package dev.sixik.generator_accelerator.common.density.compiler.compiler.noise;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/**
 * Baked state for inlining {@link net.minecraft.world.level.levelgen.synth.BlendedNoise#compute}:
 * the three per-octave samplers from {@code getOctaveNoise} (main: 0..7, min/max: 0..15) plus
 * the same {@code double} fields vanilla uses after construction.
 *
 * <p>The control flow, wrap calls, 5-argument {@link ImprovedNoise#noise} invocations, and
 * {@code Mth.clampedLerp} tail match Minecraft 1.21.x decompiled
 * {@code BlendedNoise#compute} line-for-line in {@link
 * dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.Codegen} emission.
 */
public record BlendedNoiseSpec(
        double xzMultiplier,
        double yMultiplier,
        double xzFactor,
        double yFactor,
        double smearScaleMultiplier,
        double maxValue,
        ImprovedNoise[] mainOctaves,
        ImprovedNoise[] minLimitOctaves,
        ImprovedNoise[] maxLimitOctaves) {

    public static final int MAIN_OCTAVES = 8;
    public static final int LIMIT_OCTAVES = 16;
    public static final int PAYLOAD_IMPROVED_NOISES = MAIN_OCTAVES + LIMIT_OCTAVES + LIMIT_OCTAVES;
}
