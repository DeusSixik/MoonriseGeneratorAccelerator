import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensityOptimizer;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

public class DensityCompilerTest {


    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DensityCompilerData.boot();
    }

    @Test
    @DisplayName("Simple arithmetic test: (10 + 20) * 2")
    void testSimpleMath() {
        DensityFunction c10 = new DensityFunctions.Constant(10.0);
        DensityFunction c20 = new DensityFunctions.Constant(20.0);
        DensityFunction c2 = new DensityFunctions.Constant(2.0);

        DensityFunction root = new DensitySpecializations.FastMul(
                new DensitySpecializations.FastAdd(c10, c20),
                c2
        );

        compileAndVerify(root, "SimpleMath.class", 60.0);
    }

    @Test
    @DisplayName("testCompilation")
    void testCompilation() {
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(33, new ArrayList<>())), null);

//        compileAndVerify(new DensityFunctions.Ap2(DensityFunctions.TwoArgumentSimpleFunction.Type.ADD, new DensityFunctions.Constant(5), new DensityFunctions.Constant(5), 0, 5), "FillArrayConst.class", 10);
//        compileAndVerify(new DensityFunctions.Constant(10), "FillArrayConst.class", 10);
//        compileAndVerify(new DensityFunctions.BlendDensity(new DensityFunctions.BlendDensity(new DensityFunctions.Constant(10))), "TestCompilation.class", 10);
//        compileAndVerify(new DensityFunctions.Clamp(new DensityFunctions.BlendDensity(new DensityFunctions.Constant(10)), 0, 10), "TestCompilation.class", 10);
//        compileAndVerify(new DensityFunctions.Mapped(DensityFunctions.Mapped.Type.SQUARE, new DensityFunctions.BlendDensity(new DensityFunctions.Constant(10)), 0, 10), "TestCompilation.class", 100);
        compileAndVerify(new DensityFunctions.RangeChoice(
                new DensityFunctions.BlendDensity(new DensityFunctions.Constant(50)),
                10,
                20,
                new DensityFunctions.RangeChoice(
                        new DensityFunctions.BlendDensity(new DensityFunctions.Constant(50)),
                        10,
                        20,
                        new DensityFunctions.BlendDensity(new DensityFunctions.Constant(50)),
                        new DensityFunctions.BlendDensity(new DensityFunctions.Constant(4))
                ),
                new DensityFunctions.Constant(0)
        ), "TestCompilation.class", 0);
