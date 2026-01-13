package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.moonrisegeneratoraccelerator.common.level.levelgen.DensitySpecializations;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.DMUL;

public class DensityCompilerFastMulTask extends DensityCompilerTask<DensitySpecializations.FastMul> {


    @Override
    protected void compileCompute(MethodVisitor visitor, DensitySpecializations.FastMul function, DensityCompilerContext context) {
        context.compileNodeCompute(visitor, function.a());
        context.compileNodeCompute(visitor, function.b());
        visitor.visitInsn(DMUL);
    }
}
