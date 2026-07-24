package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import net.sixik.ga_utils.javatogpu.api.GPU;
import net.sixik.ga_utils.javatogpu.api.annotations.GPUGlobal;

/** JavaToGpu kernel for the arithmetic-only DFC primitive payload subset. */
public final class GpuPayloadArithmeticKernel {
    private GpuPayloadArithmeticKernel() {
    }

    @net.sixik.ga_utils.javatogpu.api.annotations.GPU
    public static void computeBatch(
            @GPUGlobal(constant = true) int[] blockX,
            @GPUGlobal(constant = true) int[] blockY,
            @GPUGlobal(constant = true) int[] blockZ,
            @GPUGlobal(constant = true) int[] opcodes,
            @GPUGlobal(constant = true) int[] arg0,
            @GPUGlobal(constant = true) int[] arg1,
            @GPUGlobal(constant = true) int[] arg2,
            @GPUGlobal(constant = true) int[] int0,
            @GPUGlobal(constant = true) int[] int1,
            @GPUGlobal(constant = true) double[] value0,
            @GPUGlobal(constant = true) double[] value1,
            int externInputCount,
            @GPUGlobal(constant = true) double[] externValues,
            int rootIndex,
            int nodeCount,
            @GPUGlobal double[] scratch,
            @GPUGlobal double[] output) {
        int point = GPU.get_global_id(0);
        int base = point * nodeCount;
        int x = blockX[point];
        int y = blockY[point];
        int z = blockZ[point];

        for (int i = 0; i < nodeCount; i++) {
            int opcode = opcodes[i];
            double result = 0.0D;
            double a = 0.0D;
            double b = 0.0D;
            double input = 0.0D;
            double maxed = 0.0D;
            double clamped = 0.0D;
            double fromValue = 0.0D;
            double toValue = 0.0D;
            double delta = 0.0D;
            double clampedDelta = 0.0D;
            int fromY = 0;
            int toY = 0;
            boolean finiteValues = false;

            switch (opcode) {
                case 1 -> result = value0[i]; // CONST
                case 2 -> result = (double) x; // BLOCK_X
                case 3 -> result = (double) y; // BLOCK_Y
                case 4 -> result = (double) z; // BLOCK_Z
                case 5 -> result = scratch[base + arg0[i]] + scratch[base + arg1[i]]; // ADD
                case 6 -> result = scratch[base + arg0[i]] - scratch[base + arg1[i]]; // SUB
                case 7 -> result = scratch[base + arg0[i]] * scratch[base + arg1[i]]; // MUL
                case 8 -> result = scratch[base + arg0[i]] / scratch[base + arg1[i]]; // DIV
                case 9 -> { // MIN
                    a = scratch[base + arg0[i]];
                    b = scratch[base + arg1[i]];
                    if (a != a || b != b) {
                        result = a + b;
                    } else if (a == 0.0D && b == 0.0D) {
                        result = 1.0D / a < 0.0D || 1.0D / b < 0.0D ? -0.0D : 0.0D;
                    } else {
                        result = a <= b ? a : b;
                    }
                }
                case 10 -> { // MAX
                    a = scratch[base + arg0[i]];
                    b = scratch[base + arg1[i]];
                    if (a != a || b != b) {
                        result = a + b;
                    } else if (a == 0.0D && b == 0.0D) {
                        result = 1.0D / a > 0.0D || 1.0D / b > 0.0D ? 0.0D : -0.0D;
                    } else {
                        result = a >= b ? a : b;
                    }
                }
                case 11 -> { // ABS
                    input = scratch[base + arg0[i]];
                    result = GPU.fabs(input);
                }
                case 12 -> result = -scratch[base + arg0[i]]; // NEG
                case 13 -> { // SQUARE
                    input = scratch[base + arg0[i]];
                    result = input * input;
                }
                case 14 -> { // CUBE
                    input = scratch[base + arg0[i]];
                    result = input * input * input;
                }
                case 15 -> { // HALF_NEGATIVE
                    input = scratch[base + arg0[i]];
                    result = input > 0.0D ? input : input * 0.5D;
                }
                case 16 -> { // QUARTER_NEGATIVE
                    input = scratch[base + arg0[i]];
                    result = input > 0.0D ? input : input * 0.25D;
                }
                case 17 -> { // SQUEEZE
                    input = scratch[base + arg0[i]];
                    clamped = input < -1.0D ? -1.0D : (input > 1.0D ? 1.0D : input);
                    result = clamped / 2.0D - clamped * clamped * clamped / 24.0D;
                }
                case 18 -> { // CLAMP
                    input = scratch[base + arg0[i]];
                    maxed = input < value0[i] ? value0[i] : input;
                    result = maxed > value1[i] ? value1[i] : maxed;
                }
                case 19 -> { // RANGE_CHOICE
                    input = scratch[base + arg0[i]];
                    result = input >= value0[i] && input < value1[i]
                            ? scratch[base + arg1[i]]
                            : scratch[base + arg2[i]];
                }
                case 20 -> { // Y_CLAMPED_GRADIENT
                    fromY = int0[i];
                    toY = int1[i];
                    fromValue = value0[i];
                    toValue = value1[i];
                    finiteValues = fromValue <= 1.7976931348623157E308D
                            && fromValue >= -1.7976931348623157E308D
                            && toValue <= 1.7976931348623157E308D
                            && toValue >= -1.7976931348623157E308D;

                    if (fromY != toY && finiteValues) {
                        if (fromY < toY) {
                            if (y <= fromY) {
                                result = fromValue;
                            } else if (y >= toY) {
                                result = toValue;
                            } else {
                                result = ((double) (y - fromY))
                                        * ((toValue - fromValue) / (double) (toY - fromY)) + fromValue;
                            }
                        } else {
                            if (y >= fromY) {
                                result = fromValue;
                            } else if (y <= toY) {
                                result = toValue;
                            } else {
                                result = ((double) (y - fromY))
                                        * ((toValue - fromValue) / (double) (toY - fromY)) + fromValue;
                            }
                        }
                    } else {
                        delta = ((double) y - (double) fromY) / ((double) toY - (double) fromY);
                        clampedDelta = delta < 0.0D ? 0.0D : (delta > 1.0D ? 1.0D : delta);
                        result = fromValue + clampedDelta * (toValue - fromValue);
                    }
                }
                case 21 -> result = externValues[point * externInputCount + int0[i]]; // EXTERN_INPUT
                default -> result = 0.0D;
            }

            scratch[base + i] = result;
        }

        output[point] = scratch[base + rootIndex];
    }
}
