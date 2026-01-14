package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static dev.sixik.density_compiller.compiler.DensityCompiler.CTX;
import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerBlendDensityTask extends DensityCompilerTask<DensityFunctions.BlendDensity> {

    private static final String BLENDER = "net/minecraft/world/level/levelgen/blending/Blender";

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx) {
        ctx.loadContext(); // Blender
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "getBlender", "()L" + BLENDER + ";", true);

        ctx.loadContext(); // Context

        ctx.visitNodeCompute(node.input()); // input_double

        mv.visitMethodInsn(INVOKEVIRTUAL, BLENDER, "blendDensity", "(L" + CTX + ";D)D", false);

        // blender.blendDensity(context, double)
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.BlendDensity node, PipelineAsmContext ctx, int destArrayVar) {
        /*
            First, we fill the array with input data
         */
        ctx.visitNodeFill(node.input(), destArrayVar);

        /*
            We need a Blender object to work with.
            We get it from the provider (through the first available context) or better through a local variable.
         */
        int blenderVar = ctx.newLocalInt();

        /*
            Command flow: ContextProvider.forIndex(0).getBlender()
         */
        ctx.aload(2); // ContextProvider
        ctx.iconst(0);
//        ctx.invokeProviderForIndex();
//        ctx.invokeContextInterface("getBlender",
//                DescriptorBuilder.builder().type(Blender.class).buildMethodVoid()
//        );

        mv.visitMethodInsn(INVOKEINTERFACE,
                "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider",
                "forIndex",
                "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;",
                true);
        mv.visitMethodInsn(INVOKEINTERFACE,
                CTX,
                "getBlender",
                "()L" + BLENDER + ";",
                true);
        ctx.astore(blenderVar);

        ctx.arrayForI(destArrayVar, (iVar) -> {

            /*
                We are preparing the stack
                for: ds[i] = blender.blendDensity(ContextProvider.forIndex(i), ds[i])
             */
            ctx.aload(destArrayVar);    // For the DASTORE at the end
            ctx.iload(iVar);            // For the DASTORE at the end
            ctx.aload(blenderVar);      // Target: Blender

            /*
                Getting the context for the current index
             */
            ctx.aload(2); // ContextProvider
            ctx.iload(iVar);
//            ctx.invokeProviderForIndex();
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider",
                    "forIndex",
                    "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;",
                    true);

            /*
                Getting the current value from the ds[i] array
             */
            ctx.aload(destArrayVar);
            ctx.iload(iVar);
            mv.visitInsn(DALOAD);

            /*
                Calling blendDensity(context, double)
             */

//            ctx.invokeVirtual(
//                    DescriptorBuilder.builder().type(Blender.class).build(),
//                    "blendDensity",
//                    DescriptorBuilder.builder().type(DensityFunction.FunctionContext.class).d().buildMethod(double.class)
//            );
            mv.visitMethodInsn(INVOKEVIRTUAL, BLENDER,
                    "blendDensity",
                    "(L" + CTX + ";D)D",
                    false);

            /*
                Writing the result back to ds[i]
             */
            mv.visitInsn(DASTORE);
        });
    }
}
