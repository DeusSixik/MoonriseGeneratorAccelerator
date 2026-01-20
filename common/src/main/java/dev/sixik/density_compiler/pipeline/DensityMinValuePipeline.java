package dev.sixik.density_compiler.pipeline;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.DensityCompiler;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.DRETURN;

public class DensityMinValuePipeline implements CompilerPipeline {

    @Override
    public GeneratorAdapter generateMethod(DensityCompiler compiler, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {
        final String name = "minValue";
        final String desc = "()D";

        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        return new GeneratorAdapter(ACC_PUBLIC, new Method(name, desc), mv);
    }

    @Override
    public void generateMethodBody(DensityCompiler compiler, DCAsmContext ctx, DensityFunction root, String className, String simpleClassName, int id) {
        final MethodVisitor mv = ctx.mv();
        mv.visitLdcInsn(root.minValue());
        mv.visitInsn(DRETURN);
    }
}
