package dev.sixik.density_compiler.pipeline;

import dev.sixik.asm.utils.DescriptorBuilder;
import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.DensityCompiler;
import dev.sixik.density_compiler.data.ByteCodeGeneratorStructure;
import dev.sixik.density_compiler.task_base.DensityCompilerTask;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import static org.objectweb.asm.Opcodes.*;

public class DensityComputePipeline implements CompilerPipeline {

    @Override
    public GeneratorAdapter generateMethod(DensityCompiler compiler, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {
        final String name = "compute";
        final String desc = DescriptorBuilder.builder()
                .type(DensityFunction.FunctionContext.class)
                .buildMethod(double.class);

        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        return new GeneratorAdapter(ACC_PUBLIC, new Method(name, desc), mv);
    }

    @Override
    public void generateMethodBody(DensityCompiler compiler, DCAsmContext ctx, DensityFunction root, String className, String simpleClassName, int id) {

        ctx.createNeedCache();

        ctx.readNode(root, DensityCompilerTask.Step.Compute);
        ctx.mv().visitInsn(DRETURN);
    }

    @Override
    public ByteCodeGeneratorStructure getByteCodeStructure(DensityCompiler compiler) {
        return new ByteCodeGeneratorStructure(2, 1);
    }
}
