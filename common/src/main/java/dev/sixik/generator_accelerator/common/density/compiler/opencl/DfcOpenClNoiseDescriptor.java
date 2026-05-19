package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.BlendedNoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.compiler.noise.NoiseSpec;
import dev.sixik.generator_accelerator.common.density.compiler.mixin.noise.ImprovedNoiseAccessor;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

final class DfcOpenClNoiseDescriptor {
    static final int BRANCHES_PER_SLOT = 2;
    private static final int PERMUTATION_TABLE_SIZE = 256;
    static final int PERMUTATION_STRIDE = 512;
    private static final double NORMAL_NOISE_INPUT_FACTOR = 1.0181268882175227D;
    private static final double[] FLAT_SIMPLEX_GRAD = new double[]{
            1.0D, 1.0D, 0.0D, 0.0D,
            -1.0D, 1.0D, 0.0D, 0.0D,
            1.0D, -1.0D, 0.0D, 0.0D,
            -1.0D, -1.0D, 0.0D, 0.0D,
            1.0D, 0.0D, 1.0D, 0.0D,
            -1.0D, 0.0D, 1.0D, 0.0D,
            1.0D, 0.0D, -1.0D, 0.0D,
            -1.0D, 0.0D, -1.0D, 0.0D,
            0.0D, 1.0D, 1.0D, 0.0D,
            0.0D, -1.0D, 1.0D, 0.0D,
            0.0D, 1.0D, -1.0D, 0.0D,
            0.0D, -1.0D, -1.0D, 0.0D,
            1.0D, 1.0D, 0.0D, 0.0D,
            0.0D, -1.0D, 1.0D, 0.0D,
            -1.0D, 1.0D, 0.0D, 0.0D,
            0.0D, -1.0D, -1.0D, 0.0D
    };

    final byte[] permutations;
    final double[] origins;
    final double[] inputFactors;
    final double[] ampFactors;
    final int[] branchOctaveOffsets;
    final int[] branchOctaveCounts;
    final double[] branchCoordScales;
    final double[] slotValueFactors;
    final int slotCount;
    final int branchesPerSlot;
    final int octavesPerBranch;
    final int totalOctaves;
    private final BlendedSlot[] blendedSlots;

    private DfcOpenClNoiseDescriptor(byte[] permutations,
                                     double[] origins,
                                     double[] inputFactors,
                                     double[] ampFactors,
                                     int[] branchOctaveOffsets,
                                     int[] branchOctaveCounts,
                                     double[] branchCoordScales,
                                     double[] slotValueFactors,
                                     int slotCount,
                                     int branchesPerSlot,
                                     int octavesPerBranch,
                                     int totalOctaves,
                                     BlendedSlot[] blendedSlots) {
        this.permutations = permutations;
        this.origins = origins;
        this.inputFactors = inputFactors;
        this.ampFactors = ampFactors;
        this.branchOctaveOffsets = branchOctaveOffsets;
        this.branchOctaveCounts = branchOctaveCounts;
        this.branchCoordScales = branchCoordScales;
        this.slotValueFactors = slotValueFactors;
        this.slotCount = slotCount;
        this.branchesPerSlot = branchesPerSlot;
        this.octavesPerBranch = octavesPerBranch;
        this.totalOctaves = totalOctaves;
        this.blendedSlots = blendedSlots;
    }

