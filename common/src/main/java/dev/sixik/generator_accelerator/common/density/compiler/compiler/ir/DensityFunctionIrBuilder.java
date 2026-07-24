package dev.sixik.generator_accelerator.common.density.compiler.compiler.ir;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Extension point for lowering supported {@link DensityFunction} implementations into
 * the compiler's core IR.
 *
 * <p>This is the preferred way to add GPU support for mod-provided density functions:
 * a compat module registers a builder for the mod class, destructures that object into
 * existing IR nodes, and the normal optimizer/codegen/GPU payload pipeline consumes the
 * result. That keeps the OpenCL/JavaToGpu kernel stable for functions that can be
 * represented as arithmetic, clamps, choices, gradients, noise coordinates, etc.</p>
 */
@FunctionalInterface
public interface DensityFunctionIrBuilder<T extends DensityFunction> {

    /**
     * Returns a lowered IR node, or {@code null} when this concrete instance should fall
     * back to the next registered builder / opaque invoke.
     */
    IRNode build(T function, Context context);

    interface Context {
        IRNode walk(DensityFunction function);

        IRNode intern(IRNode node);

        ConstantPool pool();

        IRNode invokeOpaque(DensityFunction function);

        default IRNode constant(double value) {
            return intern(new IRNode.Const(value));
        }

        default IRNode blockX() {
            return intern(IRNode.BlockX.INSTANCE);
        }

        default IRNode blockY() {
            return intern(IRNode.BlockY.INSTANCE);
        }

        default IRNode blockZ() {
            return intern(IRNode.BlockZ.INSTANCE);
        }

        default IRNode bin(IRNode.BinOp op, IRNode left, IRNode right) {
            return intern(new IRNode.Bin(op, left, right));
        }

        default IRNode unary(IRNode.UnaryOp op, IRNode input) {
            return intern(new IRNode.Unary(op, input));
        }

        default IRNode clamp(IRNode input, double min, double max) {
            return intern(new IRNode.Clamp(input, min, max));
        }

        default IRNode rangeChoice(IRNode input, double min, double max, IRNode whenInRange, IRNode whenOutOfRange) {
            return intern(new IRNode.RangeChoice(input, min, max, whenInRange, whenOutOfRange));
        }

        default IRNode yClampedGradient(int fromY, int toY, double fromValue, double toValue) {
            return intern(new IRNode.YClampedGradient(fromY, toY, fromValue, toValue));
        }
    }
}
