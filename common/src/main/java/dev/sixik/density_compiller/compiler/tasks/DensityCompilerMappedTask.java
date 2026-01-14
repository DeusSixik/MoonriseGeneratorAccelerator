package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMappedTask extends DensityCompilerTask<DensityFunctions.Mapped> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Mapped node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input());
        generateTransformMath(mv, node.type());
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.Mapped node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.visitNodeFill(node.input(), destArrayVar);

        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DUP2); // For DASTORE on end

            mv.visitInsn(DALOAD); // Load ds[i]

            generateTransformMath(mv, node.type());

            mv.visitInsn(DASTORE);
        });
    }

    private void generateTransformMath(MethodVisitor mv, DensityFunctions.Mapped.Type type) {
        switch (type) {
            case ABS -> DensityCompilerUtils.abs(mv);
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
            case HALF_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.5);
            case QUARTER_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.25);
            case SQUEEZE -> DensityCompilerUtils.compileSqueeze(mv);
        }
    }
}
