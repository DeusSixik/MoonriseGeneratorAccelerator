package dev.sixik.density_compiller.compiler.pipeline.configuration;

import dev.sixik.density_compiller.compiler.pipeline.DensityCompilerPipeline;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import org.objectweb.asm.MethodVisitor;

public record ByteCodeGeneratorStructure(int firstFreeLocal, int currentContextVar) {

    public boolean hasContext() {
        return currentContextVar != -1;
    }

    public PipelineAsmContext createContext(DensityCompilerPipeline pipeline, MethodVisitor mv, String ownerInternalName) {
        return new PipelineAsmContext(pipeline, mv, ownerInternalName, firstFreeLocal, currentContextVar);
    }
}
