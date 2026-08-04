package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.runtime.Runtime;
import net.minecraft.util.Mth;

/** CPU mirror for {@link GpuIrPayload}; used to validate GPU-shaped payload semantics. */
public final class GpuPayloadCpuEvaluator {
    private static final double[] IMPROVED_FLAT_GRAD = {
            1.0, 1.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0,
            1.0, -1.0, 0.0, 0.0, -1.0, -1.0, 0.0, 0.0,
            1.0, 0.0, 1.0, 0.0, -1.0, 0.0, 1.0, 0.0,
            1.0, 0.0, -1.0, 0.0, -1.0, 0.0, -1.0, 0.0,
            0.0, 1.0, 1.0, 0.0, 0.0, -1.0, 1.0, 0.0,
            0.0, 1.0, -1.0, 0.0, 0.0, -1.0, -1.0, 0.0,
            1.0, 1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0,
            -1.0, 1.0, 0.0, 0.0, 0.0, -1.0, -1.0, 0.0
    };

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
            case GpuIrPayload.INLINED_NOISE -> inlinedNoise(p, values, i);
            case GpuIrPayload.BLEND_DENSITY -> values[p.arg0()[i]];
            case GpuIrPayload.INLINED_BLENDED_NOISE -> inlinedBlendedNoise(p, i, blockX, blockY, blockZ);
            case GpuIrPayload.WEIRD_RARITY -> Runtime.weirdRarity(values[p.arg0()[i]], p.int0()[i]);
            case GpuIrPayload.CUSTOM_OP -> customOp(p, values, i, blockX, blockY, blockZ);
            default -> throw new IllegalArgumentException("Unsupported GPU payload opcode " + p.opcodes()[i]);
        };
    }

    private static double inlinedBlendedNoise(
            GpuIrPayload payload,
            int nodeIndex,
            int blockX,
            int blockY,
            int blockZ) {
        int metadataIndex = payload.int0()[nodeIndex];
        int metadataBase = metadataIndex * GpuIrPayload.NOISE_OCTAVE_DATA_STRIDE;
        double xzMultiplier = payload.noiseOctaveData()[metadataBase + GpuIrPayload.NOISE_OCTAVE_INPUT_FACTOR];
        double yMultiplier = payload.noiseOctaveData()[metadataBase + GpuIrPayload.NOISE_OCTAVE_AMP_VALUE_FACTOR];
        double xzFactor = payload.noiseOctaveData()[metadataBase + GpuIrPayload.NOISE_OCTAVE_XO];
        double yFactor = payload.noiseOctaveData()[metadataBase + GpuIrPayload.NOISE_OCTAVE_YO];
        double smearScaleMultiplier = payload.noiseOctaveData()[metadataBase + GpuIrPayload.NOISE_OCTAVE_ZO];

        double scaledX = (double) blockX * xzMultiplier;
        double scaledY = (double) blockY * yMultiplier;
        double scaledZ = (double) blockZ * xzMultiplier;
        double mainX = scaledX / xzFactor;
        double mainY = scaledY / yFactor;
        double mainZ = scaledZ / xzFactor;
        double limitYScale = yMultiplier * smearScaleMultiplier;
        double mainYScale = limitYScale / yFactor;

        int mainBase = metadataIndex + 1;
        int minBase = mainBase + 8;
        int maxBase = minBase + 16;

        double main = 0.0D;
        for (int i = 0; i < 8; i++) {
            int octaveIndex = mainBase + i;
            int dataBase = octaveIndex * GpuIrPayload.NOISE_OCTAVE_DATA_STRIDE;
            double frequency = payload.noiseOctaveData()[dataBase + GpuIrPayload.NOISE_OCTAVE_INPUT_FACTOR];
            double amplitude = payload.noiseOctaveData()[dataBase + GpuIrPayload.NOISE_OCTAVE_AMP_VALUE_FACTOR];
            if (amplitude != 0.0D) {
                main += improvedNoise(
                        payload,
                        octaveIndex,
                        dataBase,
                        Runtime.wrapAxis(mainX * frequency),
                        Runtime.wrapAxis(mainY * frequency),
                        Runtime.wrapAxis(mainZ * frequency),
                        mainYScale * frequency,
                        mainY * frequency) * amplitude;
            }
        }

        double blend = (main / 10.0D + 1.0D) * 0.5D;
        double min = 0.0D;
        if (blend < 1.0D) {
            min = blendedLimitNoise(payload, minBase, scaledX, scaledY, scaledZ, limitYScale);
        }
        double max = 0.0D;
        if (blend > 0.0D) {
            max = blendedLimitNoise(payload, maxBase, scaledX, scaledY, scaledZ, limitYScale);
        }
        return Mth.clampedLerp(min / 512.0D, max / 512.0D, blend) / 128.0D;
    }

    private static double blendedLimitNoise(
            GpuIrPayload payload,
            int octaveBase,
            double x,
            double y,
            double z,
            double yScaleBase) {
        double sum = 0.0D;
        for (int i = 0; i < 16; i++) {
            int octaveIndex = octaveBase + i;
            int dataBase = octaveIndex * GpuIrPayload.NOISE_OCTAVE_DATA_STRIDE;
            double frequency = payload.noiseOctaveData()[dataBase + GpuIrPayload.NOISE_OCTAVE_INPUT_FACTOR];
            double amplitude = payload.noiseOctaveData()[dataBase + GpuIrPayload.NOISE_OCTAVE_AMP_VALUE_FACTOR];
            if (amplitude != 0.0D) {
                sum += improvedNoise(
                        payload,
                        octaveIndex,
                        dataBase,
                        Runtime.wrapAxis(x * frequency),
                        Runtime.wrapAxis(y * frequency),
                        Runtime.wrapAxis(z * frequency),
                        yScaleBase * frequency,
                        y * frequency) * amplitude;
            }
        }
        return sum;
    }

    private static double inlinedNoise(GpuIrPayload payload, double[] values, int nodeIndex) {
        double x = values[payload.arg0()[nodeIndex]];
        double y = values[payload.arg1()[nodeIndex]];
        double z = values[payload.arg2()[nodeIndex]];
        int octaveOffset = payload.int0()[nodeIndex];
        int octaveLength = payload.int1()[nodeIndex];
        double sum = 0.0D;
        for (int i = 0; i < octaveLength; i++) {
            int octaveIndex = octaveOffset + i;
            int dataBase = octaveIndex * GpuIrPayload.NOISE_OCTAVE_DATA_STRIDE;
            double inputFactor = payload.noiseOctaveData()[dataBase + GpuIrPayload.NOISE_OCTAVE_INPUT_FACTOR];
            double ampValueFactor = payload.noiseOctaveData()[dataBase + GpuIrPayload.NOISE_OCTAVE_AMP_VALUE_FACTOR];
            double nx = Runtime.wrapAxis(x * inputFactor);
            double ny = Runtime.wrapAxis(y * inputFactor);
            double nz = Runtime.wrapAxis(z * inputFactor);
            sum += improvedNoise(payload, octaveIndex, dataBase, nx, ny, nz) * ampValueFactor;
        }
        return sum * payload.value0()[nodeIndex];
    }

    private static double improvedNoise(
            GpuIrPayload payload,
            int octaveIndex,
            int dataBase,
            double x,
            double y,
            double z) {
        return improvedNoise(payload, octaveIndex, dataBase, x, y, z, 0.0D, 0.0D);
    }

    private static double improvedNoise(
            GpuIrPayload payload,
            int octaveIndex,
            int dataBase,
            double x,
            double y,
            double z,
            double yScale,
            double yMax) {
        double inputX = x + payload.noiseOctaveData()[dataBase + GpuIrPayload.NOISE_OCTAVE_XO];
        double inputY = y + payload.noiseOctaveData()[dataBase + GpuIrPayload.NOISE_OCTAVE_YO];
        double inputZ = z + payload.noiseOctaveData()[dataBase + GpuIrPayload.NOISE_OCTAVE_ZO];
        int gridX = floor(inputX);
        int gridY = floor(inputY);
        int gridZ = floor(inputZ);
        double deltaX = inputX - gridX;
        double deltaY = inputY - gridY;
        double deltaZ = inputZ - gridZ;
        double weirdDeltaY = deltaY;
        if (yScale != 0.0D) {
            double range = yMax >= 0.0D && yMax < deltaY ? yMax : deltaY;
            double scaled = range / yScale + 1.0E-7D;
            int scaledFloor = (int) scaled;
            if (scaled < (double) scaledFloor) {
                scaledFloor--;
            }
            weirdDeltaY = deltaY - (double) scaledFloor * yScale;
        }
        return sampleAndLerp(
                payload.noisePermutations(), octaveIndex, gridX, gridY, gridZ,
                deltaX, weirdDeltaY, deltaZ, deltaY);
    }

    private static double sampleAndLerp(
            int[] permutations,
            int octaveIndex,
            int gridX,
            int gridY,
            int gridZ,
            double x,
            double y,
            double z) {
        return sampleAndLerp(permutations, octaveIndex, gridX, gridY, gridZ, x, y, z, y);
    }

    private static double sampleAndLerp(
            int[] permutations,
            int octaveIndex,
            int gridX,
            int gridY,
            int gridZ,
            double x,
            double wy,
            double z,
            double smoothY) {
        int permutationBase = octaveIndex * GpuIrPayload.NOISE_PERMUTATION_SIZE;
        int X = gridX & 0xFF;
        int Y = gridY & 0xFF;
        int Z = gridZ & 0xFF;

        int A = permutations[permutationBase + X] + Y;
        int AA = permutations[permutationBase + (A & 0xFF)] + Z;
        int AB = permutations[permutationBase + ((A + 1) & 0xFF)] + Z;
        int B = permutations[permutationBase + ((X + 1) & 0xFF)] + Y;
        int BA = permutations[permutationBase + (B & 0xFF)] + Z;
        int BB = permutations[permutationBase + ((B + 1) & 0xFF)] + Z;

        int gi000 = (permutations[permutationBase + (AA & 0xFF)] & 15) << 2;
        int gi001 = (permutations[permutationBase + ((AA + 1) & 0xFF)] & 15) << 2;
        int gi010 = (permutations[permutationBase + (AB & 0xFF)] & 15) << 2;
        int gi011 = (permutations[permutationBase + ((AB + 1) & 0xFF)] & 15) << 2;
        int gi100 = (permutations[permutationBase + (BA & 0xFF)] & 15) << 2;
        int gi101 = (permutations[permutationBase + ((BA + 1) & 0xFF)] & 15) << 2;
        int gi110 = (permutations[permutationBase + (BB & 0xFF)] & 15) << 2;
        int gi111 = (permutations[permutationBase + ((BB + 1) & 0xFF)] & 15) << 2;

        double x1 = x - 1.0D;
        double y1 = wy - 1.0D;
        double z1 = z - 1.0D;
        double n000 = gradDot(gi000, x, wy, z);
        double n100 = gradDot(gi100, x1, wy, z);
        double n001 = gradDot(gi001, x, wy, z1);
        double n101 = gradDot(gi101, x1, wy, z1);
        double n011 = gradDot(gi011, x, y1, z1);
        double n111 = gradDot(gi111, x1, y1, z1);
        double n010 = gradDot(gi010, x, y1, z);
        double n110 = gradDot(gi110, x1, y1, z);

        double u = smoothstep(x);
        double v = smoothstep(smoothY);
        double w = smoothstep(z);
        double lerpX1 = n000 + u * (n100 - n000);
        double lerpX2 = n010 + u * (n110 - n010);
        double lerpX3 = n001 + u * (n101 - n001);
        double lerpX4 = n011 + u * (n111 - n011);
        double lerpY1 = lerpX1 + v * (lerpX2 - lerpX1);
        double lerpY2 = lerpX3 + v * (lerpX4 - lerpX3);
        return lerpY1 + w * (lerpY2 - lerpY1);
    }

    private static double gradDot(int gradIndex4, double x, double y, double z) {
        return IMPROVED_FLAT_GRAD[gradIndex4] * x
                + IMPROVED_FLAT_GRAD[gradIndex4 | 1] * y
                + IMPROVED_FLAT_GRAD[gradIndex4 | 2] * z;
    }

    private static double smoothstep(double value) {
        return value * value * value * (value * (value * 6.0D - 15.0D) + 10.0D);
    }

    private static int floor(double value) {
        int floor = (int) value;
        return value < (double) floor ? floor - 1 : floor;
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
