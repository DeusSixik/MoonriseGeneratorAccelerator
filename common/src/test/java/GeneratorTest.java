import dev.sixik.density_interpreter.DensityCompiler;
import dev.sixik.density_interpreter.DensityVM;
import dev.sixik.density_interpreter.tests.FlatNoiseChunk;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

public class GeneratorTest {

    @BeforeAll
    static void onStart() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("Density Compiler ByteCode Generation")
    void testGenerator() {
//        DensityFunction root = DensityFunctions.TwoArgumentSimpleFunction.create(
//                DensityFunctions.TwoArgumentSimpleFunction.Type.MUL,
//                new DensityFunctions.Constant(5),
//                DensityFunctions.TwoArgumentSimpleFunction.create(
//                        DensityFunctions.TwoArgumentSimpleFunction.Type.ADD,
//                        new DensityFunctions.Constant(1),
//                        new DensityFunctions.Constant(404)
//                )
//        );

        DensityFunction root = new DensityFunctions.RangeChoice(
                new DensityFunctions.Constant(50),
                5,
                10,
                new DensityFunctions.Constant(15),
                new DensityFunctions.Constant(1)
        );

        System.out.println(DensityCompiler.generateCommands(root));


    }

    @Test
    @DisplayName("Test Native methods")
    void testNative() {
        DensityVM.Initialize();

        DensityFunction root = DensityFunctions.TwoArgumentSimpleFunction.create(
                DensityFunctions.TwoArgumentSimpleFunction.Type.ADD,
                new DensityFunctions.Constant(5), new DensityFunctions.Constant(1)
        );

        final DensityCompiler.Data data = DensityCompiler.generateCommands(root);
        long ptr = DensityVM.createVMContext(data);


        double[] result = new double[1000];
        for (int i = 0; i < result.length; i++) {
            result[i] = DensityVM.densityInvoke(ptr);
        }

        System.out.println(root.compute(null));
    }

    @Test
    void testPerformance() {
        DensityVM.Initialize();

        // Создаем более сложный граф, чтобы VM было что считать
        DensityFunction root = DensityFunctions.TwoArgumentSimpleFunction.create(
                DensityFunctions.TwoArgumentSimpleFunction.Type.ADD,
                DensityFunctions.TwoArgumentSimpleFunction.create(
                        DensityFunctions.TwoArgumentSimpleFunction.Type.MUL,
                        new DensityFunctions.Constant(5.0),
                        new DensityFunctions.Constant(2.0)
                ),
                new DensityFunctions.Constant(1.0)
        );

        final DensityCompiler.Data data = DensityCompiler.generateCommands(root);
        long ptr = DensityVM.createVMContext(data);

        int iterations = 10_000_000; // 10 миллионов запусков

        // --- WARMUP (Прогрев) ---
        System.out.println("Warming up...");
        for (int i = 0; i < 100_000; i++) {
            root.compute(null);
            DensityVM.densityInvoke(ptr);
        }

        // --- BENCHMARK JAVA ---
        System.out.println("Testing Java...");
        long startJava = System.nanoTime();
        double sumJava = 0;
        for (int i = 0; i < iterations; i++) {
            sumJava += root.compute(null);
        }
        long endJava = System.nanoTime();

        // --- BENCHMARK C++ (JNI Invoke) ---
        System.out.println("Testing C++ (via JNI)...");
        long startCpp = System.nanoTime();
        double sumCpp = 0;
        for (int i = 0; i < iterations; i++) {
            sumCpp += DensityVM.densityInvoke(ptr);
        }
        long endCpp = System.nanoTime();

        // --- ВЫВОД РЕЗУЛЬТАТОВ ---
        double javaTimeMs = (endJava - startJava) / 1_000_000.0;
        double cppTimeMs = (endCpp - startCpp) / 1_000_000.0;

        System.out.printf("Java: %.2f ms (avg: %.4f ns/op)\n", javaTimeMs, (double)(endJava - startJava) / iterations);
        System.out.printf("C++ : %.2f ms (avg: %.4f ns/op)\n", cppTimeMs, (double)(endCpp - startCpp) / iterations);

        // Чтобы JIT не вырезал циклы как неиспользуемые
        System.out.println("Check sums: " + sumJava + " | " + sumCpp);
    }

    @Test
    void testComparison() {
        // 1. Стандартный способ (Minecraft)

        NoiseSettings noiseSettings = new NoiseSettings(-64, 384, 1, 2);

        final var registries = VanillaRegistries.createLookup();
        RandomState randomState = RandomState.create(NoiseGeneratorSettings.dummy(), registries.lookupOrThrow(Registries.NOISE), 1L);

        ChunkPos chunkPos = new ChunkPos(new BlockPos(200, 10, 100));
        int id = 16 / noiseSettings.getCellWidth();

        final Aquifer.FluidStatus fluidLevel = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        NoiseChunk mcChunk = new NoiseChunk(id, randomState, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(), noiseSettings,
                DensityFunctions.BeardifierMarker.INSTANCE, NoiseGeneratorSettings.dummy(), (x, y, z) -> fluidLevel, Blender.EMPTY);



        int gridX = 16 / mcChunk.cellWidth + 1;  // 5 точек (0, 4, 8, 12, 16)
        int gridZ = 16 / mcChunk.cellWidth + 1;  // 5 точек
        int gridY = 384 / mcChunk.cellHeight + 1; // 49 точек

        double[] mcData = new double[128];

        DensityFunction root = DensityFunctions.TwoArgumentSimpleFunction.create(
                DensityFunctions.TwoArgumentSimpleFunction.Type.ADD,
                DensityFunctions.TwoArgumentSimpleFunction.create(
                        DensityFunctions.TwoArgumentSimpleFunction.Type.MUL,
                        new DensityFunctions.Constant(5.0),
                        new DensityFunctions.Constant(2.0)
                ),
                new DensityFunctions.Constant(1.0)
        );
        mcChunk.fillAllDirectly(mcData, root);

        //

        System.out.println(mcChunk);
        // ... заполни массив через fillAllDirectly ...

        double[] myData = FlatNoiseChunk.generateChunkDensities(mcChunk, root, chunkPos.x, chunkPos.z, mcChunk.cellWidth, mcChunk.cellHeight, 384);

        for (int i=0; i<myData.length; i++) {
            if (Math.abs(mcData[i] - myData[i]) > 0.0001) {
                throw new RuntimeException("Mismatch at index " + i);
            }
        }
        System.out.println("It works exactly the same!");
    }
}
