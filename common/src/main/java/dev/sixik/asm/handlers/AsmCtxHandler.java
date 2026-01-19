package dev.sixik.asm.handlers;

import dev.sixik.asm.BasicAsmContext;
import org.objectweb.asm.commons.GeneratorAdapter;

public interface AsmCtxHandler {

    GeneratorAdapter mv();

    BasicAsmContext ctx();
}
