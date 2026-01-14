package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;


public class DensityCompilerEndIslandTask extends DensityCompilerTask<DensityFunctions.EndIslandDensityFunction> {
    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.EndIslandDensityFunction node, PipelineAsmContext ctx) {
//        ctx.emitLeafCallReference(mv, node);
//
//        mv.visitTypeInsn(CHECKCAST, "net/minecraft/world/level/levelgen/DensityFunctions$EndIslandDensityFunction");
//
//        ctx.loadContext(mv);
//
//        mv.visitMethodInsn(INVOKESTATIC,
//                "dev/sixik/density_compiller/compiler/wrappers/EndIslandHelper", // Твой путь к хелперу
//                "fastCompute",
//                "(Lnet/minecraft/world/level/levelgen/DensityFunctions$EndIslandDensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
//                false);
    }
}
