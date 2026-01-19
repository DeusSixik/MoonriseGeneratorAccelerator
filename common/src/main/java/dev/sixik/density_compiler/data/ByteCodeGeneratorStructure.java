package dev.sixik.density_compiler.data;

import dev.sixik.density_compiler.DCAsmContext;
import dev.sixik.density_compiler.DensityCompiler;
import org.objectweb.asm.commons.GeneratorAdapter;

public record ByteCodeGeneratorStructure(int firstFreeLocal, int currentContextVar) {

    public DCAsmContext createContext(DensityCompiler compiler, GeneratorAdapter mv) {
        return new DCAsmContext(compiler, mv, firstFreeLocal, currentContextVar);
    }
}
