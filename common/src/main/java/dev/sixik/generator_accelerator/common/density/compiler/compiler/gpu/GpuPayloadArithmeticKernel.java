package dev.sixik.generator_accelerator.common.density.compiler.compiler.gpu;

import net.sixik.ga_utils.javatogpu.api.GPU;
import net.sixik.ga_utils.javatogpu.api.annotations.CCode;
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
            @GPUGlobal(constant = true) int[] noisePermutations,
            @GPUGlobal(constant = true) double[] noiseOctaveData,
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
            double noiseX = 0.0D;
            double noiseY = 0.0D;
            double noiseZ = 0.0D;
            int octaveOffset = 0;
            int octaveLength = 0;
            double sum = 0.0D;
            int metadataIndex = 0;
            int metadataBase = 0;
            double xzMultiplier = 0.0D;
            double yMultiplier = 0.0D;
            double xzFactor = 0.0D;
            double yFactor = 0.0D;
            double smearScaleMultiplier = 0.0D;
            double scaledX = 0.0D;
            double scaledY = 0.0D;
            double scaledZ = 0.0D;
            double mainX = 0.0D;
            double mainY = 0.0D;
            double mainZ = 0.0D;
            double limitYScale = 0.0D;
            double mainYScale = 0.0D;
            int mainBase = 0;
            int minBase = 0;
            int maxBase = 0;
            double main = 0.0D;
            double blend = 0.0D;
            double min = 0.0D;
            double max = 0.0D;

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
                case 22 -> { // INLINED_NOISE
                    noiseX = scratch[base + arg0[i]];
                    noiseY = scratch[base + arg1[i]];
                    noiseZ = scratch[base + arg2[i]];
                    octaveOffset = int0[i];
                    octaveLength = int1[i];
                    sum = 0.0D;
                    for (int octave = 0; octave < octaveLength; octave++) {
                        int octaveIndex = octaveOffset + octave;
                        int dataBase = octaveIndex * 5;
                        double inputFactor = noiseOctaveData[dataBase];
                        double ampValueFactor = noiseOctaveData[dataBase + 1];
                        double nx = wrapAxis(noiseX * inputFactor);
                        double ny = wrapAxis(noiseY * inputFactor);
                        double nz = wrapAxis(noiseZ * inputFactor);

                        double inputX = nx + noiseOctaveData[dataBase + 2];
                        double inputY = ny + noiseOctaveData[dataBase + 3];
                        double inputZ = nz + noiseOctaveData[dataBase + 4];
                        int gridX = floorInt(inputX);
                        int gridY = floorInt(inputY);
                        int gridZ = floorInt(inputZ);
                        double dx = inputX - (double) gridX;
                        double dy = inputY - (double) gridY;
                        double dz = inputZ - (double) gridZ;

                        int permutationBase = octaveIndex * 256;
                        int X = gridX & 0xFF;
                        int Y = gridY & 0xFF;
                        int Z = gridZ & 0xFF;
                        int A = noisePermutations[permutationBase + X] + Y;
                        int AA = noisePermutations[permutationBase + (A & 0xFF)] + Z;
                        int AB = noisePermutations[permutationBase + ((A + 1) & 0xFF)] + Z;
                        int B = noisePermutations[permutationBase + ((X + 1) & 0xFF)] + Y;
                        int BA = noisePermutations[permutationBase + (B & 0xFF)] + Z;
                        int BB = noisePermutations[permutationBase + ((B + 1) & 0xFF)] + Z;

                        int gi000 = (noisePermutations[permutationBase + (AA & 0xFF)] & 15) << 2;
                        int gi001 = (noisePermutations[permutationBase + ((AA + 1) & 0xFF)] & 15) << 2;
                        int gi010 = (noisePermutations[permutationBase + (AB & 0xFF)] & 15) << 2;
                        int gi011 = (noisePermutations[permutationBase + ((AB + 1) & 0xFF)] & 15) << 2;
                        int gi100 = (noisePermutations[permutationBase + (BA & 0xFF)] & 15) << 2;
                        int gi101 = (noisePermutations[permutationBase + ((BA + 1) & 0xFF)] & 15) << 2;
                        int gi110 = (noisePermutations[permutationBase + (BB & 0xFF)] & 15) << 2;
                        int gi111 = (noisePermutations[permutationBase + ((BB + 1) & 0xFF)] & 15) << 2;

                        double dx1 = dx - 1.0D;
                        double dy1 = dy - 1.0D;
                        double dz1 = dz - 1.0D;
                        double n000 = gradDot(gi000, dx, dy, dz);
                        double n100 = gradDot(gi100, dx1, dy, dz);
                        double n001 = gradDot(gi001, dx, dy, dz1);
                        double n101 = gradDot(gi101, dx1, dy, dz1);
                        double n011 = gradDot(gi011, dx, dy1, dz1);
                        double n111 = gradDot(gi111, dx1, dy1, dz1);
                        double n010 = gradDot(gi010, dx, dy1, dz);
                        double n110 = gradDot(gi110, dx1, dy1, dz);

                        double u = smoothstep(dx);
                        double v = smoothstep(dy);
                        double w = smoothstep(dz);
                        double lerpX1 = n000 + u * (n100 - n000);
                        double lerpX2 = n010 + u * (n110 - n010);
                        double lerpX3 = n001 + u * (n101 - n001);
                        double lerpX4 = n011 + u * (n111 - n011);
                        double lerpY1 = lerpX1 + v * (lerpX2 - lerpX1);
                        double lerpY2 = lerpX3 + v * (lerpX4 - lerpX3);
                        sum += (lerpY1 + w * (lerpY2 - lerpY1)) * ampValueFactor;
                    }
                    result = sum * value0[i];
                }
                case 23 -> result = scratch[base + arg0[i]]; // BLEND_DENSITY no-op marker; runtime parity guards context-sensitive blending.
                case 24 -> { // INLINED_BLENDED_NOISE
                    metadataIndex = int0[i];
                    metadataBase = metadataIndex * 5;
                    xzMultiplier = noiseOctaveData[metadataBase];
                    yMultiplier = noiseOctaveData[metadataBase + 1];
                    xzFactor = noiseOctaveData[metadataBase + 2];
                    yFactor = noiseOctaveData[metadataBase + 3];
                    smearScaleMultiplier = noiseOctaveData[metadataBase + 4];

                    scaledX = (double) x * xzMultiplier;
                    scaledY = (double) y * yMultiplier;
                    scaledZ = (double) z * xzMultiplier;
                    mainX = scaledX / xzFactor;
                    mainY = scaledY / yFactor;
                    mainZ = scaledZ / xzFactor;
                    limitYScale = yMultiplier * smearScaleMultiplier;
                    mainYScale = limitYScale / yFactor;

                    mainBase = metadataIndex + 1;
                    minBase = mainBase + 8;
                    maxBase = minBase + 16;

                    main = 0.0D;
                    for (int blendedOctave = 0; blendedOctave < 8; blendedOctave++) {
                        int blendedOctaveIndex = mainBase + blendedOctave;
                        int blendedDataBase = blendedOctaveIndex * 5;
                        double frequency = noiseOctaveData[blendedDataBase];
                        double amplitude = noiseOctaveData[blendedDataBase + 1];
                        if (amplitude != 0.0D) {
                            main += improvedNoise(
                                    noisePermutations,
                                    noiseOctaveData,
                                    blendedOctaveIndex,
                                    blendedDataBase,
                                    wrapAxis(mainX * frequency),
                                    wrapAxis(mainY * frequency),
                                    wrapAxis(mainZ * frequency),
                                    mainYScale * frequency,
                                    mainY * frequency) * amplitude;
                        }
                    }

                    blend = (main / 10.0D + 1.0D) * 0.5D;
                    min = 0.0D;
                    if (blend < 1.0D) {
                        for (int blendedOctave = 0; blendedOctave < 16; blendedOctave++) {
                            int blendedOctaveIndex = minBase + blendedOctave;
                            int blendedDataBase = blendedOctaveIndex * 5;
                            double frequency = noiseOctaveData[blendedDataBase];
                            double amplitude = noiseOctaveData[blendedDataBase + 1];
                            if (amplitude != 0.0D) {
                                min += improvedNoise(
                                        noisePermutations,
                                        noiseOctaveData,
                                        blendedOctaveIndex,
                                        blendedDataBase,
                                        wrapAxis(scaledX * frequency),
                                        wrapAxis(scaledY * frequency),
                                        wrapAxis(scaledZ * frequency),
                                        limitYScale * frequency,
                                        scaledY * frequency) * amplitude;
                            }
                        }
                    }
                    max = 0.0D;
                    if (blend > 0.0D) {
                        for (int blendedOctave = 0; blendedOctave < 16; blendedOctave++) {
                            int blendedOctaveIndex = maxBase + blendedOctave;
                            int blendedDataBase = blendedOctaveIndex * 5;
                            double frequency = noiseOctaveData[blendedDataBase];
                            double amplitude = noiseOctaveData[blendedDataBase + 1];
                            if (amplitude != 0.0D) {
                                max += improvedNoise(
                                        noisePermutations,
                                        noiseOctaveData,
                                        blendedOctaveIndex,
                                        blendedDataBase,
                                        wrapAxis(scaledX * frequency),
                                        wrapAxis(scaledY * frequency),
                                        wrapAxis(scaledZ * frequency),
                                        limitYScale * frequency,
                                        scaledY * frequency) * amplitude;
                            }
                        }
                    }
                    result = clampedLerp(min / 512.0D, max / 512.0D, blend) / 128.0D;
                }
                case 25 -> { // WEIRD_RARITY
                    input = scratch[base + arg0[i]];
                    if (int0[i] == 0) {
                        if (input < -0.5D) {
                            result = 0.75D;
                        } else if (input < 0.0D) {
                            result = 1.0D;
                        } else {
                            result = input < 0.5D ? 1.5D : 2.0D;
                        }
                    } else {
                        if (input < -0.75D) {
                            result = 0.5D;
                        } else if (input < -0.5D) {
                            result = 0.75D;
                        } else if (input < 0.5D) {
                            result = 1.0D;
                        } else {
                            result = input < 0.75D ? 2.0D : 3.0D;
                        }
                    }
                }
                default -> result = 0.0D;
            }

            scratch[base + i] = result;
        }

        output[point] = scratch[base + rootIndex];
    }

    @net.sixik.ga_utils.javatogpu.api.annotations.GPU
    public static void computeMultiPayloadBatch(
            @GPUGlobal(constant = true) int[] blockX,
            @GPUGlobal(constant = true) int[] blockY,
            @GPUGlobal(constant = true) int[] blockZ,
            @GPUGlobal(constant = true) int[] payloadNodeOffsets,
            @GPUGlobal(constant = true) int[] payloadNodeCounts,
            @GPUGlobal(constant = true) int[] payloadRootIndices,
            @GPUGlobal(constant = true) int[] opcodes,
            @GPUGlobal(constant = true) int[] arg0,
            @GPUGlobal(constant = true) int[] arg1,
            @GPUGlobal(constant = true) int[] arg2,
            @GPUGlobal(constant = true) int[] int0,
            @GPUGlobal(constant = true) int[] int1,
            @GPUGlobal(constant = true) double[] value0,
            @GPUGlobal(constant = true) double[] value1,
            @GPUGlobal(constant = true) int[] noisePermutations,
            @GPUGlobal(constant = true) double[] noiseOctaveData,
            int maxExternInputCount,
            @GPUGlobal(constant = true) double[] externValues,
            int pointsPerPayload,
            int scratchStride,
            @GPUGlobal double[] scratch,
            @GPUGlobal double[] output) {
        int point = GPU.get_global_id(0);
        int payloadSlot = point / pointsPerPayload;
        int nodeOffset = payloadNodeOffsets[payloadSlot];
        int nodeCount = payloadNodeCounts[payloadSlot];
        int rootIndex = payloadRootIndices[payloadSlot];
        int base = point * scratchStride;
        int x = blockX[point];
        int y = blockY[point];
        int z = blockZ[point];

        for (int localNode = 0; localNode < nodeCount; localNode++) {
            int i = nodeOffset + localNode;
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
            double noiseX = 0.0D;
            double noiseY = 0.0D;
            double noiseZ = 0.0D;
            int octaveOffset = 0;
            int octaveLength = 0;
            double sum = 0.0D;
            int metadataIndex = 0;
            int metadataBase = 0;
            double xzMultiplier = 0.0D;
            double yMultiplier = 0.0D;
            double xzFactor = 0.0D;
            double yFactor = 0.0D;
            double smearScaleMultiplier = 0.0D;
            double scaledX = 0.0D;
            double scaledY = 0.0D;
            double scaledZ = 0.0D;
            double mainX = 0.0D;
            double mainY = 0.0D;
            double mainZ = 0.0D;
            double limitYScale = 0.0D;
            double mainYScale = 0.0D;
            int mainBase = 0;
            int minBase = 0;
            int maxBase = 0;
            double main = 0.0D;
            double blend = 0.0D;
            double min = 0.0D;
            double max = 0.0D;

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
                case 21 -> result = externValues[point * maxExternInputCount + int0[i]]; // EXTERN_INPUT
                case 22 -> { // INLINED_NOISE
                    noiseX = scratch[base + arg0[i]];
                    noiseY = scratch[base + arg1[i]];
                    noiseZ = scratch[base + arg2[i]];
                    octaveOffset = int0[i];
                    octaveLength = int1[i];
                    sum = 0.0D;
                    for (int octave = 0; octave < octaveLength; octave++) {
                        int octaveIndex = octaveOffset + octave;
                        int dataBase = octaveIndex * 5;
                        double inputFactor = noiseOctaveData[dataBase];
                        double ampValueFactor = noiseOctaveData[dataBase + 1];
                        double nx = wrapAxis(noiseX * inputFactor);
                        double ny = wrapAxis(noiseY * inputFactor);
                        double nz = wrapAxis(noiseZ * inputFactor);

                        double inputX = nx + noiseOctaveData[dataBase + 2];
                        double inputY = ny + noiseOctaveData[dataBase + 3];
                        double inputZ = nz + noiseOctaveData[dataBase + 4];
                        int gridX = floorInt(inputX);
                        int gridY = floorInt(inputY);
                        int gridZ = floorInt(inputZ);
                        double dx = inputX - (double) gridX;
                        double dy = inputY - (double) gridY;
                        double dz = inputZ - (double) gridZ;

                        int permutationBase = octaveIndex * 256;
                        int X = gridX & 0xFF;
                        int Y = gridY & 0xFF;
                        int Z = gridZ & 0xFF;
                        int A = noisePermutations[permutationBase + X] + Y;
                        int AA = noisePermutations[permutationBase + (A & 0xFF)] + Z;
                        int AB = noisePermutations[permutationBase + ((A + 1) & 0xFF)] + Z;
                        int B = noisePermutations[permutationBase + ((X + 1) & 0xFF)] + Y;
                        int BA = noisePermutations[permutationBase + (B & 0xFF)] + Z;
                        int BB = noisePermutations[permutationBase + ((B + 1) & 0xFF)] + Z;

                        int gi000 = (noisePermutations[permutationBase + (AA & 0xFF)] & 15) << 2;
                        int gi001 = (noisePermutations[permutationBase + ((AA + 1) & 0xFF)] & 15) << 2;
                        int gi010 = (noisePermutations[permutationBase + (AB & 0xFF)] & 15) << 2;
                        int gi011 = (noisePermutations[permutationBase + ((AB + 1) & 0xFF)] & 15) << 2;
                        int gi100 = (noisePermutations[permutationBase + (BA & 0xFF)] & 15) << 2;
                        int gi101 = (noisePermutations[permutationBase + ((BA + 1) & 0xFF)] & 15) << 2;
                        int gi110 = (noisePermutations[permutationBase + (BB & 0xFF)] & 15) << 2;
                        int gi111 = (noisePermutations[permutationBase + ((BB + 1) & 0xFF)] & 15) << 2;

                        double dx1 = dx - 1.0D;
                        double dy1 = dy - 1.0D;
                        double dz1 = dz - 1.0D;
                        double n000 = gradDot(gi000, dx, dy, dz);
                        double n100 = gradDot(gi100, dx1, dy, dz);
                        double n001 = gradDot(gi001, dx, dy, dz1);
                        double n101 = gradDot(gi101, dx1, dy, dz1);
                        double n011 = gradDot(gi011, dx, dy1, dz1);
                        double n111 = gradDot(gi111, dx1, dy1, dz1);
                        double n010 = gradDot(gi010, dx, dy1, dz);
                        double n110 = gradDot(gi110, dx1, dy1, dz);

                        double u = smoothstep(dx);
                        double v = smoothstep(dy);
                        double w = smoothstep(dz);
                        double lerpX1 = n000 + u * (n100 - n000);
                        double lerpX2 = n010 + u * (n110 - n010);
                        double lerpX3 = n001 + u * (n101 - n001);
                        double lerpX4 = n011 + u * (n111 - n011);
                        double lerpY1 = lerpX1 + v * (lerpX2 - lerpX1);
                        double lerpY2 = lerpX3 + v * (lerpX4 - lerpX3);
                        sum += (lerpY1 + w * (lerpY2 - lerpY1)) * ampValueFactor;
                    }
                    result = sum * value0[i];
                }
                case 23 -> result = scratch[base + arg0[i]]; // BLEND_DENSITY no-op marker; runtime parity guards context-sensitive blending.
                case 24 -> { // INLINED_BLENDED_NOISE
                    metadataIndex = int0[i];
                    metadataBase = metadataIndex * 5;
                    xzMultiplier = noiseOctaveData[metadataBase];
                    yMultiplier = noiseOctaveData[metadataBase + 1];
                    xzFactor = noiseOctaveData[metadataBase + 2];
                    yFactor = noiseOctaveData[metadataBase + 3];
                    smearScaleMultiplier = noiseOctaveData[metadataBase + 4];

                    scaledX = (double) x * xzMultiplier;
                    scaledY = (double) y * yMultiplier;
                    scaledZ = (double) z * xzMultiplier;
                    mainX = scaledX / xzFactor;
                    mainY = scaledY / yFactor;
                    mainZ = scaledZ / xzFactor;
                    limitYScale = yMultiplier * smearScaleMultiplier;
                    mainYScale = limitYScale / yFactor;

                    mainBase = metadataIndex + 1;
                    minBase = mainBase + 8;
                    maxBase = minBase + 16;

                    main = 0.0D;
                    for (int blendedOctave = 0; blendedOctave < 8; blendedOctave++) {
                        int blendedOctaveIndex = mainBase + blendedOctave;
                        int blendedDataBase = blendedOctaveIndex * 5;
                        double frequency = noiseOctaveData[blendedDataBase];
                        double amplitude = noiseOctaveData[blendedDataBase + 1];
                        if (amplitude != 0.0D) {
                            main += improvedNoise(
                                    noisePermutations,
                                    noiseOctaveData,
                                    blendedOctaveIndex,
                                    blendedDataBase,
                                    wrapAxis(mainX * frequency),
                                    wrapAxis(mainY * frequency),
                                    wrapAxis(mainZ * frequency),
                                    mainYScale * frequency,
                                    mainY * frequency) * amplitude;
                        }
                    }

                    blend = (main / 10.0D + 1.0D) * 0.5D;
                    min = 0.0D;
                    if (blend < 1.0D) {
                        for (int blendedOctave = 0; blendedOctave < 16; blendedOctave++) {
                            int blendedOctaveIndex = minBase + blendedOctave;
                            int blendedDataBase = blendedOctaveIndex * 5;
                            double frequency = noiseOctaveData[blendedDataBase];
                            double amplitude = noiseOctaveData[blendedDataBase + 1];
                            if (amplitude != 0.0D) {
                                min += improvedNoise(
                                        noisePermutations,
                                        noiseOctaveData,
                                        blendedOctaveIndex,
                                        blendedDataBase,
                                        wrapAxis(scaledX * frequency),
                                        wrapAxis(scaledY * frequency),
                                        wrapAxis(scaledZ * frequency),
                                        limitYScale * frequency,
                                        scaledY * frequency) * amplitude;
                            }
                        }
                    }
                    max = 0.0D;
                    if (blend > 0.0D) {
                        for (int blendedOctave = 0; blendedOctave < 16; blendedOctave++) {
                            int blendedOctaveIndex = maxBase + blendedOctave;
                            int blendedDataBase = blendedOctaveIndex * 5;
                            double frequency = noiseOctaveData[blendedDataBase];
                            double amplitude = noiseOctaveData[blendedDataBase + 1];
                            if (amplitude != 0.0D) {
                                max += improvedNoise(
                                        noisePermutations,
                                        noiseOctaveData,
                                        blendedOctaveIndex,
                                        blendedDataBase,
                                        wrapAxis(scaledX * frequency),
                                        wrapAxis(scaledY * frequency),
                                        wrapAxis(scaledZ * frequency),
                                        limitYScale * frequency,
                                        scaledY * frequency) * amplitude;
                            }
                        }
                    }
                    result = clampedLerp(min / 512.0D, max / 512.0D, blend) / 128.0D;
                }
                case 25 -> { // WEIRD_RARITY
                    input = scratch[base + arg0[i]];
                    if (int0[i] == 0) {
                        if (input < -0.5D) {
                            result = 0.75D;
                        } else if (input < 0.0D) {
                            result = 1.0D;
                        } else {
                            result = input < 0.5D ? 1.5D : 2.0D;
                        }
                    } else {
                        if (input < -0.75D) {
                            result = 0.5D;
                        } else if (input < -0.5D) {
                            result = 0.75D;
                        } else if (input < 0.5D) {
                            result = 1.0D;
                        } else {
                            result = input < 0.75D ? 2.0D : 3.0D;
                        }
                    }
                }
                default -> result = 0.0D;
            }

            scratch[base + localNode] = result;
        }

        output[point] = scratch[base + rootIndex];
    }

    @CCode(inline = true)
    private static int floorInt(double value) {
        int floor = (int) value;
        return value < (double) floor ? floor - 1 : floor;
    }

    @CCode(inline = true)
    private static double wrapAxis(double value) {
        double scaled = value / 33554432.0D + 0.5D;
        long truncated = (long) scaled;
        long floored = scaled < (double) truncated ? truncated - 1L : truncated;
        return value - (double) floored * 33554432.0D;
    }

    @CCode(inline = true)
    private static double smoothstep(double value) {
        return value * value * value * (value * (value * 6.0D - 15.0D) + 10.0D);
    }

    @CCode(inline = true)
    private static double clampedLerp(double from, double to, double delta) {
        double clamped = delta < 0.0D ? 0.0D : (delta > 1.0D ? 1.0D : delta);
        return from + clamped * (to - from);
    }

    @CCode(inline = true)
    private static double improvedNoise(
            @GPUGlobal(constant = true) int[] noisePermutations,
            @GPUGlobal(constant = true) double[] noiseOctaveData,
            int octaveIndex,
            int dataBase,
            double x,
            double y,
            double z,
            double yScale,
            double yMax) {
        double inputX = x + noiseOctaveData[dataBase + 2];
        double inputY = y + noiseOctaveData[dataBase + 3];
        double inputZ = z + noiseOctaveData[dataBase + 4];
        int gridX = floorInt(inputX);
        int gridY = floorInt(inputY);
        int gridZ = floorInt(inputZ);
        double dx = inputX - (double) gridX;
        double dy = inputY - (double) gridY;
        double dz = inputZ - (double) gridZ;
        double weirdDy = dy;
        if (yScale != 0.0D) {
            double range = yMax >= 0.0D && yMax < dy ? yMax : dy;
            double scaled = range / yScale + 1.0E-7D;
            int scaledFloor = (int) scaled;
            if (scaled < (double) scaledFloor) {
                scaledFloor--;
            }
            weirdDy = dy - (double) scaledFloor * yScale;
        }

        int permutationBase = octaveIndex * 256;
        int X = gridX & 0xFF;
        int Y = gridY & 0xFF;
        int Z = gridZ & 0xFF;
        int A = noisePermutations[permutationBase + X] + Y;
        int AA = noisePermutations[permutationBase + (A & 0xFF)] + Z;
        int AB = noisePermutations[permutationBase + ((A + 1) & 0xFF)] + Z;
        int B = noisePermutations[permutationBase + ((X + 1) & 0xFF)] + Y;
        int BA = noisePermutations[permutationBase + (B & 0xFF)] + Z;
        int BB = noisePermutations[permutationBase + ((B + 1) & 0xFF)] + Z;

        int gi000 = (noisePermutations[permutationBase + (AA & 0xFF)] & 15) << 2;
        int gi001 = (noisePermutations[permutationBase + ((AA + 1) & 0xFF)] & 15) << 2;
        int gi010 = (noisePermutations[permutationBase + (AB & 0xFF)] & 15) << 2;
        int gi011 = (noisePermutations[permutationBase + ((AB + 1) & 0xFF)] & 15) << 2;
        int gi100 = (noisePermutations[permutationBase + (BA & 0xFF)] & 15) << 2;
        int gi101 = (noisePermutations[permutationBase + ((BA + 1) & 0xFF)] & 15) << 2;
        int gi110 = (noisePermutations[permutationBase + (BB & 0xFF)] & 15) << 2;
        int gi111 = (noisePermutations[permutationBase + ((BB + 1) & 0xFF)] & 15) << 2;

        double dx1 = dx - 1.0D;
        double dy1 = weirdDy - 1.0D;
        double dz1 = dz - 1.0D;
        double n000 = gradDot(gi000, dx, weirdDy, dz);
        double n100 = gradDot(gi100, dx1, weirdDy, dz);
        double n001 = gradDot(gi001, dx, weirdDy, dz1);
        double n101 = gradDot(gi101, dx1, weirdDy, dz1);
        double n011 = gradDot(gi011, dx, dy1, dz1);
        double n111 = gradDot(gi111, dx1, dy1, dz1);
        double n010 = gradDot(gi010, dx, dy1, dz);
        double n110 = gradDot(gi110, dx1, dy1, dz);

        double u = smoothstep(dx);
        double v = smoothstep(dy);
        double w = smoothstep(dz);
        double lerpX1 = n000 + u * (n100 - n000);
        double lerpX2 = n010 + u * (n110 - n010);
        double lerpX3 = n001 + u * (n101 - n001);
        double lerpX4 = n011 + u * (n111 - n011);
        double lerpY1 = lerpX1 + v * (lerpX2 - lerpX1);
        double lerpY2 = lerpX3 + v * (lerpX4 - lerpX3);
        return lerpY1 + w * (lerpY2 - lerpY1);
    }

    @CCode(inline = true)
    private static double gradDot(int gradIndex4, double x, double y, double z) {
        int h = gradIndex4 >> 2;
        double gx = h == 0 || h == 2 || h == 4 || h == 6 || h == 12
                ? 1.0D
                : (h == 1 || h == 3 || h == 5 || h == 7 || h == 14 ? -1.0D : 0.0D);
        double gy = h == 0 || h == 1 || h == 8 || h == 10 || h == 12 || h == 14
                ? 1.0D
                : (h == 2 || h == 3 || h == 9 || h == 11 || h == 13 || h == 15 ? -1.0D : 0.0D);
        double gz = h == 4 || h == 5 || h == 8 || h == 9 || h == 13
                ? 1.0D
                : (h == 6 || h == 7 || h == 10 || h == 11 || h == 15 ? -1.0D : 0.0D);
        return gx * x + gy * y + gz * z;
    }
}
