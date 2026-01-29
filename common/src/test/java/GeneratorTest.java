import dev.sixik.density_interpreter.DensityCompiler;
import dev.sixik.density_interpreter.DensityVM;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

        RandomSource source = RandomSource.create(256);

        ImprovedNoise noise = new ImprovedNoise(source);

        long ptr = DensityVM.createNativeImprovedNoise(noise.xo, noise.yo, noise.zo, noise.p);

        System.out.println(ptr);
        System.out.println(noise.noise(20, 5, 1));
        System.out.println(DensityVM.noise(ptr, 20, 5, 1, 0, 0));
    }
}
