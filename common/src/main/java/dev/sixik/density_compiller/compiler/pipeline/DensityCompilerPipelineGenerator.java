package dev.sixik.density_compiller.compiler.pipeline;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.pipeline.configuration.ByteCodeGeneratorStructure;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

public interface DensityCompilerPipelineGenerator {

    void apply(
            DensityCompilerPipeline pipeline,
            AsmCtx ctx,
            DensityFunction root,
            String className,
            String classSimpleName,
            int id
    );

    default MethodVisitor generateMethod(DensityCompilerPipeline pipeline, ClassWriter cw, DensityFunction root) {
        return null;
    }

    default ByteCodeGeneratorStructure getStructure() {
        return new ByteCodeGeneratorStructure(0, 0);
    }

}
