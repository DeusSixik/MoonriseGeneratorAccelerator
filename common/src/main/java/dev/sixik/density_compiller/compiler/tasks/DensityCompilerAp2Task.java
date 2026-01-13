package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerMath;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.DADD;
import static org.objectweb.asm.Opcodes.DMUL;

public class DensityCompilerAp2Task extends DensityCompilerTask<DensityFunctions.Ap2> {
    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Ap2 node, DensityCompilerContext ctx) {
        ctx.compileNode(mv, node.argument1()); // Put double (+2 slots)
        ctx.compileNode(mv, node.argument2()); // Put double (+2 slots)

        switch (node.type()) {
            case ADD -> mv.visitInsn(DADD);
            case MUL -> mv.visitInsn(DMUL);
            case MIN -> DensityCompilerMath.min(mv);
            case MAX -> DensityCompilerMath.max(mv);
        }
    }
}
