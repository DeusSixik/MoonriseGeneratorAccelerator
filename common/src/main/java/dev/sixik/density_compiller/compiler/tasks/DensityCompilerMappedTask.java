package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerMappedTask extends DensityCompilerTask<DensityFunctions.Mapped> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Mapped node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input());
        generateTransformMath(mv, node.type(), node.input(), ctx);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.Mapped node, PipelineAsmContext ctx, int destArrayVar) {
        ctx.visitNodeFill(node.input(), destArrayVar);

        // Оптимизация: Если операция ABS и вход уже положительный -> ничего не делаем
        if (node.type() == DensityFunctions.Mapped.Type.ABS && node.input().minValue() >= 0.0) {
            return;
        }

        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DUP2); // Stack: [Arr, I, Arr, I]

            mv.visitInsn(DALOAD); // Stack: [Arr, I, Val]

            generateTransformMath(mv, node.type(), node.input(), ctx);

            mv.visitInsn(DASTORE);
        });
    }

    private void generateTransformMath(MethodVisitor mv, DensityFunctions.Mapped.Type type, DensityFunction input, PipelineAsmContext ctx) {
        switch (type) {
            case ABS -> { /* ... */ }
            case SQUARE -> { /* ... */ }
            case CUBE -> { /* ... */ }
            case HALF_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.5);
            case QUARTER_NEGATIVE -> DensityCompilerUtils.compileNegativeFactor(mv, 0.25);
            case SQUEEZE -> {
                boolean needsClamp = input.minValue() < -1.0 || input.maxValue() > 1.0;
                // Теперь передаем ctx
                DensityCompilerUtils.compileSqueeze(mv, ctx, needsClamp);
            }
        }
    }
}
