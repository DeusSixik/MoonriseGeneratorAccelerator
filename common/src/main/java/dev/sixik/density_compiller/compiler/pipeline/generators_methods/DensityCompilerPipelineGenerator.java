package dev.sixik.density_compiller.compiler.pipeline.generators_methods;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.configuration.ByteCodeGeneratorStructure;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

public interface DensityCompilerPipelineGenerator {

    default boolean ignore(DensityCompilerPipeline pipeline) {
        return false;
    }

    void applyMethod(
            DensityCompilerPipeline pipeline,
            PipelineAsmContext ctx,
            DensityFunction root,
            String className,
            String classSimpleName,
            int id
    );

    default MethodVisitor generateMethod(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root) {
        return null;
    }

    default ByteCodeGeneratorStructure getStructure(DensityCompilerPipeline pipeline) {
        return new ByteCodeGeneratorStructure(5, -1);
    }

    default void generateClassField(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root, String className, String simpleClassName, int id) {}

}
