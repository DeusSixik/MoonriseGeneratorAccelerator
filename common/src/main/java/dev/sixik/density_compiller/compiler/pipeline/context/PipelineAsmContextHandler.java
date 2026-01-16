package dev.sixik.density_compiller.compiler.pipeline.context;

import dev.sixik.asm.AsmCtx;
import org.objectweb.asm.Opcodes;

public interface PipelineAsmContextHandler extends Opcodes {

    ContextCache cache();

    AsmCtx ctx();
}
