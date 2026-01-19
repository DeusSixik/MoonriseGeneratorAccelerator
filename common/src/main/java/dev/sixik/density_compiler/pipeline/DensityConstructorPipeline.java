package dev.sixik.density_compiler.pipeline;

import dev.sixik.asm.utils.DescriptorBuilder;
import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.DensityCompiler;
import dev.sixik.density_compiler.data.ByteCodeGeneratorStructure;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.Map;

import static dev.sixik.density_compiler.DCAsmContext.*;

public class DensityConstructorPipeline implements CompilerPipeline {

    @Override
    public ByteCodeGeneratorStructure getByteCodeStructure(DensityCompiler compiler) {
        return new ByteCodeGeneratorStructure(2, -1);
    }

    @Override
    public GeneratorAdapter generateMethod(DensityCompiler compiler, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {
        final Map<Object, Integer> leavesMap = compiler.locals.leafToId;
        final String name = "<init>";
        final String desc = leavesMap.isEmpty()
                ? DescriptorBuilder.builder().buildMethodVoid()
                : DescriptorBuilder.builder().array(Object.class).buildMethodVoid();

        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        return new GeneratorAdapter(ACC_PUBLIC, new Method(name, desc), mv);
    }

    @Override
    public void generateMethodBody(DensityCompiler compiler, DCAsmContext ctx, DensityFunction root, String className, String simpleClassName, int id) {
        final var mv = ctx.mv();

        mv.loadThis();
        mv.visitMethodInsn(INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                DescriptorBuilder.builder().buildMethodVoid(),
                false);

        Map<Object, Integer> leavesMap = compiler.locals.leafToId;
        for (var entry : leavesMap.entrySet()) {
            int index = entry.getValue();
            String desc = compiler.locals.leafTypes.getOrDefault(index, "Lnet/minecraft/world/level/levelgen/DensityFunction;");
            String internalName = Type.getType(desc).getInternalName();

            mv.loadThis();
            mv.visitVarInsn(ALOAD, 1); // Массив Object[] (бывший DensityFunction[])
            mv.push(index);
            mv.visitInsn(AALOAD);

            // ВАЖНО: Кастим Object к конкретному типу поля (NoiseHolder, Spline...)
            mv.visitTypeInsn(CHECKCAST, internalName);

            mv.visitFieldInsn(PUTFIELD, className, DEFAULT_LEAF_FUNCTION_NAME.apply(entry.getKey()) + "_" + index, desc);
        }

        mv.visitInsn(RETURN);
    }

    @Override
    public void generateClassField(DensityCompiler compiler, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {
        final Map<Object, Integer> leavesMap = compiler.locals.leafToId;

        for (var entry : leavesMap.entrySet()) {
            int index = entry.getValue();
            String desc = compiler.locals.leafTypes.getOrDefault(index, "Lnet/minecraft/world/level/levelgen/DensityFunction;");

            cw.visitField(ACC_PRIVATE | ACC_FINAL,
                    DEFAULT_LEAF_FUNCTION_NAME.apply(entry.getKey()) + "_" + index,
                    desc, null, null).visitEnd();
        }
    }
}
