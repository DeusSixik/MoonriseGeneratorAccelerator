import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.math.c3.NativeNormalNoise;
import dev.sixik.generator_accelerator.math.c3.NativeRandom;
import dev.sixik.generator_accelerator.math.c3.NativeTests;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

public class NoiseTesting
{

    @BeforeAll
    static void init() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        GeneratorAccelerator.tryLoadNatives();
    }


    @Test
    void testNatives() {



        XoroshiroRandomSource xoroshiroRandomSource = new XoroshiroRandomSource(32);
//
        System.out.println("S1: " + xoroshiroRandomSource.randomNumberGenerator.seedLo);
        System.out.println("S2: " + xoroshiroRandomSource.randomNumberGenerator.seedHi);

        NormalNoise normalNoise = NormalNoise.create(xoroshiroRandomSource, 2, 2d, 1d, 0.3d, 0.4d);
        System.out.println(normalNoise.maxValue());
        System.out.println(normalNoise.getValue(2, 1, 3));

        long xoroshiroPtr = NativeRandom.createXoroshiro(32);
        NativeRandom.printSeed(xoroshiroPtr);

        long normalNoisePtr = NativeNormalNoise.create(xoroshiroPtr, 2, new double[] { 2d, 1d, 0.3d, 0.4d });

        System.out.println(NativeNormalNoise.getMax(normalNoisePtr));
        System.out.println(NativeNormalNoise.getValue(normalNoisePtr, 2, 1, 3));


    }
}
