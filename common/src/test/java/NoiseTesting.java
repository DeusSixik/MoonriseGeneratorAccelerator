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

        NormalNoise testNoise = NormalNoise.create(xoroshiroRandomSource, 2, 2d, 1d, 0.3d, 0.4d);

        NormalNoise normalNoise = NormalNoise.create(xoroshiroRandomSource, 2, 2d, 1d, 0.3d, 0.4d);
        long xoroshiroPtr = NativeRandom.createXoroshiro(32);
        NativeRandom.printSeed(xoroshiroPtr);

        final long normalNoisePtr = NativeNormalNoise.create(xoroshiroPtr, 2, new double[] { 2d, 1d, 0.3d, 0.4d });

        // ==================== Замер Java ====================
        long startJava = System.nanoTime();
        double java = getSum(normalNoise::getValue);
        long timeJavaNs = System.nanoTime() - startJava;

        // ==================== Замер C3 (нативный) ====================
        long startC3 = System.nanoTime();
        double c3 = getSum((x, y, z) -> NativeNormalNoise.getValue(normalNoisePtr, x, y, z));
        long timeC3Ns = System.nanoTime() - startC3;

        // ==================== Вывод результатов ====================
        double timeJavaMs = timeJavaNs / 1_000_000.0;
        double timeC3Ms = timeC3Ns / 1_000_000.0;
        double speedup = (double) timeJavaNs / timeC3Ns;

        System.out.println("Java:  " + java + "  |  время = " + timeJavaMs + " мс");
        System.out.println("C3:    " + c3 + "  |  время = " + timeC3Ms + " мс");
        System.out.println("Ускорение C3 по сравнению с Java: " + String.format("%.2f", speedup) + "x");

        // Проверка корректности (на всякий случай)
        System.out.println("Результаты совпадают? " + (Math.abs(java - c3) < 1e-9));
    }

    private static double getSum(Func func) {
        double sum = 0;

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    sum += func.handle(x, y, z);
                }
            }
        }

        return sum;
    }

    @FunctionalInterface
    private interface Func {

        double handle(int x, int y, int z);
    }
}
