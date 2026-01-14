package dev.sixik.density_compiller.compiler.pipeline.configuration;

import dev.sixik.asm.AsmCtx;
import org.objectweb.asm.MethodVisitor;

public record ByteCodeGeneratorStructure(int firstFreeLocal, int currentContextVar) {

    public AsmCtx createContext(MethodVisitor mv, String ownerInternalName) {
        return new AsmCtx(mv, ownerInternalName, firstFreeLocal, currentContextVar);
    }
}
