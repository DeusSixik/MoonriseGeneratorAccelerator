package dev.sixik.density_compiller.compiler.tasks_base;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.MethodVisitor;

public abstract class DensityCompilerTask<T extends DensityFunction> {

    public final void compileComputeImpl(MethodVisitor visitor, DensityFunction function, DensityCompilerContext context) {
        compileCompute(visitor, (T) function, context);
    }

    protected abstract void compileCompute(MethodVisitor mv, T node, DensityCompilerContext ctx);
}
