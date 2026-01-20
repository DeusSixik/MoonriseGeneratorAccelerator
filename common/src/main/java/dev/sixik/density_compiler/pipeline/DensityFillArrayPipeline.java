package dev.sixik.density_compiler.pipeline;

import dev.sixik.asm.utils.DescriptorBuilder;
import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.DensityCompiler;
import dev.sixik.density_compiler.data.ByteCodeGeneratorStructure;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

public class DensityFillArrayPipeline implements CompilerPipeline{

    @Override
    public GeneratorAdapter generateMethod(DensityCompiler compiler, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {
        final String name = "fillArray";
        final String desc = DescriptorBuilder.builder()
                .array(double.class)
                .type(DensityFunction.ContextProvider.class)
                .buildMethodVoid();

        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        return new GeneratorAdapter(ACC_PUBLIC, new Method(name, desc), mv);
    }

    @Override
    public void generateMethodBody(DensityCompiler compiler, DCAsmContext ctx, DensityFunction root, String className, String simpleClassName, int id) {
        final MethodVisitor mv = ctx.mv();
        int destArrayArgIndex = 0;

        ctx.putCachedVariable("destArrayVar", destArrayArgIndex);

        ctx.arrayForI(destArrayArgIndex, (iVar) -> {
            // ... внутри лямбды ...
            GeneratorAdapter ga = (GeneratorAdapter) ctx.mv();

            // Правильная загрузка аргументов через адаптер
            ga.loadArg(destArrayArgIndex); // Загрузит ALOAD 1 (так как Arg 0 = Slot 1)
            ga.loadLocal(iVar);            // Загрузит ILOAD X (где X - слот счетчика)

            ctx.readNode(root, DensityCompilerTask.Step.Compute);

            ga.arrayStore(Type.DOUBLE_TYPE);
        });

        ctx.mv().visitInsn(RETURN);
    }

    @Override
    public ByteCodeGeneratorStructure getByteCodeStructure(DensityCompiler compiler) {
        return new ByteCodeGeneratorStructure(5, 2);
    }
}
