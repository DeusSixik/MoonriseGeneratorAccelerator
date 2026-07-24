package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.runtime.Runtime;
import net.minecraft.util.Mth;

/** CPU mirror for {@link GpuIrPayload}; used to validate GPU-shaped payload semantics. */
public final class GpuPayloadCpuEvaluator {

    private GpuPayloadCpuEvaluator() {
    }

    public static double compute(GpuIrPayload payload, int blockX, int blockY, int blockZ) {
        if (payload.hasExternInputs()) {
            throw new IllegalArgumentException("GPU payload requires extern input values");
        }
        return compute(payload, blockX, blockY, blockZ, null, 0);
    }

    public static double compute(
            GpuIrPayload payload,
            int blockX,
            int blockY,
            int blockZ,
            double[] externValues,
            int pointIndex) {
        double[] values = new double[payload.nodeCount()];
        for (int i = 0; i < payload.nodeCount(); i++) {
            values[i] = computeNode(payload, values, i, blockX, blockY, blockZ, externValues, pointIndex);
        }
        return values[payload.rootIndex()];
    }

    private static double computeNode(
            GpuIrPayload p,
            double[] values,
            int i,
            int blockX,
            int blockY,
            int blockZ,
            double[] externValues,
            int pointIndex) {
        return switch (p.opcodes()[i]) {
            case GpuIrPayload.CONST -> p.value0()[i];
            case GpuIrPayload.BLOCK_X -> (double) blockX;
            case GpuIrPayload.BLOCK_Y -> (double) blockY;
            case GpuIrPayload.BLOCK_Z -> (double) blockZ;
            case GpuIrPayload.EXTERN_INPUT -> externInput(p, externValues, pointIndex, p.int0()[i]);
            case GpuIrPayload.ADD -> values[p.arg0()[i]] + values[p.arg1()[i]];
            case GpuIrPayload.SUB -> values[p.arg0()[i]] - values[p.arg1()[i]];
            case GpuIrPayload.MUL -> values[p.arg0()[i]] * values[p.arg1()[i]];
            case GpuIrPayload.DIV -> values[p.arg0()[i]] / values[p.arg1()[i]];
            case GpuIrPayload.MIN -> Math.min(values[p.arg0()[i]], values[p.arg1()[i]]);
            case GpuIrPayload.MAX -> Math.max(values[p.arg0()[i]], values[p.arg1()[i]]);
            case GpuIrPayload.ABS -> Math.abs(values[p.arg0()[i]]);
            case GpuIrPayload.NEG -> -values[p.arg0()[i]];
            case GpuIrPayload.SQUARE -> values[p.arg0()[i]] * values[p.arg0()[i]];
            case GpuIrPayload.CUBE -> values[p.arg0()[i]] * values[p.arg0()[i]] * values[p.arg0()[i]];
            case GpuIrPayload.HALF_NEGATIVE -> conditionalScale(values[p.arg0()[i]], 0.5);
            case GpuIrPayload.QUARTER_NEGATIVE -> conditionalScale(values[p.arg0()[i]], 0.25);
            case GpuIrPayload.SQUEEZE -> Runtime.squeeze(values[p.arg0()[i]]);
            case GpuIrPayload.CLAMP -> Math.max(p.value0()[i], Math.min(p.value1()[i], values[p.arg0()[i]]));
            case GpuIrPayload.RANGE_CHOICE -> {
                double input = values[p.arg0()[i]];
                yield input >= p.value0()[i] && input < p.value1()[i]
                        ? values[p.arg1()[i]]
                        : values[p.arg2()[i]];
            }
            case GpuIrPayload.Y_CLAMPED_GRADIENT -> yClampedGradient(p, i, blockY);
            case GpuIrPayload.CUSTOM_OP -> customOp(p, values, i, blockX, blockY, blockZ);
            default -> throw new IllegalArgumentException("Unsupported GPU payload opcode " + p.opcodes()[i]);
        };
    }

    private static double customOp(
            GpuIrPayload payload,
            double[] values,
            int nodeIndex,
            int blockX,
            int blockY,
            int blockZ) {
        DensityFunctionGpuKernelOpRegistry.Entry entry =
                DensityFunctionGpuKernelOpRegistry.lookupSlot(payload.int0()[nodeIndex]);
        if (entry == null) {
            throw new IllegalArgumentException("Custom GPU op slot is not registered: " + payload.int0()[nodeIndex]);
        }
        DensityFunctionGpuKernelOp op = entry.op();
        double a = op.inputCount() > 0 ? values[payload.arg0()[nodeIndex]] : 0.0D;
        double b = op.inputCount() > 1 ? values[payload.arg1()[nodeIndex]] : 0.0D;
        double c = op.inputCount() > 2 ? values[payload.arg2()[nodeIndex]] : 0.0D;
        return op.cpuEvaluator().compute(
                a,
                b,
                c,
                blockX,
                blockY,
                blockZ,
                payload.value0()[nodeIndex],
                payload.value1()[nodeIndex],
                payload.value2()[nodeIndex],
                payload.value3()[nodeIndex]);
    }

    private static double externInput(GpuIrPayload payload, double[] externValues, int pointIndex, int slot) {
        int externInputCount = payload.externInputCount();
        if (slot < 0 || slot >= externInputCount) {
            throw new IllegalArgumentException("Extern input slot out of bounds: " + slot);
        }
        if (externValues == null) {
            throw new IllegalArgumentException("Extern input values are required");
        }
        int index = pointIndex * externInputCount + slot;
        if (index < 0 || index >= externValues.length) {
            throw new IllegalArgumentException("Extern input value index out of bounds: " + index);
        }
        return externValues[index];
    }

    private static double conditionalScale(double value, double factor) {
        return value > 0.0 ? value : value * factor;
    }

    private static double yClampedGradient(GpuIrPayload p, int i, int blockY) {
        int fromY = p.int0()[i];
        int toY = p.int1()[i];
        double fromValue = p.value0()[i];
        double toValue = p.value1()[i];
        if (fromY == toY || !Double.isFinite(fromValue) || !Double.isFinite(toValue)) {
            return Mth.clampedMap((double) blockY, (double) fromY, (double) toY, fromValue, toValue);
        }
        if (fromY < toY) {
            if (blockY <= fromY) {
                return fromValue;
            }
            if (blockY >= toY) {
                return toValue;
            }
        } else {
            if (blockY >= fromY) {
                return fromValue;
            }
            if (blockY <= toY) {
                return toValue;
            }
        }
        return ((double) (blockY - fromY)) * ((toValue - fromValue) / (double) (toY - fromY)) + fromValue;
    }
}
