package dev.sixik.density_compiler.handlers;

import dev.sixik.density_compiler.DCAsmContext;
import org.objectweb.asm.commons.GeneratorAdapter;

public interface DCAsmHandler {

    GeneratorAdapter mv();

    DCAsmContext dctx();
}
