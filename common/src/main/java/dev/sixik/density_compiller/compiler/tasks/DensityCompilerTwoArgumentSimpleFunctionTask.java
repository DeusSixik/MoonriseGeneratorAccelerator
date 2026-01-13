package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerMath;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.DADD;
import static org.objectweb.asm.Opcodes.DMUL;

public class DensityCompilerTwoArgumentSimpleFunctionTask extends
        DensityCompilerTask<DensityFunctions.TwoArgumentSimpleFunction> {

    @Override
    protected void compileCompute(MethodVisitor visitor,
                                  DensityFunctions.TwoArgumentSimpleFunction function,
                                  DensityCompilerContext context
    ) {
        context.compileNode(visitor, function.argument1());
        context.compileNode(visitor, function.argument2());
        switch (function.type()) {
            case ADD -> visitor.visitInsn(DADD);
            case MUL -> visitor.visitInsn(DMUL);
            case MIN -> DensityCompilerMath.min(visitor);
            case MAX -> DensityCompilerMath.max(visitor);
        }
    }
}