    static DfcOpenClNoiseDescriptor synthetic(int slotCount, int octavesPerBranch) {
        int safeSlotCount = Math.max(2, slotCount);
        int safeOctaves = Math.max(1, octavesPerBranch);
        int totalOctaves = safeSlotCount * BRANCHES_PER_SLOT * safeOctaves;
        byte[] permutations = new byte[totalOctaves * PERMUTATION_STRIDE];
        double[] origins = new double[totalOctaves * 3];
        double[] inputFactors = new double[totalOctaves];
        double[] ampFactors = new double[totalOctaves];
        int branchCount = safeSlotCount * BRANCHES_PER_SLOT;
        int[] branchOffsets = new int[branchCount];
        int[] branchCounts = new int[branchCount];
        double[] branchScales = new double[branchCount];
        double[] slotFactors = new double[safeSlotCount];

        for (int slot = 0; slot < safeSlotCount; slot++) {
            slotFactors[slot] = slot == 0 ? 0.70D : slot == 1 ? -0.42D : 0.25D / (slot + 1.0D);
            for (int branch = 0; branch < BRANCHES_PER_SLOT; branch++) {
                int branchIndex = slot * BRANCHES_PER_SLOT + branch;
                branchOffsets[branchIndex] = branchIndex * safeOctaves;
                branchCounts[branchIndex] = safeOctaves;
                double branchBaseScale = slot == 0 ? 1.0D : 0.73D + slot * 0.03125D;
                branchScales[branchIndex] = branch == 0 ? branchBaseScale : branchBaseScale * NORMAL_NOISE_INPUT_FACTOR;
                for (int octave = 0; octave < safeOctaves; octave++) {
                    int index = (branchIndex * safeOctaves) + octave;
                    fillPermutation(permutations, index * PERMUTATION_STRIDE,
                            0x9E3779B97F4A7C15L + index * 0xD1B54A32D192ED03L);
                    origins[index * 3] = 17.125D + index * 0.73125D;
                    origins[index * 3 + 1] = -43.75D + index * 0.61875D;
                    origins[index * 3 + 2] = 101.375D - index * 0.4175D;
                    double base = 1.0D / (24.0D + (branchIndex & 3) * 8.0D);
                    inputFactors[index] = base * (1 << Math.min(octave, 20));
                    ampFactors[index] = (1.0D + (branchIndex & 3) * 0.0625D) * Math.pow(0.5D, octave);
                }
            }
        }

        return new DfcOpenClNoiseDescriptor(permutations, origins, inputFactors, ampFactors,
                branchOffsets, branchCounts, branchScales, slotFactors, safeSlotCount, BRANCHES_PER_SLOT, safeOctaves,
                totalOctaves, null);
    }

    static DfcOpenClNoiseDescriptor fromNoiseSpecs(NoiseSpec[] specs) {
        return fromNoiseSpecs(specs, null);
    }

    static DfcOpenClNoiseDescriptor fromNoiseSpecs(NoiseSpec[] specs, boolean[] inactiveSlots) {
        return fromCompiledPlan(specs, null, inactiveSlots);
    }

