package dev.sixik.density_compiller.compiler.tasks.depre;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.wrappers.CompiledCoordinate;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.List;

@Deprecated
public class DensityCompilerSplineTask extends DensityCompilerTask<DensityFunctions.Spline> {
    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Spline node, PipelineAsmContext ctx) {
        CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> originalSpline = node.spline();

        /*
             Deep copy of the spline with compilation of all coordinates inside
             mapAll will traverse the entire spline tree
         */
//        ctx.compileNodeCompute(mv, new DensityFunctionSplineWrapper(recursiveCreateCompute(mv, ctx, originalSpline)));
    }

    private CubicSpline<DensityFunctions.Spline.Point, CompiledCoordinate> recursiveCreateCompute(
            MethodVisitor mv,
            PipelineAsmContext ctx,
            CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> originalSpline
    ) {
        final CubicSpline<DensityFunctions.Spline.Point, CompiledCoordinate> rewrite;

        if (originalSpline instanceof CubicSpline.Constant<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> compiledSpline) {
            rewrite = CubicSpline.constant(compiledSpline.value());
        } else if (originalSpline instanceof CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> ms) {
            rewrite = new CubicSpline.Multipoint<>(
                    new CompiledCoordinate(null /*ctx.compiler().compile(ms.coordinate().function().value())*/),
                    ms.locations(),
                    splineCoordinateToCompiledCoordinate(mv, ctx, ms.values()),
                    ms.derivatives(),
                    ms.minValue(),
                    ms.maxValue()
            );
        } else throw new UnsupportedOperationException("Can't compile type: " + originalSpline.getClass().getName());

        return rewrite;
    }

    private List<CubicSpline<DensityFunctions.Spline.Point, CompiledCoordinate>> splineCoordinateToCompiledCoordinate(
            MethodVisitor mv,
            PipelineAsmContext ctx,
            List<CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>> orig
    ) {

        final List<CubicSpline<DensityFunctions.Spline.Point, CompiledCoordinate>> newList = new ArrayList<>();

        for (int i = 0; i < orig.size(); i++) {
            final var element = orig.get(i);
            newList.add(recursiveCreateCompute(mv, ctx, element));
        }

        return newList;
    }

}
