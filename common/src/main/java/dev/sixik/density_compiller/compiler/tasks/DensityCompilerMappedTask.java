package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerMath;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.DMUL;
import static org.objectweb.asm.Opcodes.DUP2;

public class DensityCompilerMappedTask extends DensityCompilerTask<DensityFunctions.Mapped> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Mapped node, DensityCompilerContext ctx) {
        ctx.compileNode(mv, node.input());

        switch (node.type()) {
            case ABS -> DensityCompilerMath.abs(mv);
            case SQUARE -> {
                mv.visitInsn(DUP2);
                mv.visitInsn(DMUL);
            }
            case CUBE -> {
                mv.visitInsn(DUP2);
                mv.visitInsn(DUP2);
                mv.visitInsn(DMUL);
                mv.visitInsn(DMUL);
            }
            case HALF_NEGATIVE -> DensityCompilerMath.compileNegativeFactor(mv, 0.5);
            case QUARTER_NEGATIVE -> DensityCompilerMath.compileNegativeFactor(mv, 0.25);
            case SQUEEZE -> DensityCompilerMath.compileSqueeze(mv);
        }
    }
}