    static DfcOpenClNoiseDescriptor fromCompiledPlan(NoiseSpec[] specs,
                                                     BlendedNoiseSpec[] blendedSpecs,
                                                     boolean[] inactiveSlots) {
        if (specs == null || specs.length == 0) {
            throw new IllegalArgumentException("noise specs are empty");
        }
        int maxOctaves = 0;
        int totalOctaves = 0;
        for (int slot = 0; slot < specs.length; slot++) {
            NoiseSpec spec = specs[slot];
            BlendedNoiseSpec blended = blendedSpec(blendedSpecs, slot);
            if (spec != null && blended != null) {
                throw new IllegalArgumentException("slot " + slot + " has both normal and blended noise specs");
            }
            if (spec == null && blended == null) {
                if (isInactiveSlot(inactiveSlots, slot)) {
                    continue;
                }
                throw new IllegalArgumentException("noise spec is null");
            }
            if (spec != null) {
                maxOctaves = Math.max(maxOctaves, spec.first().activeOctaves().length);
                maxOctaves = Math.max(maxOctaves, spec.second().activeOctaves().length);
                totalOctaves += spec.first().activeOctaves().length;
                totalOctaves += spec.second().activeOctaves().length;
            } else {
                maxOctaves = Math.max(maxOctaves, BlendedNoiseSpec.LIMIT_OCTAVES);
                totalOctaves += countNonNull(blended.mainOctaves());
                totalOctaves += countNonNull(blended.minLimitOctaves());
                totalOctaves += countNonNull(blended.maxLimitOctaves());
            }
        }
        maxOctaves = Math.max(1, maxOctaves);
        totalOctaves = Math.max(1, totalOctaves);

        int slotCount = specs.length;
        byte[] permutations = new byte[totalOctaves * PERMUTATION_STRIDE];
        double[] origins = new double[totalOctaves * 3];
        double[] inputFactors = new double[totalOctaves];
        double[] ampFactors = new double[totalOctaves];
        int branchCount = slotCount * BRANCHES_PER_SLOT;
        int[] branchOffsets = new int[branchCount];
        int[] branchCounts = new int[branchCount];
        double[] branchScales = new double[branchCount];
        double[] slotFactors = new double[slotCount];
        BlendedSlot[] blendedSlots = blendedSpecs == null ? null : new BlendedSlot[slotCount];

        int nextOctave = 0;
        for (int slot = 0; slot < slotCount; slot++) {
            NoiseSpec spec = specs[slot];
            BlendedNoiseSpec blended = blendedSpec(blendedSpecs, slot);
            if (spec == null && blended == null && isInactiveSlot(inactiveSlots, slot)) {
                slotFactors[slot] = 1.0D;
                continue;
            }
            if (spec != null) {
                slotFactors[slot] = spec.valueFactor();
                nextOctave = fillBranch(spec.first(), slot, 0, nextOctave, permutations, origins, inputFactors,
                        ampFactors, branchOffsets, branchCounts, branchScales);
                nextOctave = fillBranch(spec.second(), slot, 1, nextOctave, permutations, origins, inputFactors,
                        ampFactors, branchOffsets, branchCounts, branchScales);
            } else {
                slotFactors[slot] = 1.0D;
                if (blendedSlots == null) {
                    blendedSlots = new BlendedSlot[slotCount];
                }
                BlendedSlot built = new BlendedSlot(
                        blended.xzMultiplier(),
                        blended.yMultiplier(),
                        blended.xzFactor(),
                        blended.yFactor(),
                        blended.smearScaleMultiplier(),
                        new int[BlendedNoiseSpec.MAIN_OCTAVES],
                        new int[BlendedNoiseSpec.LIMIT_OCTAVES],
                        new int[BlendedNoiseSpec.LIMIT_OCTAVES]);
                java.util.Arrays.fill(built.mainOctaves, -1);
                java.util.Arrays.fill(built.minLimitOctaves, -1);
                java.util.Arrays.fill(built.maxLimitOctaves, -1);
                nextOctave = fillBlendedOctaves(blended.mainOctaves(), built.mainOctaves,
                        nextOctave, permutations, origins);
                nextOctave = fillBlendedOctaves(blended.minLimitOctaves(), built.minLimitOctaves,
                        nextOctave, permutations, origins);
                nextOctave = fillBlendedOctaves(blended.maxLimitOctaves(), built.maxLimitOctaves,
                        nextOctave, permutations, origins);
                blendedSlots[slot] = built;
            }
        }

        return new DfcOpenClNoiseDescriptor(permutations, origins, inputFactors, ampFactors,
                branchOffsets, branchCounts, branchScales, slotFactors, slotCount, BRANCHES_PER_SLOT, maxOctaves,
                nextOctave, blendedSlots);
    }

    private static boolean isInactiveSlot(boolean[] inactiveSlots, int slot) {
        return inactiveSlots != null && slot >= 0 && slot < inactiveSlots.length && inactiveSlots[slot];
    }

    boolean isBlendedSlot(int slot) {
        return blendedSlot(slot) != null;
    }

    BlendedSlot blendedSlot(int slot) {
        return blendedSlots != null && slot >= 0 && slot < blendedSlots.length ? blendedSlots[slot] : null;
    }

    double sampleSlot(int slot, double bx, double by, double bz) {
        BlendedSlot blended = blendedSlot(slot);
        if (blended != null) {
            return sampleBlendedSlot(blended, bx, by, bz);
        }
        double value = 0.0D;
        int branchBase = slot * this.branchesPerSlot;
        for (int branch = 0; branch < this.branchesPerSlot; branch++) {
            int branchIndex = branchBase + branch;
            value += sampleBranch(branchIndex, bx, by, bz, this.branchCoordScales[branchIndex]);
        }
        return value * this.slotValueFactors[slot];
    }

