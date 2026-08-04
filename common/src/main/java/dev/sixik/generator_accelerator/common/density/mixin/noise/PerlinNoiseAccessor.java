package dev.sixik.generator_accelerator.common.density.mixin.noise;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessors for the four {@link PerlinNoise} fields we bake into the per-noise
 * {@link dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec}.
 *
 * <p>The interesting layout invariants (kept in sync with vanilla 1.21.x):
 * <ul>
 *   <li>{@code noiseLevels[]} indexes the per-octave {@link ImprovedNoise} samplers.
 *       Entries are {@code null} where the corresponding amplitude is {@code 0.0} —
 *       vanilla's {@code getValue} loop checks this and skips the call. We strip those
 *       at spec-build time so the unrolled bytecode only carries instructions for active
 *       octaves.</li>
 *   <li>{@code amplitudes} is a {@link DoubleList} parallel to {@code noiseLevels}; the
 *       per-octave amplitude is multiplied into each octave's contribution.</li>
 *   <li>{@code lowestFreqInputFactor} is {@code 2^(-firstOctave)}; per-octave input
 *       factor is {@code lowestFreqInputFactor * 2^i} where {@code i} is the original
 *       octave index (NOT the active-octave index — vanilla's {@code d1 *= 2.0}
 *       happens unconditionally inside the loop).</li>
 *   <li>{@code lowestFreqValueFactor} is {@code 2^(noiseLevels.length-1) /
 *       (2^noiseLevels.length - 1)}; per-octave value factor is
 *       {@code lowestFreqValueFactor * 0.5^i}.</li>
 * </ul>
 */
@Mixin(value = PerlinNoise.class, priority = 2000)
public interface PerlinNoiseAccessor {

    @Accessor("noiseLevels")
    ImprovedNoise[] dfc$getNoiseLevels();

    @Accessor("amplitudes")
    DoubleList dfc$getAmplitudes();

    @Accessor("lowestFreqInputFactor")
    double dfc$getLowestFreqInputFactor();

    @Accessor("lowestFreqValueFactor")
    double dfc$getLowestFreqValueFactor();
}
