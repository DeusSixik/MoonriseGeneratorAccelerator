import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.objectweb.asm.Opcodes.ARRAYLENGTH;

public class ASMTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DensityCompilerData.boot();
    }

    @Test
    public void generateClass() {
//        DensityFunctions.RangeChoice densityFunction2 = new DensityFunctions.RangeChoice(
//                new DensityFunctions.Constant(50),
//                10,
//                20,
//                new DensityFunctions.Constant(50),
//                new DensityFunctions.Constant(0)
//        );
//        DensityCompilerPipeline.from(
//                new DensityFunctions.RangeChoice(
//                        new DensityFunctions.RangeChoice(
//                                new DensityFunctions.Constant(50),
//                                10,
//                                20,
//                                densityFunction2,
//                                new DensityFunctions.Constant(0)
//                        ),
//                        10,
//                        20,
//                        densityFunction2,
//                        new DensityFunctions.Constant(0)
//                ),
//                 true
//        ).startCompilation();

//        DensityCompilerPipeline.from(
//                new DensityFunctions.Constant(5),
//                 true
//        ).startCompilation();

        DensityCompilerPipeline.from(new DensityFunctions.YClampedGradient(5, 1, 2, 1), true).startCompilation();

        //        DensityFunctions.Constant constant = new DensityFunctions.Constant(5);
//
//        TestCompiler compiler = new TestCompiler(this::method);
//        compiler.compile(constant);
    }


}
