package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import org.objectweb.asm.MethodVisitor;

public class DensityCompilerFastMinTask extends DensityCompilerTask<DensitySpecializations.FastMin> {


    @Override
    protected void compileCompute(MethodVisitor visitor, DensitySpecializations.FastMin function, DensityCompilerContext context) {
        context.compileNodeCompute(visitor, function.a());
        context.compileNodeCompute(visitor, function.b());
        DensityCompilerUtils.min(visitor);
    }
}