    private double sampleBlendedSlot(BlendedSlot slot, double bx, double by, double bz) {
        double x = bx * slot.xzMultiplier;
        double y = by * slot.yMultiplier;
        double z = bz * slot.xzMultiplier;
        double mainX = x / slot.xzFactor;
        double mainY = y / slot.yFactor;
        double mainZ = z / slot.xzFactor;
        double smearY = slot.yMultiplier * slot.smearScaleMultiplier;
        double mainYScale = smearY / slot.yFactor;

        double main = 0.0D;
        for (int octave = 0; octave < slot.mainOctaves.length; octave++) {
            int index = slot.mainOctaves[octave];
            if (index >= 0) {
                double scale = 1.0D / (1L << octave);
                main += perlinSample(index * PERMUTATION_STRIDE,
                        this.origins[index * 3],
                        this.origins[index * 3 + 1],
                        this.origins[index * 3 + 2],
                        wrapAxis(mainX * scale),
                        wrapAxis(mainY * scale),
                        wrapAxis(mainZ * scale),
                        mainYScale * scale,
                        mainY * scale) / scale;
            }
        }

        double blend = (main / 10.0D + 1.0D) / 2.0D;
        boolean skipMin = blend >= 1.0D;
        boolean skipMax = blend <= 0.0D;
        double min = 0.0D;
        double max = 0.0D;
        for (int octave = 0; octave < BlendedNoiseSpec.LIMIT_OCTAVES; octave++) {
            double scale = 1.0D / (1L << octave);
            double sx = wrapAxis(x * scale);
            double sy = wrapAxis(y * scale);
            double sz = wrapAxis(z * scale);
            double yScale = smearY * scale;
            double yMax = y * scale;
            if (!skipMin) {
                int minIndex = slot.minLimitOctaves[octave];
                if (minIndex >= 0) {
                    min += perlinSample(minIndex * PERMUTATION_STRIDE,
                            this.origins[minIndex * 3],
                            this.origins[minIndex * 3 + 1],
                            this.origins[minIndex * 3 + 2],
                            sx, sy, sz, yScale, yMax) / scale;
                }
            }
            if (!skipMax) {
                int maxIndex = slot.maxLimitOctaves[octave];
                if (maxIndex >= 0) {
                    max += perlinSample(maxIndex * PERMUTATION_STRIDE,
                            this.origins[maxIndex * 3],
                            this.origins[maxIndex * 3 + 1],
                            this.origins[maxIndex * 3 + 2],
                            sx, sy, sz, yScale, yMax) / scale;
                }
            }
        }
        return clampedLerp(min / 512.0D, max / 512.0D, blend) / 128.0D;
    }

    private double sampleBranch(int branchIndex, double bx, double by, double bz, double coordScale) {
        double value = 0.0D;
        int octaveBase = this.branchOctaveOffsets[branchIndex];
        int octaveCount = this.branchOctaveCounts[branchIndex];
        double scaledX = bx * coordScale;
        double scaledY = by * coordScale;
        double scaledZ = bz * coordScale;
        for (int octave = 0; octave < octaveCount; octave++) {
            int index = octaveBase + octave;
            double ampFactor = this.ampFactors[index];
            if (ampFactor == 0.0D) {
                continue;
            }
            double inputFactor = this.inputFactors[index];
            value += ampFactor * perlinSample(index * PERMUTATION_STRIDE,
                    this.origins[index * 3],
                    this.origins[index * 3 + 1],
                    this.origins[index * 3 + 2],
                    wrapAxis(scaledX * inputFactor),
                    wrapAxis(scaledY * inputFactor),
                    wrapAxis(scaledZ * inputFactor));
        }
        return value;
    }

    private double perlinSample(int offset, double originX, double originY, double originZ,
                                double x, double y, double z) {
        return perlinSample(offset, originX, originY, originZ, x, y, z, 0.0D, 0.0D);
    }

