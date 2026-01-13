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
        mv.visitVarInsn(ALOAD, 1); // Blender
        mv.visitMethodInsn(INVOKEINTERFACE, ctx.CTX(), "getBlender", "()L" + BLENDER + ";", true);

        mv.visitVarInsn(ALOAD, 1); // Context

        ctx.compileNode(mv, node.input()); // input_double

        mv.visitMethodInsn(INVOKEVIRTUAL, BLENDER, "blendDensity", "(L" + ctx.CTX() + ";D)D", false);

        // blender.blendDensity(context, double)
    }
}
