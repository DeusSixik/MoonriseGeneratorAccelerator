package dev.sixik.density_compiller.compiler.pipeline.configuration;

import dev.sixik.asm.AsmCtx;
import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import org.objectweb.asm.MethodVisitor;

public record ByteCodeGeneratorStructure(int firstFreeLocal, int currentContextVar) {

    public boolean hasContext() {
        return currentContextVar >= 0;
    }

    public PipelineAsmContext createContext(MethodVisitor mv, String ownerInternalName) {
        return new PipelineAsmContext(mv, ownerInternalName, firstFreeLocal, currentContextVar);
    }
}
