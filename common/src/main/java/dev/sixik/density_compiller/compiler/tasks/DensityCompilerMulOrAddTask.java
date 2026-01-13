package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.DADD;
import static org.objectweb.asm.Opcodes.DMUL;

public class DensityCompilerMulOrAddTask extends DensityCompilerTask<DensityFunctions.MulOrAdd> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.MulOrAdd node, DensityCompilerContext ctx) {
        ctx.compileNodeCompute(mv, node.input());

        switch (node.specificType()) {
            case MUL -> {
                mv.visitLdcInsn(node.argument());
                mv.visitInsn(DMUL);
            }
            case ADD -> {
                mv.visitLdcInsn(node.argument());
                mv.visitInsn(DADD);
            }
        }
    }
}
