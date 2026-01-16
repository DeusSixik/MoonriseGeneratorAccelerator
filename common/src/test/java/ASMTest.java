import dev.sixik.density_compiller.compiler.data.DensityCompilerData;
import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.objectweb.asm.Opcodes.ARRAYLENGTH;

public class ASMTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DensityCompilerData.boot();
    }

    @Test()
    public void compileAllCasses() {
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(33, new ArrayList<>())), null);

        compileAll(List.of(
                new DensityFunctions.Constant(1),
                DensityFunctions.TwoArgumentSimpleFunction.create(DensityFunctions.TwoArgumentSimpleFunction.Type.ADD,
                        DensityFunctions.TwoArgumentSimpleFunction.create(DensityFunctions.TwoArgumentSimpleFunction.Type.MAX, new DensityFunctions.Constant(5), new DensityFunctions.Constant(1)),
                        DensityFunctions.TwoArgumentSimpleFunction
                                .create(DensityFunctions.TwoArgumentSimpleFunction.Type.MUL,
                                        new DensityFunctions.Constant(5),
                                        DensityFunctions.TwoArgumentSimpleFunction.create(DensityFunctions.TwoArgumentSimpleFunction.Type.MIN, new DensityFunctions.Constant(5), new DensityFunctions.Constant(1)))
                ),
                DensityFunctions.BlendAlpha.INSTANCE,
                DensityFunctions.BlendOffset.INSTANCE,
                DensityFunctions.BeardifierMarker.INSTANCE,
                new DensityFunctions.ShiftedNoise(
                        new DensityFunctions.Constant(1),
                        new DensityFunctions.Constant(2),
                        new DensityFunctions.Constant(3),
                        5,
                        6,
                        noise
                ),
                new DensityFunctions.RangeChoice(
                        new DensityFunctions.Constant(1),
                        10,
                        20,
                        new DensityFunctions.Constant(2),
                        new DensityFunctions.Constant(3)
                ),
                new DensityFunctions.ShiftA(noise),
                new DensityFunctions.ShiftB(noise),
                new DensityFunctions.Shift(noise),
                new DensityFunctions.Clamp(
                        new DensityFunctions.Constant(5),
                        1,
                        3
                ),
                new DensityFunctions.Mapped(
                        DensityFunctions.Mapped.Type.SQUARE,
                        new DensityFunctions.Constant(5),
                        1,
                        2
                ),
                new DensityFunctions.MulOrAdd(
                        DensityFunctions.MulOrAdd.Type.ADD,
                        new DensityFunctions.Constant(5),
                        5,
                        1,
                        3
                ),
                new DensityFunctions.Noise(
                        noise,
                        10,
                        20
                ),
                new DensityFunctions.WeirdScaledSampler(
                        new DensityFunctions.Constant(4),
                        noise,
                        DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1
                ),
                new DensityFunctions.BlendDensity(
                        new DensityFunctions.Constant(10)
                ),
                new DensityFunctions.YClampedGradient(
                        2,3,4,5
                ),
                new DensityFunctions.HolderHolder(Holder.direct(new DensityFunctions.Constant(10))),
                new DensityFunctions.Marker(DensityFunctions.Marker.Type.Cache2D, new DensityFunctions.Constant(4)),
                new DensityFunctions.EndIslandDensityFunction(4),
                new DensityFunctions.Ap2(DensityFunctions.TwoArgumentSimpleFunction.Type.ADD,
                        new DensityFunctions.Constant(1),
                        new DensityFunctions.Constant(3),
                        10, 20)
        ));
    }

    @Test
    public void generateClass() {
        final DensityFunction.NoiseHolder noise = new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(33, new ArrayList<>())), null);


//        DensityFunctions.RangeChoice densityFunction2 = new DensityFunctions.RangeChoice(
//                new DensityFunctions.Constant(50),
//                10,
//                20,
//                new DensityFunctions.Constant(50),
//                new DensityFunctions.Constant(1000)
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

//        final var dens = new DensityFunctions.BlendDensity(new DensityFunctions.YClampedGradient(5, 1, 2, 1));
//        final var dens = new DensityFunctions.BlendDensity(new DensityFunctions.Constant(1));
        DensityFunction c1 = new DensityFunctions.Constant(404);
        DensityFunction c2 = new DensityFunctions.Constant(-804);

        DensityFunctions.ShiftedNoise dens = new DensityFunctions.ShiftedNoise(c2, c1, c2, 10, 20, new DensityFunction.NoiseHolder(Holder.direct(new NormalNoise.NoiseParameters(10, new ArrayList<>())), null));

        DensityCompilerPipeline.from(dens, true).startCompilation();

        //        DensityFunctions.Constant constant = new DensityFunctions.Constant(5);
//
//        TestCompiler compiler = new TestCompiler(this::method);
//        compiler.compile(constant);
    }


    private void compileAll(Collection<DensityFunction> functions) {
        for (DensityFunction function : functions) {
            try {
                DensityCompilerPipeline.from(function, true).startCompilation();
            } catch (Exception e) {
                System.out.println("ERROR on: " + function.getClass().getName());
            }
        }
    }
}
