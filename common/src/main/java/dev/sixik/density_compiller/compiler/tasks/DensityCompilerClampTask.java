package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.utils.DensityCompilerUtils;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.DALOAD;
import static org.objectweb.asm.Opcodes.DASTORE;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

public class DensityCompilerClampTask extends DensityCompilerTask<DensityFunctions.Clamp> {

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.Clamp node, PipelineAsmContext ctx) {
        ctx.visitNodeCompute(node.input());

        mv.visitLdcInsn(node.minValue());
        mv.visitLdcInsn(node.maxValue());

        DensityCompilerUtils.clamp(mv);
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.Clamp node, PipelineAsmContext ctx, int destArrayVar) {
        /*
            Filling the array with input data
         */
        ctx.visitNodeFill(node.input(), destArrayVar);

        /*
            We cache the boundaries so as not to pull getters in the loop.
         */
        double min = node.minValue();
        double max = node.maxValue();

        /*
            Using your arrayForI for In-place transformation
         */
        ctx.arrayForI(destArrayVar, (iVar) -> {
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);

            /*
                Duplicating the array reference and the index for DASTORE
             */
            mv.visitInsn(DUP2);

            /*
                Loading the current ds[i] value
             */
            mv.visitInsn(DALOAD);

            /*
                Executing the Clamp
             */
            mv.visitLdcInsn(min);
            mv.visitLdcInsn(max);
            mv.visitMethodInsn(INVOKESTATIC,
                    "net/minecraft/util/Mth",
                    "clamp",
                    "(DDD)D",
                    false);

            /*
                Writing back: ds[i] = result
             */
            mv.visitInsn(DASTORE);
        });
    }
}
