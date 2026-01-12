import dev.sixik.density_compiller.compiler.DensityCompiler;
import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        return Stream.of(
                Arguments.of(new DensitySpecializations.FastAdd(new DensityFunctions.Constant(5), new DensityFunctions.Constant(5)), "AddTest", 10.0),
                Arguments.of(new DensitySpecializations.FastMul(new DensityFunctions.Constant(3), new DensityFunctions.Constant(4)), "MulTest", 12.0),
                Arguments.of(new DensitySpecializations.FastMin(new DensityFunctions.Constant(-5), new DensityFunctions.Constant(10)), "MinTest", -5.0),
                Arguments.of(new DensitySpecializations.FastMax(new DensityFunctions.Constant(-5), new DensityFunctions.Constant(10)), "MaxTest", 10),
                Arguments.of(DensityFunctions.BlendOffset.INSTANCE, "BlendOffset", 0),
                Arguments.of(DensityFunctions.BlendAlpha.INSTANCE, "BlendAlpha", 1)
        );
    }

    /**
     * Compiles, dumps, and verifies the result of the calculation.
     */
    private void compileAndVerify(DensityFunction root, String fileName, double expectedResult) {
        System.out.println("Compiling " + fileName + "...");
        try {
            DensityCompiler compiler = new DensityCompiler();
            compiler.compileAndDump(root, fileName);

            /*
                We are also creating an instance to check that it does not crash in runtime.
             */
            DensityFunction compiled = compiler.compile(root);

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
}
