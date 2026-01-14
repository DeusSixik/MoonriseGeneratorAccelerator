package dev.sixik.density_compiller.compiler.tasks_base;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.MethodVisitor;

public abstract class DensityCompilerTask<T extends DensityFunction> {

    public static final int COMPUTE = 1 << 1;
    public static final int FILL = 1 << 2;
    public static final int ALL = COMPUTE | FILL;

    public final void compileComputeImpl(MethodVisitor visitor, DensityFunction function, DensityCompilerContext context) {
        compileCompute(visitor, (T) function, context);
    }

    protected abstract void compileCompute(MethodVisitor mv, T node, DensityCompilerContext ctx);

    public void compileFill(MethodVisitor mv, T node, DensityCompilerContext ctx, int destArrayVar) {
        ctx.emitLeafFill(mv, node, destArrayVar);
    }

    public int buildBits() {
        return COMPUTE;
    }
}
