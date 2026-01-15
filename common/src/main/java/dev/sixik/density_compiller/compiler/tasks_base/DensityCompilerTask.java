package dev.sixik.density_compiller.compiler.tasks_base;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.MethodVisitor;

public abstract class DensityCompilerTask<T extends DensityFunction> {

    public static final int COMPUTE = 1 << 1;
    public static final int FILL = 1 << 2;
    public static final int ALL = COMPUTE | FILL;

    public final void compileComputeImpl(MethodVisitor visitor, DensityFunction function, PipelineAsmContext context) {
        compileCompute(visitor, (T) function, context);
    }

    protected abstract void compileCompute(MethodVisitor mv, T node, PipelineAsmContext ctx);

    protected void prepareCompute(MethodVisitor mv, T node, PipelineAsmContext ctx) {}

    public int buildBits() {
        return ALL;
    }
}
