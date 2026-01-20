import dev.sixik.density_compiler.DensityCompiler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SomeTest {

    @BeforeAll
    static void onStart() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void testFobos() {
        final var density = new DensityFunctions.Ap2(
                DensityFunctions.TwoArgumentSimpleFunction.Type.MIN,
                new DensityFunctions.Ap2(DensityFunctions.TwoArgumentSimpleFunction.Type.MAX,
                        new DensityFunctions.Constant(5),
                        new DensityFunctions.Constant(3),
                1,5
                ),
                new DensityFunctions.Constant(4),
                1,5
        );

        DensityCompiler.from(density, true).compile();
    }
}
