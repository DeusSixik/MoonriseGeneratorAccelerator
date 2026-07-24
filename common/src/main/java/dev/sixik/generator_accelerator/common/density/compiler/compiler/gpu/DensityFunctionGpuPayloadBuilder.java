package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.codegen.ConstantPool;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.IRNode;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Extension point for packing mod-provided {@link DensityFunction}s into the primitive
 * {@link GpuIrPayload} instruction stream.
 *
 * <p>This layer is intentionally lower than {@code DensityFunctionIrBuilder}: use IR
 * builders when a function can be expressed as normal compiler IR, and use payload
 * builders for late GPU packing of otherwise opaque extern functions. Builders should
 * only emit opcodes supported by {@link GpuPayloadArithmeticKernel}; child functions can
 * be exposed as {@link GpuIrPayload#EXTERN_INPUT} slots via {@link Context#externInput}.</p>
 */
@FunctionalInterface
public interface DensityFunctionGpuPayloadBuilder<T extends DensityFunction> {

    /**
     * Lightweight instance predicate used by diagnostics before payload emission. Override
     * when only some variants of the target class fit the shared primitive kernel.
     */
    default boolean supports(T function) {
        return true;
    }

    /**
     * Returns the payload node index for the function, or a negative value to decline
     * this concrete instance and let the compiler fall back to the next builder.
     * Builders that decline should do so before emitting nodes through the context.
     */
    int build(T function, Context context);

    interface Context {
        ConstantPool pool();

        /** Encodes an existing IR subtree using the normal payload compiler rules. */
        int visit(IRNode node);

        /**
         * Adds a per-point CPU-filled input slot. This is useful for child functions
         * that are not yet GPU-packable while still letting the wrapper arithmetic run
         * inside the shared GPU payload kernel.
         */
        int externInput(DensityFunction function);

        int constant(double value);

        int blockX();

        int blockY();

        int blockZ();

        int bin(IRNode.BinOp op, int left, int right);

        int unary(IRNode.UnaryOp op, int input);

        int clamp(int input, double min, double max);

        int rangeChoice(int input, double min, double max, int whenInRange, int whenOutOfRange);

        int yClampedGradient(int fromY, int toY, double fromValue, double toValue);

        int customOp(String opId, int arg0, int arg1, int arg2,
                     double param0, double param1, double param2, double param3);

        default int customUnary(String opId, int input) {
            return customOp(opId, input, -1, -1, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        default int customUnary(String opId, int input, double param0) {
            return customOp(opId, input, -1, -1, param0, 0.0D, 0.0D, 0.0D);
        }

        default int customBinary(String opId, int left, int right) {
            return customOp(opId, left, right, -1, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        default int customBinary(String opId, int left, int right, double param0) {
            return customOp(opId, left, right, -1, param0, 0.0D, 0.0D, 0.0D);
        }

        default int customTernary(String opId, int first, int second, int third) {
            return customOp(opId, first, second, third, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }
}
