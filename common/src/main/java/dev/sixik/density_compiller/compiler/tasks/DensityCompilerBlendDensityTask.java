package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerBlendDensityTask extends DensityCompilerTask<DensityFunctions.BlendDensity> {

    private static final String BLENDER = "net/minecraft/world/level/levelgen/blending/Blender";

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.BlendDensity node, DensityCompilerContext ctx) {
        ctx.loadContext(mv); // Blender
        mv.visitMethodInsn(INVOKEINTERFACE, ctx.CTX(), "getBlender", "()L" + BLENDER + ";", true);

        ctx.loadContext(mv); // Context

        ctx.compileNodeCompute(mv, node.input()); // input_double

        mv.visitMethodInsn(INVOKEVIRTUAL, BLENDER, "blendDensity", "(L" + ctx.CTX() + ";D)D", false);

        // blender.blendDensity(context, double)
    }

    @Override
    public void compileFill(MethodVisitor mv, DensityFunctions.BlendDensity node, DensityCompilerContext ctx, int destArrayVar) {
        /*
            First, we fill the array with input data
         */
        ctx.compileNodeFill(node.input(), destArrayVar);

        /*
            We need a Blender object to work with.
            We get it from the provider (through the first available context) or better through a local variable.
         */
        int blenderVar = ctx.allocateLocalVarIndex();

        /*
            Command flow: ContextProvider.forIndex(0).getBlender()
         */
        mv.visitVarInsn(ALOAD, 2); // ContextProvider
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEINTERFACE,
                "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider",
                "forIndex",
                "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;",
                true);
        mv.visitMethodInsn(INVOKEINTERFACE,
                ctx.CTX(),
                "getBlender",
                "()L" + BLENDER + ";",
                true);
        mv.visitVarInsn(ASTORE, blenderVar);

        ctx.arrayForI(destArrayVar, (iVar) -> {

            /*
                We are preparing the stack
                for: ds[i] = blender.blendDensity(ContextProvider.forIndex(i), ds[i])
             */
            mv.visitVarInsn(ALOAD, destArrayVar); // For the DASTORE at the end
            mv.visitVarInsn(ILOAD, iVar);         // For the DASTORE at the end

            mv.visitVarInsn(ALOAD, blenderVar);  // Target: Blender

            /*
                Getting the context for the current index
             */
            mv.visitVarInsn(ALOAD, 2); // ContextProvider
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitMethodInsn(INVOKEINTERFACE,
                    "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider",
                    "forIndex",
                    "(I)Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;",
                    true);

            /*
                Getting the current value from the ds[i] array
             */
            mv.visitVarInsn(ALOAD, destArrayVar);
            mv.visitVarInsn(ILOAD, iVar);
            mv.visitInsn(DALOAD);

            /*
                Calling blendDensity(context, double)
             */
            mv.visitMethodInsn(INVOKEVIRTUAL, BLENDER,
                    "blendDensity",
                    "(L" + ctx.CTX() + ";D)D",
                    false);

            /*
                Writing the result back to ds[i]
             */
            mv.visitInsn(DASTORE);
        });
    }
}