//        compileAndVerify(
//                new DensityFunctions.WeirdScaledSampler(new DensityFunctions.Constant(5), noise, DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1),
//                "TestCompilation.class", 0.0);
    }

    @Test
    @DisplayName("Min/Max Function Test")
    void testMinMax() {
        DensityFunction c10 = new DensityFunctions.Constant(10.0);
        DensityFunction c20 = new DensityFunctions.Constant(20.0);

        DensityFunction root = new DensitySpecializations.FastMax(c10, c20);

        compileAndVerify(root, "MinMaxTest.class", 20.0);
    }

    @Test
    @DisplayName("Beardifier Function Test")
    void testBeardifier() {
        final ObjectArrayList<Beardifier.Rigid> objects = new ObjectArrayList<>();
        final ObjectArrayList<JigsawJunction> objects2 = new ObjectArrayList<>();

        DensityFunction c10 = new Beardifier(objects.listIterator(), objects2.listIterator());
        DensityFunction c20 = new DensityFunctions.Constant(20.0);

        DensityFunction root = new DensitySpecializations.FastMax(c10, c20);

        compileAndVerify(root, "Beardifier.class", 20.0);
    }

    @Test
    @DisplayName("LargeDensity Function Test")
    void testLargeDensity() {
        final ObjectArrayList<Beardifier.Rigid> objects = new ObjectArrayList<>();
        final ObjectArrayList<JigsawJunction> objects2 = new ObjectArrayList<>();

        DensityFunction c1 = new DensityFunctions.Constant(404);
        DensityFunction c2 = new DensityFunctions.Constant(-804);

        DensityFunction max = new DensitySpecializations.FastMax(c1, c2);

        DensityFunction c10 = new Beardifier(objects.listIterator(), objects2.listIterator());

        DensityFunctions.ShiftedNoise mig = new DensityFunctions.ShiftedNoise(c10, max, c10, 10, 20, new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(10, new ArrayList<>())), null));

        DensityFunction c20 = new DensityFunctions.Constant(20.0);
        DensityFunction sh1 = new DensityFunctions.ShiftedNoise(c20, mig, c20, 10, 20, new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(10, new ArrayList<>())), null));

        DensityFunction root1 = new DensityFunctions.RangeChoice(sh1, 0, 1, c10, c1);
        DensityFunction root2 = new DensityFunctions.RangeChoice(root1, 0, 1, c10, c1);
        DensityFunction root = new DensityFunctions.RangeChoice(root1, 0, 1, root2, c1);

        compileAndVerify(root, "LargeDensity.class", 0.0);
    }

    @ParameterizedTest
    @MethodSource("provideFunctionBatches")
    @DisplayName("Массовая проверка функций")
    void testBatch(DensityFunction func, String name, double expected) {
        compileAndVerify(func, name + ".class", expected);
    }

    /**
     * A method for adding a batch of tests
     */
    private static Stream<Arguments> provideFunctionBatches() {
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(33, new ArrayList<>())), null);

        return Stream.of(
                Arguments.of(new DensitySpecializations.FastAdd(new DensityFunctions.Constant(5), new DensityFunctions.Constant(5)), "AddTest", 10.0),
                Arguments.of(new DensitySpecializations.FastMul(new DensityFunctions.Constant(3), new DensityFunctions.Constant(4)), "MulTest", 12.0),
                Arguments.of(new DensitySpecializations.FastMin(new DensityFunctions.Constant(-5), new DensityFunctions.Constant(10)), "MinTest", -5.0),
                Arguments.of(new DensitySpecializations.FastMax(new DensityFunctions.Constant(-5), new DensityFunctions.Constant(10)), "MaxTest", 10),
                Arguments.of(DensityFunctions.BlendOffset.INSTANCE, "BlendOffset", 0),
                Arguments.of(DensityFunctions.BlendAlpha.INSTANCE, "BlendAlpha", 1),
                Arguments.of(new DensityFunctions.ShiftedNoise(new DensityFunctions.Constant(.1), new DensityFunctions.Constant(.2), new DensityFunctions.Constant(.3), 11, 22, noise), "ShiftedNoise", 0),
                Arguments.of(new DensityFunctions.RangeChoice(new DensityFunctions.Constant(.1), 0, 1, new DensityFunctions.Constant(.2), new DensityFunctions.Constant(0.3)), "RangeChoice", 0.2),
                Arguments.of(new DensityFunctions.ShiftA(noise), "ShiftA", 0.0),
                Arguments.of(new DensityFunctions.ShiftB(noise), "ShiftB", 0.0),
                Arguments.of(new DensityFunctions.Clamp(new DensityFunctions.Constant(.1), 0, 1), "Clamp", 0.1),
                Arguments.of(
                        DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.ABS, new DensityFunctions.Constant(-5.0)),
                        "MappedAbs",
                        5.0
                ),
                Arguments.of(
                        DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.SQUARE, new DensityFunctions.Constant(3.0)),
                        "MappedSquare",
                        9.0
                ),
                Arguments.of(
                        DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.HALF_NEGATIVE, new DensityFunctions.Constant(-10.0)),
                        "MappedHalfNeg",
                        -5.0
                ),
                Arguments.of(
                        new DensityFunctions.MulOrAdd(
                                DensityFunctions.MulOrAdd.Type.ADD,
                                new DensityFunctions.Constant(1),
                                0.1, 0.2, 0.3
                        ),
                        "MulOrAdd Add",
                        1.3
                ),
                Arguments.of(
                        new DensityFunctions.MulOrAdd(
                                DensityFunctions.MulOrAdd.Type.MUL,
                                new DensityFunctions.Constant(1),
                                0.1, 0.2, 0.3
                        ),
                        "MulOrAdd Mul",
                        0.3
                ),
                Arguments.of(
                        new DensityFunctions.Noise(
                                noise,
                                1,
                                1
                        ),
                        "Noise",
                        0.0
                ),
                Arguments.of(
                        new DensityFunctions.WeirdScaledSampler(
                                new DensityFunctions.Constant(0.5),
                                noise,
                                DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1
                        ),
                        "WeirdSampler",
                        0.0
                ),
                Arguments.of(
                        new DensityFunctions.BlendDensity(
                                new DensityFunctions.Constant(0.5)
                        ),
                        "BlendDensity",
                        0.5
                ),
                Arguments.of(
                        new DensityFunctions.YClampedGradient(
                                2,
                                5,
                                10,
                                4
                        ),
                        "YClampedGradient",
                        10.0
                ),
                Arguments.of(
                        new DensityFunctions.HolderHolder(Holder.direct(new DensityFunctions.Constant(505))),
                        "HolderHolder",
                        505
                )
        );
    }

    /**
     * Compiles, dumps, and verifies the result of the calculation.
     */
    private void compileAndVerify(DensityFunction root, String fileName, double expectedResult) {
        System.out.println("Compiling " + fileName + "...");
        try {

            /*
                We are also creating an instance to check that it does not crash in runtime.
             */
            DensityFunction compiled = DensityCompilerPipeline.from(root, true).startCompilation();

            /*
                Checking the math (using an empty context)
             */
            double result = compiled.compute(new DensityFunction.SinglePointContext(0, 0, 0));

            System.out.println("Result of " + fileName + ": " + result);
            assertEquals(expectedResult, result, 0.00001, "The math in the compiled class " + fileName + " is incorrect!");

        } catch (Exception e) {
            throw new RuntimeException("Compilation error: " + fileName, e);
        }
    }

    @Test
    void testFillArrayMatchesCompute() {
        DensityFunction root = createDensityFunction();
        DensityFunction compiled = mockCompiledFunction(root);

        int size = 64;
        double[] results = new double[size];

        DensityFunction.ContextProvider mockProvider = Mockito.mock(DensityFunction.ContextProvider.class);

        DensityFunction.FunctionContext[] contexts = new DensityFunction.FunctionContext[size];
        for (int i = 0; i < size; i++) {
            DensityFunction.FunctionContext mockCtx = Mockito.mock(DensityFunction.FunctionContext.class);
            when(mockCtx.blockX()).thenReturn(i);
            when(mockCtx.blockY()).thenReturn(i * 2);
            when(mockCtx.blockZ()).thenReturn(0);

            contexts[i] = mockCtx;
        }

        when(mockProvider.forIndex(anyInt())).thenAnswer(inv -> contexts[(Integer) inv.getArgument(0)]);

        compiled.fillArray(results, mockProvider);

        for (int i = 0; i < size; i++) {
            double expected = compiled.compute(contexts[i]);
            double actual = results[i];

            Assertions.assertEquals(expected, actual, 1e-9, "Mismatch at index " + i);
        }
    }

    @Test
    void testVanillaEquivalence() {
        final DensityFunction original = createDensityFunction();

        DensityFunction optimized = mockCompiledFunction(original);

        int size = 256;
        double[] vanillaResults = new double[size];
        double[] optimizedResults = new double[size];

        DensityFunction.ContextProvider testProvider = new TestContextProvider(size);

        original.fillArray(vanillaResults, testProvider);
        optimized.fillArray(optimizedResults, testProvider);

        for (int i = 0; i < size; i++) {

            System.out.println(vanillaResults[i] + " | " + optimizedResults[i]);

            assertEquals(vanillaResults[i], optimizedResults[i], 1e-12,
                    "Дерево выдало разные результаты на индексе " + i);

            assertEquals(optimizedResults[i], optimized.compute(testProvider.forIndex(i)), 1e-12,
                    "Внутренний дисбаланс в оптимизированном классе на индексе " + i);
        }
    }

    @Test
    void benchmarkDensityPerformance() {
        final HolderLookup.RegistryLookup<DensityFunction> holder =
                VanillaRegistries.createLookup().lookup(Registries.DENSITY_FUNCTION).get();

        final DensityFunction original = NoiseRouterData.getFunction(holder, NoiseRouterData.DEPTH);
        final DensityFunction optimized = mockCompiledFunction(original);

        int size = 16384;
        double[] vanillaResults = new double[size];
        double[] optimizedResults = new double[size];
        TestContextProvider provider = new TestContextProvider(size);

        final int operations = 200;

        RandomSource source = RandomSource.create(256);

        System.out.println("Start Vanilla Test");
        System.gc();

        source.setSeed(256);
        long startVanilla = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            provider.setContext(source.nextInt(size - 1), source.nextInt(0, 16), source.nextInt(0, 16), source.nextInt(0, 16));
            original.fillArray(vanillaResults, provider);
        }
        long endVanilla = System.nanoTime();

        System.out.println("Start ASM Path Test");
        System.gc();

        source.setSeed(256);
        long startOptimized = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            provider.setContext(source.nextInt(size - 1), source.nextInt(0, 16), source.nextInt(0, 16), source.nextInt(0, 16));
            optimized.fillArray(optimizedResults, provider);
        }
        long endOptimized = System.nanoTime();

        for (int i = 0; i < size; i++) {
            assertEquals(vanillaResults[i], optimizedResults[i], 1e-12,
                    "Дерево выдало разные результаты на индексе " + i);

            assertEquals(optimizedResults[i], optimized.compute(provider.forIndex(i)), 1e-12,
                    "Внутренний дисбаланс в оптимизированном классе на индексе " + i);
        }

        double vanillaTime = (endVanilla - startVanilla) / 1_000_000.0;
        double optimizedTime = (endOptimized - startOptimized) / 1_000_000.0;

        System.out.printf("Vanilla total time: %.2f ms%n", vanillaTime);
        System.out.printf("Optimized total time: %.2f ms%n", optimizedTime);
        System.out.printf("Speedup: %.2fx%n", vanillaTime / optimizedTime);


    }

    private DensityFunction createDensityFunction() {
        final HolderLookup.RegistryLookup<DensityFunction> holder =
                VanillaRegistries.createLookup().lookup(Registries.DENSITY_FUNCTION).get();

        boolean bl = true;
        boolean bl2 = false;

        return NoiseRouterData.getFunction(holder, bl ? NoiseRouterData.DEPTH_LARGE : (bl2 ? NoiseRouterData.DEPTH_AMPLIFIED : NoiseRouterData.DEPTH));
    }

    private DensityFunction mockCompiledFunction(DensityFunction root) {
        final DensityOptimizer optimizer = new DensityOptimizer();
        final DensityFunction opt = optimizer.optimize(root);
        return DensityCompilerPipeline.from(opt, true).startCompilation();
    }

    public static class TestContextProvider implements DensityFunction.ContextProvider {

        private final SimpleContext[] contexts;

        public TestContextProvider(int size) {
            this.contexts = new SimpleContext[size];
            for (int i = 0; i < size; i++) {
                this.contexts[i] = new SimpleContext(0, i, 0);
            }
        }

        /**
         * Позволяет задать кастомные координаты для теста (например, 3D сетку).
         */
        public void setContext(int index, int x, int y, int z) {
            this.contexts[index] = new SimpleContext(x, y, z);
        }

        @Override
        public DensityFunction.FunctionContext forIndex(int i) {
            return contexts[i];
        }

        @Override
        public void fillAllDirectly(double[] ds, DensityFunction densityFunction) {
            // Стандартная реализация без побочных эффектов NoiseChunk
            for (int i = 0; i < ds.length; i++) {
                ds[i] = densityFunction.compute(contexts[i]);
            }
        }

        /**
         * Легковесный контекст, который не требует Blender.
         */
        public record SimpleContext(int blockX, int blockY, int blockZ) implements DensityFunction.FunctionContext {
            @Override
            public Blender getBlender() {
                return Blender.empty(); // Возвращаем пустой блендер, чтобы не было NPE
            }
        }
    }
}
