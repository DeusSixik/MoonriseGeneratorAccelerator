package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

/**
 * Primitive, GPU-shaped representation of the arithmetic subset of DFC IR.
 *
 * <p>The arrays are intentionally plain primitive buffers so the same layout can
 * be consumed by a CPU mirror evaluator now and a JavaToGpu kernel later.
 */
public record GpuIrPayload(
        int rootIndex,
        int externInputCount,
        int[] externInputPathOffsets,
        int[] externInputPathLengths,
        int[] externInputOwnerPath,
        int[] externInputLeafExternIndices,
        int[] opcodes,
        int[] arg0,
        int[] arg1,
        int[] arg2,
        int[] int0,
        int[] int1,
        double[] value0,
        double[] value1,
        double[] value2,
        double[] value3,
        int[] noisePermutations,
        double[] noiseOctaveData) {

    public static final int CONST = 1;
    public static final int BLOCK_X = 2;
    public static final int BLOCK_Y = 3;
    public static final int BLOCK_Z = 4;
    public static final int ADD = 5;
    public static final int SUB = 6;
    public static final int MUL = 7;
    public static final int DIV = 8;
    public static final int MIN = 9;
    public static final int MAX = 10;
    public static final int ABS = 11;
    public static final int NEG = 12;
    public static final int SQUARE = 13;
    public static final int CUBE = 14;
    public static final int HALF_NEGATIVE = 15;
    public static final int QUARTER_NEGATIVE = 16;
    public static final int SQUEEZE = 17;
    public static final int CLAMP = 18;
    public static final int RANGE_CHOICE = 19;
    public static final int Y_CLAMPED_GRADIENT = 20;
    public static final int EXTERN_INPUT = 21;
    public static final int INLINED_NOISE = 22;
    public static final int BLEND_DENSITY = 23;
    public static final int INLINED_BLENDED_NOISE = 24;
    public static final int WEIRD_RARITY = 25;
    public static final int CUSTOM_OP = 1000;

    public static final int NOISE_PERMUTATION_SIZE = 256;
    public static final int NOISE_OCTAVE_DATA_STRIDE = 5;
    public static final int NOISE_OCTAVE_INPUT_FACTOR = 0;
    public static final int NOISE_OCTAVE_AMP_VALUE_FACTOR = 1;
    public static final int NOISE_OCTAVE_XO = 2;
    public static final int NOISE_OCTAVE_YO = 3;
    public static final int NOISE_OCTAVE_ZO = 4;

    public int nodeCount() {
        return opcodes.length;
    }

    public boolean hasExternInputs() {
        return externInputCount > 0;
    }

    public boolean hasCustomOps() {
        for (int opcode : opcodes) {
            if (opcode == CUSTOM_OP) {
                return true;
            }
        }
        return false;
    }

    public boolean requiresRootParity() {
        for (int opcode : opcodes) {
            if (opcode == BLEND_DENSITY) {
                return true;
            }
        }
        return false;
    }

    public int noiseOctaveCount() {
        return noiseOctaveData.length / NOISE_OCTAVE_DATA_STRIDE;
    }
}
