package dev.sixik.density_compiller.compiler.tasks_base;

import dev.sixik.density_compiller.compiler.pipeline.context.PipelineAsmContext;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.MethodVisitor;

public abstract class DensityCompilerTask<T extends DensityFunction> {

    public static final int COMPUTE = 1 << 1;
    public static final int PREPARE_COMPUTE = 1 << 2;
    public static final int POST_PREPARE_COMPUTE = 1 << 3;

    public final void compileComputeImpl(MethodVisitor visitor, DensityFunction function, PipelineAsmContext context) {
        compileCompute(visitor, (T) function, context);
    }

    protected abstract void compileCompute(MethodVisitor mv, T node, PipelineAsmContext ctx);

    public final void prepareComputeImpl(MethodVisitor mv, DensityFunction node, PipelineAsmContext ctx) {
        prepareCompute(mv, (T) node, ctx);
    }

    protected void prepareCompute(MethodVisitor mv, T node, PipelineAsmContext ctx) {

    }

    public final void postPrepareComputeImpl(MethodVisitor mv, DensityFunction node, PipelineAsmContext ctx) {
        postPrepareCompute(mv, (T) node, ctx);
    }

    protected void postPrepareCompute(MethodVisitor mv, T node, PipelineAsmContext ctx) {
    }

    @Deprecated
    protected double inlineValueImpl(MethodVisitor mv, DensityFunction node, PipelineAsmContext ctx) {
        return inlineValue(mv, (T) node, ctx);
    }

    @Deprecated
    protected double inlineValue(MethodVisitor mv, T node, PipelineAsmContext ctx) {
        return Double.MIN_VALUE;
    }

    @Deprecated(forRemoval = true)
    public int buildBits() {
        return 0;
    }
}
