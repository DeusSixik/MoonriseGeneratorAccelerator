package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.DADD;

public class DensityCompilerFastAddTask extends DensityCompilerTask<DensitySpecializations.FastAdd> {


    @Override
    protected void compileCompute(MethodVisitor visitor, DensitySpecializations.FastAdd function, DensityCompilerContext context) {
        context.compileNode(visitor, function.a());
        context.compileNode(visitor, function.b());
        visitor.visitInsn(DADD);
    }
}
