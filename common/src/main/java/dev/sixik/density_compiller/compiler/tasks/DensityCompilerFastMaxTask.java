package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerMath;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerFastMaxTask extends DensityCompilerTask<DensitySpecializations.FastMax> {


    @Override
    protected void compileCompute(MethodVisitor visitor, DensitySpecializations.FastMax function, DensityCompilerContext context) {
        context.compileNode(visitor, function.a());
        context.compileNode(visitor, function.b());
        DensityCompilerMath.max(visitor);
    }
}