    private double perlinSample(int offset, double originX, double originY, double originZ,
                                double x, double y, double z, double yScale, double yMax) {
        double inputX = x + originX;
        double inputY = y + originY;
        double inputZ = z + originZ;
        int gridX = javaFloor(inputX);
        int gridY = javaFloor(inputY);
        int gridZ = javaFloor(inputZ);
        double deltaX = inputX - gridX;
        double deltaY = inputY - gridY;
        double deltaZ = inputZ - gridZ;
        double shiftedDeltaY = deltaY;
        if (yScale != 0.0D) {
            double maxShift = yMax >= 0.0D && yMax < deltaY ? yMax : deltaY;
            shiftedDeltaY = deltaY - Math.floor(maxShift / yScale + 1.0E-7D) * yScale;
        }
        double x1 = deltaX - 1.0D;
        double y1 = shiftedDeltaY - 1.0D;
        double z1 = deltaZ - 1.0D;

        double n000 = perlinGrad(offset, gridX, gridY, gridZ, deltaX, shiftedDeltaY, deltaZ);
        double n100 = perlinGrad(offset, gridX + 1, gridY, gridZ, x1, shiftedDeltaY, deltaZ);
        double n010 = perlinGrad(offset, gridX, gridY + 1, gridZ, deltaX, y1, deltaZ);
        double n110 = perlinGrad(offset, gridX + 1, gridY + 1, gridZ, x1, y1, deltaZ);
        double n001 = perlinGrad(offset, gridX, gridY, gridZ + 1, deltaX, shiftedDeltaY, z1);
        double n101 = perlinGrad(offset, gridX + 1, gridY, gridZ + 1, x1, shiftedDeltaY, z1);
        double n011 = perlinGrad(offset, gridX, gridY + 1, gridZ + 1, deltaX, y1, z1);
        double n111 = perlinGrad(offset, gridX + 1, gridY + 1, gridZ + 1, x1, y1, z1);

        return lerp3(perlinFade(deltaX), perlinFade(deltaY), perlinFade(deltaZ),
                n000, n100, n010, n110, n001, n101, n011, n111);
    }

    private double perlinGrad(int offset, int px, int py, int pz, double fx, double fy, double fz) {
        int hash = perm(offset, perm(offset, perm(offset, px) + py) + pz) & 15;
        int grad = hash << 2;
        return FLAT_SIMPLEX_GRAD[grad] * fx
                + FLAT_SIMPLEX_GRAD[grad | 1] * fy
                + FLAT_SIMPLEX_GRAD[grad | 2] * fz;
    }

    private int perm(int offset, int index) {
        return this.permutations[offset + (index & 255)] & 0xFF;
    }

    private static int fillBranch(NoiseSpec.PerlinSpec branch, int slot, int branchInSlot, int octaveOffset,
                                   byte[] permutations, double[] origins, double[] inputFactors,
                                   double[] ampFactors, int[] branchOffsets, int[] branchCounts,
                                   double[] branchScales) {
        int branchIndex = slot * BRANCHES_PER_SLOT + branchInSlot;
        branchOffsets[branchIndex] = octaveOffset;
        branchScales[branchIndex] = branch.inputCoordScale();
        ImprovedNoise[] octaves = branch.activeOctaves();
        branchCounts[branchIndex] = octaves.length;
        for (int octave = 0; octave < octaves.length; octave++) {
            int index = octaveOffset + octave;
            ImprovedNoiseAccessor acc = (ImprovedNoiseAccessor) (Object) octaves[octave];
            byte[] p = acc.dfc$getPermutation();
            if (p == null || p.length < PERMUTATION_TABLE_SIZE) {
                throw new IllegalArgumentException("improved-noise permutation is shorter than 256");
            }
            int offset = index * PERMUTATION_STRIDE;
            System.arraycopy(p, 0, permutations, offset, PERMUTATION_TABLE_SIZE);
            System.arraycopy(p, 0, permutations, offset + PERMUTATION_TABLE_SIZE, PERMUTATION_TABLE_SIZE);
            origins[index * 3] = acc.dfc$getXo();
            origins[index * 3 + 1] = acc.dfc$getYo();
            origins[index * 3 + 2] = acc.dfc$getZo();
            inputFactors[index] = branch.inputFactors()[octave];
            ampFactors[index] = branch.ampValueFactors()[octave];
        }
        return octaveOffset + octaves.length;
    }

