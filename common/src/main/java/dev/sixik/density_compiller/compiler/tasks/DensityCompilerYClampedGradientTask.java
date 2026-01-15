package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerYClampedGradientTask extends DensityCompilerTask<DensityFunctions.YClampedGradient> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.YClampedGradient node, PipelineAsmContext ctx) {
        // (double) functionContext.blockY()
//        ctx.loadContext();

        ctx.loadBlockY();
//        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
//        mv.visitInsn(I2D);

        // Параметры градиента
        mv.visitLdcInsn((double) node.fromY());
        mv.visitLdcInsn((double) node.toY());
        mv.visitLdcInsn(node.fromValue());
        mv.visitLdcInsn(node.toValue());

        // Используем инлайновую версию для скорости
        DensityCompilerUtils.clampedMap(mv);
    }
}
