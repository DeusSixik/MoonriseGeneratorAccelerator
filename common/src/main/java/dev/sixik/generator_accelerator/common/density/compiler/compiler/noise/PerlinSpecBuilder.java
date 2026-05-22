package dev.sixik.generator_accelerator.common.density.compiler.compiler.noise;

import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import dev.sixik.generator_accelerator.common.density.compiler.mixin.noise.PerlinNoiseAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link NoiseSpec.PerlinSpec} from a live {@link PerlinNoise} by walking
 * {@code noiseLevels[]} with the same rules as {@link PerlinNoise#getValue(double, double, double)}.
 *
 * <p>Shared by {@link NoiseSpecCache} and any other inliner that needs a flattened
 * per-octave view without going through a {@link net.minecraft.world.level.levelgen.synth.NormalNoise}
 * container.
 */
public final class PerlinSpecBuilder {
    private PerlinSpecBuilder() {}

    /**
     * @param inputCoordScale pre-multiplier on each coordinate before the unrolled
     *                        per-octave {@code 2^i} factor ( {@code 1.0} for the first
     *                        {@code NormalNoise} branch, {@link NoiseSpec#INPUT_FACTOR}
     *                        for the second).
     * @return {@code null} on accessor failure or malformed state.
     */
    public static NoiseSpec.PerlinSpec build(PerlinNoise pn, double inputCoordScale) {
        PerlinNoiseAccessor pa;
        try {
            pa = (PerlinNoiseAccessor) (Object) pn;
        } catch (ClassCastException ex) {
            DensityFunctionCompiler.LOGGER.warn(
                    "PerlinNoiseAccessor mixin not applied to {} — cannot build Perlin spec",
                    System.identityHashCode(pn));
            NoiseSpecCache.recordPerlinAccessorFailure();
            return null;
        }
        ImprovedNoise[] octaves = pa.dfc$getNoiseLevels();
        DoubleList amplitudes = pa.dfc$getAmplitudes();
        double lowestFreqInputFactor = pa.dfc$getLowestFreqInputFactor();
        double lowestFreqValueFactor = pa.dfc$getLowestFreqValueFactor();

        if (octaves == null || amplitudes == null || octaves.length != amplitudes.size()) {
            return null;
        }

        List<ImprovedNoise> active = new ObjectArrayList<>(octaves.length);
        List<Double> inputFactors = new ObjectArrayList<>(octaves.length);
        List<Double> ampValueFactors = new ObjectArrayList<>(octaves.length);
        int skipped = 0;
        for (int i = 0; i < octaves.length; i++) {
            ImprovedNoise oct = octaves[i];
            double amp = amplitudes.getDouble(i);
            if (oct == null) {
                skipped++;
                continue;
            }
            double inputFactor = lowestFreqInputFactor * Math.pow(2.0, i);
            double valueFactor = lowestFreqValueFactor * Math.pow(0.5, i);
            active.add(oct);
            inputFactors.add(inputFactor);
            ampValueFactors.add(amp * valueFactor);
        }
        NoiseSpecCache.addOctavesSkippedForPerlinBuild(skipped);

        ImprovedNoise[] activeArr = active.toArray(new ImprovedNoise[0]);
        double[] inputArr = new double[inputFactors.size()];
        double[] ampArr = new double[ampValueFactors.size()];
        for (int i = 0; i < inputArr.length; i++) {
            inputArr[i] = inputFactors.get(i);
            ampArr[i] = ampValueFactors.get(i);
        }
        return new NoiseSpec.PerlinSpec(activeArr, inputArr, ampArr, inputCoordScale);
    }
}