    private static int fillBlendedOctaves(ImprovedNoise[] octaves, int[] indices, int octaveOffset,
                                          byte[] permutations, double[] origins) {
        int nextOctave = octaveOffset;
        int limit = Math.min(octaves == null ? 0 : octaves.length, indices.length);
        for (int octave = 0; octave < limit; octave++) {
            ImprovedNoise noise = octaves[octave];
            if (noise == null) {
                continue;
            }
            indices[octave] = nextOctave;
            fillImprovedNoise(noise, nextOctave, permutations, origins);
            nextOctave++;
        }
        return nextOctave;
    }

    private static void fillImprovedNoise(ImprovedNoise noise, int index, byte[] permutations, double[] origins) {
        ImprovedNoiseAccessor acc = (ImprovedNoiseAccessor) (Object) noise;
        byte[] p = acc.dfc$getPermutation();
        if (p == null || p.length < PERMUTATION_TABLE_SIZE) {
            throw new IllegalArgumentException("improved-noise permutation is shorter than 256");
        }
        int offset = index * PERMUTATION_STRIDE;
        System.arraycopy(p, 0, permutations, offset, PERMUTATION_TABLE_SIZE);
        System.arraycopy(p, 0, permutations, offset + PERMUTATION_TABLE_SIZE, PERMUTATION_TABLE_SIZE);
        origins[index * 3] = acc.dfc$getXo();
        origins[index * 3 + 1] = acc.dfc$getYo();
        origins[index * 3 + 2] = acc.dfc$getZo();
    }

    private static BlendedNoiseSpec blendedSpec(BlendedNoiseSpec[] specs, int slot) {
        return specs != null && slot >= 0 && slot < specs.length ? specs[slot] : null;
    }

    private static int countNonNull(Object[] values) {
        int count = 0;
        if (values != null) {
            for (Object value : values) {
                if (value != null) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void fillPermutation(byte[] out, int offset, long seed) {
        fillIdentityPermutation(out, offset);
        for (int i = PERMUTATION_TABLE_SIZE - 1; i > 0; i--) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int j = (int) Long.remainderUnsigned(seed, i + 1L);
            byte tmp = out[offset + i];
            out[offset + i] = out[offset + j];
            out[offset + j] = tmp;
        }
        System.arraycopy(out, offset, out, offset + PERMUTATION_TABLE_SIZE, PERMUTATION_TABLE_SIZE);
    }

    private static void fillIdentityPermutation(byte[] out, int offset) {
        for (int i = 0; i < PERMUTATION_TABLE_SIZE; i++) {
            out[offset + i] = (byte) i;
            out[offset + PERMUTATION_TABLE_SIZE + i] = (byte) i;
        }
    }

    private static double perlinFade(double value) {
        return value * value * value * (value * (value * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp3(double dx, double dy, double dz,
                                double x0y0z0, double x1y0z0,
                                double x0y1z0, double x1y1z0,
                                double x0y0z1, double x1y0z1,
                                double x0y1z1, double x1y1z1) {
        double x00 = lerp(dx, x0y0z0, x1y0z0);
        double x10 = lerp(dx, x0y1z0, x1y1z0);
        double x01 = lerp(dx, x0y0z1, x1y0z1);
        double x11 = lerp(dx, x0y1z1, x1y1z1);
        return lerp(dz, lerp(dy, x00, x10), lerp(dy, x01, x11));
    }

    private static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    private static double clampedLerp(double start, double end, double delta) {
        if (delta < 0.0D) {
            return start;
        }
        if (delta > 1.0D) {
            return end;
        }
        return lerp(delta, start, end);
    }

    private static int javaFloor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    private static double wrapAxis(double value) {
        if (value >= -16777216.0D && value < 16777216.0D) {
            return value;
        }
        return value - Math.floor(value / 33554432.0D + 0.5D) * 33554432.0D;
    }

    record BlendedSlot(
            double xzMultiplier,
            double yMultiplier,
            double xzFactor,
            double yFactor,
            double smearScaleMultiplier,
            int[] mainOctaves,
            int[] minLimitOctaves,
            int[] maxLimitOctaves) {
    }
}
