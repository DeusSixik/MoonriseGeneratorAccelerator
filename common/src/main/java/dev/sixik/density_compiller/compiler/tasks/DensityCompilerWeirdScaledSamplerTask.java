package dev.sixik.density_compiller.compiler.tasks;

import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerContext;
import dev.sixik.density_compiller.compiler.tasks_base.DensityCompilerTask;
import dev.sixik.density_compiller.compiler.wrappers.PublicNoiseWrapper;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

public class DensityCompilerWeirdScaledSamplerTask extends DensityCompilerTask<DensityFunctions.WeirdScaledSampler> {

    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String MAPPER = "it/unimi/dsi/fastutil/doubles/Double2DoubleFunction";
    private static final String WRAPPER = Type.getInternalName(PublicNoiseWrapper.class);

    @Override
    protected void compileCompute(MethodVisitor mv, DensityFunctions.WeirdScaledSampler node, DensityCompilerContext ctx) {
        ctx.emitLeafCallReference(mv, node);
        mv.visitTypeInsn(CHECKCAST, "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler");
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler", "rarityValueMapper", "()Lnet/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler$RarityValueMapper;", false);
        mv.visitFieldInsn(GETFIELD, "net/minecraft/world/level/levelgen/DensityFunctions$WeirdScaledSampler$RarityValueMapper", "mapper", "Lit/unimi/dsi/fastutil/doubles/Double2DoubleFunction;");

        ctx.compileNode(mv, node.input());

        mv.visitMethodInsn(INVOKEINTERFACE, MAPPER, "get", "(D)D", true);

        mv.visitVarInsn(DSTORE, 2);

        PublicNoiseWrapper noiseWrapper = new PublicNoiseWrapper(node.noise());
        ctx.emitLeafCallReference(mv, noiseWrapper);
        mv.visitTypeInsn(CHECKCAST, WRAPPER);
        mv.visitMethodInsn(INVOKEVIRTUAL, WRAPPER, "holder", "()L" + HOLDER + ";", false);

        // X
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockX", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, 2); // e
        mv.visitInsn(DDIV);

        // Y
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockY", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, 2); // e
        mv.visitInsn(DDIV);

        // Z
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, CTX, "blockZ", "()I", true);
        mv.visitInsn(I2D);
        mv.visitVarInsn(DLOAD, 2); // e
        mv.visitInsn(DDIV);

        mv.visitMethodInsn(INVOKEVIRTUAL, HOLDER, "getValue", "(DDD)D", false);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
        mv.visitVarInsn(DLOAD, 2);
        mv.visitInsn(DMUL);
    }
}
