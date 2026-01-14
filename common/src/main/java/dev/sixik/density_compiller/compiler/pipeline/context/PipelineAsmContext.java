package dev.sixik.density_compiller.compiler.pipeline.context;

import dev.sixik.asm.AsmCtx;
import org.objectweb.asm.MethodVisitor;

public class PipelineAsmContext extends AsmCtx {

    public PipelineAsmContext(
            MethodVisitor mv,
            String ownerInternalName,
            int firstFreeLocal,
            int currentContextVar
    ) {
        super(mv, ownerInternalName, firstFreeLocal, currentContextVar);
    }
}
