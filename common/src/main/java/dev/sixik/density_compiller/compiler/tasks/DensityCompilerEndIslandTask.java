package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;


public class DensityCompilerEndIslandTask extends DensityCompilerTask<DensityFunctions.EndIslandDensityFunction> {
    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.EndIslandDensityFunction node, PipelineAsmContext ctx) {
        ctx.visitLeafReference(node);

        mv.visitTypeInsn(CHECKCAST,
                Type.getInternalName(DensityFunctions.EndIslandDensityFunction.class));

        ctx.loadContext();

//        ctx.invokeStatic(
//                DescriptorBuilder.builder().type(EndIslandHelper.class).build(),
//                "fastCompute",
//                DescriptorBuilder.builder()
//                        .type(DensityFunctions.EndIslandDensityFunction.class)
//                        .type(DensityFunction.FunctionContext.class)
//                        .buildMethodVoid()
//        );

        mv.visitMethodInsn(INVOKESTATIC,
                "dev/sixik/density_compiller/compiler/wrappers/EndIslandHelper", // Твой путь к хелперу
                "fastCompute",
                "(Lnet/minecraft/world/level/levelgen/DensityFunctions$EndIslandDensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
                false);
    }
}
