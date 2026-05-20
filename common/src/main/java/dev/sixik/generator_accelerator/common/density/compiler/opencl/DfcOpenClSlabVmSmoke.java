package dev.sixik.generator_accelerator.common.density.compiler.opencl;

final class DfcOpenClSlabVmSmoke {
    static final int CELL_WIDTH = 4;
    static final int COUNT = CELL_WIDTH * CELL_WIDTH;
    static final double EPSILON = 1.0E-9;
    static final double NOISE_EPSILON = 1.0E-7;
    static final int NOISE_SLOT_COUNT = 2;
    static final int NOISE_HEAVY_SLOT_COUNT = 4;
    static final int NOISE_OCTAVES_PER_BRANCH = 2;
    static final int NOISE_HEAVY_OCTAVES_PER_BRANCH = 8;
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

    private DfcOpenClSlabVmSmoke() {
    }

    static byte[] bytecode() {
        return new byte[]{
                2, 0,
                2, 1,
                32,
                19,
                32,
                16,
                32,
                18,
                33,
                17,
                1, 0, 0,
                34,
                51,
                32
        };
    }

    static double[] constants() {
        return new double[]{0.1D};
    }

    static double[] slotRowsFlat() {
        double[] slots = new double[COUNT * 2];
        for (int i = 0; i < COUNT; i++) {
            slots[i] = i * 0.5D;
            slots[COUNT + i] = 10.0D - i * 0.25D;
        }
        return slots;
    }

    static DfcOpenClDeviceContext.SlabVmRequest request(double[] out) {
        return new DfcOpenClDeviceContext.SlabVmRequest(
                bytecode(),
                constants(),
                slotRowsFlat(),
                2,
                COUNT,
                100,
                200,
                64,
                CELL_WIDTH,
                0,
                0,
                0,
                0,
                3.25D,
                out,
                COUNT);
    }

    static void validate(double[] out) {
        validate(out, 1);
    }

    static DfcOpenClDeviceContext.SlabVmCoordsRequest coordsRequest(double[] out, int repeats) {
        int safeRepeats = Math.max(1, repeats);
        int n = COUNT * safeRepeats;
        double[] slots = new double[n * 2];
        double[] blockX = new double[n];
        double[] blockY = new double[n];
        double[] blockZ = new double[n];
        double[] hoist = new double[n];
        for (int i = 0; i < n; i++) {
            int base = i % COUNT;
            int ix = base / CELL_WIDTH;
            int iz = base - ix * CELL_WIDTH;
            slots[i] = base * 0.5D;
            slots[n + i] = 10.0D - base * 0.25D;
            blockX[i] = 100 + ix;
            blockY[i] = 64;
            blockZ[i] = 200 + iz;
            hoist[i] = 3.25D;
        }
        return new DfcOpenClDeviceContext.SlabVmCoordsRequest(
                bytecode(),
                constants(),
                slots,
                2,
                n,
                blockX,
                blockY,
                blockZ,
                hoist,
                out,
                n);
    }

    static long cellCoordElementCount(int cellWidth, int cellHeight, int cells) {
        return (long) cellWidth * cellWidth * cellHeight * cells;
    }

    static DfcOpenClDeviceContext.SlabVmCoordsRequest cellCoordsRequest(double[] out, int cellWidth, int cellHeight,
                                                                        int cells) {
        int safeCellWidth = Math.max(1, cellWidth);
        int safeCellHeight = Math.max(1, cellHeight);
        int safeCells = Math.max(1, cells);
        long elements = cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int n = Math.toIntExact(elements);
        double[] slots = new double[n * 2];
        double[] blockX = new double[n];
        double[] blockY = new double[n];
        double[] blockZ = new double[n];
        double[] hoist = new double[n];
        for (int i = 0; i < n; i++) {
            slots[i] = cellSlot0(i);
            slots[n + i] = cellSlot1(i);
            blockX[i] = cellBlockX(i, safeCellWidth, safeCellHeight);
            blockY[i] = cellBlockY(i, safeCellWidth, safeCellHeight);
            blockZ[i] = cellBlockZ(i, safeCellWidth, safeCellHeight);
            hoist[i] = cellHoist(i, safeCellWidth, safeCellHeight);
        }
        return new DfcOpenClDeviceContext.SlabVmCoordsRequest(
                bytecode(),
                constants(),
                slots,
                2,
                n,
                blockX,
                blockY,
                blockZ,
                hoist,
                out,
                n);
    }

    static DfcOpenClDeviceContext.SlabVmCellGridRequest cellGridRequest(double[] out, int cellWidth, int cellHeight,
                                                                        int cells) {
        int safeCellWidth = Math.max(1, cellWidth);
        int safeCellHeight = Math.max(1, cellHeight);
        int safeCells = Math.max(1, cells);
        long elements = cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells);
        int n = Math.toIntExact(elements);
        double[] slots = new double[n * 2];
        for (int i = 0; i < n; i++) {
            slots[i] = cellSlot0(i);
            slots[n + i] = cellSlot1(i);
        }
        return new DfcOpenClDeviceContext.SlabVmCellGridRequest(
                bytecode(),
                constants(),
                slots,
                2,
                n,
                100,
                64,
                200,
                safeCellWidth,
                safeCellHeight,
                safeCells,
                3.25D,
                out,
                n);
    }

    static DfcOpenClDeviceContext.SlabVmGeneratedCellGridRequest generatedCellGridRequest(double[] out, int cellWidth,
                                                                                         int cellHeight, int cells) {
        int safeCellWidth = Math.max(1, cellWidth);
        int safeCellHeight = Math.max(1, cellHeight);
        int safeCells = Math.max(1, cells);
        int n = Math.toIntExact(cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells));
        return new DfcOpenClDeviceContext.SlabVmGeneratedCellGridRequest(
                bytecode(),
                constants(),
                2,
                100,
                64,
                200,
                safeCellWidth,
                safeCellHeight,
                safeCells,
                3.25D,
                out,
                n);
    }

    static DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest noiseCellGridRequest(double[] out, int cellWidth,
                                                                                  int cellHeight, int cells) {
        return noiseCellGridRequest(out, cellWidth, cellHeight, cells,
                NOISE_SLOT_COUNT, NOISE_OCTAVES_PER_BRANCH);
    }

    static DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest noiseCellGridRequest(double[] out, int cellWidth,
                                                                                  int cellHeight, int cells,
                                                                                  int slotCount,
                                                                                  int octavesPerBranch) {
        return noiseCellGridRequest(out, cellWidth, cellHeight, cells,
                DfcOpenClNoiseDescriptor.synthetic(slotCount, octavesPerBranch));
    }

    static DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest noiseCellGridRequest(double[] out, int cellWidth,
                                                                                  int cellHeight, int cells,
                                                                                  DfcOpenClNoiseDescriptor descriptor) {
        int safeCellWidth = Math.max(1, cellWidth);
        int safeCellHeight = Math.max(1, cellHeight);
        int safeCells = Math.max(1, cells);
        int n = Math.toIntExact(cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells));
        return new DfcOpenClDeviceContext.SlabVmNoiseCellGridRequest(
                bytecode(),
                constants(),
                descriptor.permutations,
                descriptor.origins,
                descriptor.inputFactors,
                descriptor.ampFactors,
                descriptor.branchOctaveOffsets,
                descriptor.branchOctaveCounts,
                descriptor.branchCoordScales,
                descriptor.slotValueFactors,
                descriptor.slotCount,
                descriptor.branchesPerSlot,
                descriptor.octavesPerBranch,
                100,
                64,
                200,
                safeCellWidth,
                safeCellHeight,
                safeCells,
                DfcOpenClRuntime.CELL_GRID_LAYOUT_XZ,
                3.25D,
                out,
                n);
    }

    static void validate(double[] out, int repeats) {
        int safeRepeats = Math.max(1, repeats);
        if (out.length < COUNT * safeRepeats) {
            throw new IllegalStateException("OpenCL slab VM smoke output too short: " + out.length);
        }
        double squeeze = 1.0D / 2.0D - 1.0D / 24.0D;
        for (int i = 0; i < COUNT * safeRepeats; i++) {
            int base = i % COUNT;
            int ix = base / CELL_WIDTH;
            int iz = base - ix * CELL_WIDTH;
            double expected = base * 0.5D
                    + (10.0D - base * 0.25D)
                    + 3.25D
                    + (100 + ix)
                    - (200 + iz)
                    + squeeze;
            double actual = out[i];
            if (!Double.isFinite(actual) || Math.abs(actual - expected) > EPSILON) {
                throw new IllegalStateException("OpenCL slab VM smoke mismatch at " + i
                        + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    static void validateCellCoords(double[] out, int cellWidth, int cellHeight, int cells) {
        int safeCellWidth = Math.max(1, cellWidth);
        int safeCellHeight = Math.max(1, cellHeight);
        int safeCells = Math.max(1, cells);
        int n = Math.toIntExact(cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells));
        if (out.length < n) {
            throw new IllegalStateException("OpenCL slab VM cell output too short: " + out.length);
        }
        for (int i = 0; i < n; i++) {
            double blockY = cellBlockY(i, safeCellWidth, safeCellHeight);
            double expected = cellSlot0(i)
                    + cellSlot1(i)
                    + cellHoist(i, safeCellWidth, safeCellHeight)
                    + cellBlockX(i, safeCellWidth, safeCellHeight)
                    - cellBlockZ(i, safeCellWidth, safeCellHeight)
                    + squeeze(blockY * 0.1D);
            double actual = out[i];
            if (!Double.isFinite(actual) || Math.abs(actual - expected) > EPSILON) {
                throw new IllegalStateException("OpenCL slab VM cell mismatch at " + i
                        + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    static void validateNoiseCellGrid(double[] out, int cellWidth, int cellHeight, int cells) {
        validateNoiseCellGrid(out, cellWidth, cellHeight, cells, NOISE_SLOT_COUNT, NOISE_OCTAVES_PER_BRANCH);
    }

    static void validateNoiseCellGrid(double[] out, int cellWidth, int cellHeight, int cells,
                                      int slotCount, int octavesPerBranch) {
        validateNoiseCellGrid(out, cellWidth, cellHeight, cells,
                DfcOpenClNoiseDescriptor.synthetic(slotCount, octavesPerBranch));
    }

    static void validateNoiseCellGrid(double[] out, int cellWidth, int cellHeight, int cells,
                                      DfcOpenClNoiseDescriptor descriptor) {
        validateDirectNoiseCellGrid(out, cellWidth, cellHeight, cells, descriptor, 2);
    }

    static void validateDirectNoiseCellGrid(double[] out, int cellWidth, int cellHeight, int cells,
                                            DfcOpenClNoiseDescriptor descriptor, int usedSlotCount) {
        int safeCellWidth = Math.max(1, cellWidth);
        int safeCellHeight = Math.max(1, cellHeight);
        int safeCells = Math.max(1, cells);
        int safeUsedSlots = Math.min(Math.max(1, usedSlotCount), descriptor.slotCount);
        int n = Math.toIntExact(cellCoordElementCount(safeCellWidth, safeCellHeight, safeCells));
        if (out.length < n) {
            throw new IllegalStateException("OpenCL slab VM noise output too short: " + out.length);
        }
        int checks = Math.min(n, 257);
        for (int check = 0; check < checks; check++) {
            int i = checks == 1 ? 0 : (int) ((long) check * (n - 1) / (checks - 1));
            double bx = cellBlockX(i, safeCellWidth, safeCellHeight);
            double by = cellBlockY(i, safeCellWidth, safeCellHeight);
            double bz = cellBlockZ(i, safeCellWidth, safeCellHeight);
            double expected = cellHoist(i, safeCellWidth, safeCellHeight)
                    + bx
                    - bz
                    + squeeze(by * 0.1D);
            for (int slot = 0; slot < safeUsedSlots; slot++) {
                expected += descriptor.sampleSlot(slot, bx, by, bz);
            }
            double actual = out[i];
            if (!Double.isFinite(actual) || Math.abs(actual - expected) > NOISE_EPSILON) {
                throw new IllegalStateException("OpenCL slab VM noise mismatch at " + i
                        + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    private static double cellSlot0(int element) {
        return (element & 63) * 0.5D;
    }

    private static double cellSlot1(int element) {
        return 10.0D - (element & 31) * 0.25D;
    }

    private static double cellHoist(int element, int cellWidth, int cellHeight) {
        int cell = element / (cellWidth * cellWidth * cellHeight);
        return 3.25D + (cell & 7) * 0.03125D;
    }

    private static double cellBlockX(int element, int cellWidth, int cellHeight) {
        int cellVolume = cellWidth * cellWidth * cellHeight;
        int cell = element / cellVolume;
        int inCell = element - cell * cellVolume;
        int plane = inCell % (cellWidth * cellWidth);
        int ix = plane / cellWidth;
        int cellX = cell & 31;
        return 100 + cellX * cellWidth + ix;
    }

    private static double cellBlockY(int element, int cellWidth, int cellHeight) {
        int planeSize = cellWidth * cellWidth;
        int inCell = element % (planeSize * cellHeight);
        int yIndex = inCell / planeSize;
        return 64 + (cellHeight - 1 - yIndex);
    }

    private static double cellBlockZ(int element, int cellWidth, int cellHeight) {
        int cellVolume = cellWidth * cellWidth * cellHeight;
        int cell = element / cellVolume;
        int inCell = element - cell * cellVolume;
        int plane = inCell % (cellWidth * cellWidth);
        int iz = plane - (plane / cellWidth) * cellWidth;
        int cellZ = cell >>> 5;
        return 200 + cellZ * cellWidth + iz;
    }

    private static double squeeze(double value) {
        double clamped = value < -1.0D ? -1.0D : Math.min(value, 1.0D);
        return clamped / 2.0D - clamped * clamped * clamped / 24.0D;
    }

    private static double noiseSlotValue(int slot, double bx, double by, double bz,
                                         DfcOpenClNoiseDescriptor descriptor) {
        double value = 0.0D;
        int branchBase = slot * descriptor.branchesPerSlot;
        for (int branch = 0; branch < descriptor.branchesPerSlot; branch++) {
            int branchIndex = branchBase + branch;
            value += noiseBranchValue(branchIndex, bx, by, bz, descriptor, descriptor.branchCoordScales[branchIndex]);
        }
        return value * descriptor.slotValueFactors[slot];
    }

    private static double noiseBranchValue(int branchIndex, double bx, double by, double bz,
                                           DfcOpenClNoiseDescriptor descriptor, double coordScale) {
        double value = 0.0D;
        int octaveBase = descriptor.branchOctaveOffsets[branchIndex];
        int octaveCount = descriptor.branchOctaveCounts[branchIndex];
        double scaledX = bx * coordScale;
        double scaledY = by * coordScale;
        double scaledZ = bz * coordScale;
        for (int octave = 0; octave < octaveCount; octave++) {
            int index = octaveBase + octave;
            double inputFactor = descriptor.inputFactors[index];
            value += descriptor.ampFactors[index] * perlinSample(descriptor.permutations,
                    index * DfcOpenClNoiseDescriptor.PERMUTATION_STRIDE,
                    descriptor.origins[index * 3],
                    descriptor.origins[index * 3 + 1],
                    descriptor.origins[index * 3 + 2],
                    wrapAxis(scaledX * inputFactor),
                    wrapAxis(scaledY * inputFactor),
                    wrapAxis(scaledZ * inputFactor));
        }
        return value;
    }

    private static double perlinSample(byte[] permutations, int offset, double originX, double originY, double originZ,
                                       double x, double y, double z) {
        double inputX = x + originX;
        double inputY = y + originY;
        double inputZ = z + originZ;
        int gridX = javaFloor(inputX);
        int gridY = javaFloor(inputY);
        int gridZ = javaFloor(inputZ);
        double deltaX = inputX - gridX;
        double deltaY = inputY - gridY;
        double deltaZ = inputZ - gridZ;
        double x1 = deltaX - 1.0D;
        double y1 = deltaY - 1.0D;
        double z1 = deltaZ - 1.0D;

        double n000 = perlinGrad(permutations, offset, gridX, gridY, gridZ, deltaX, deltaY, deltaZ);
        double n100 = perlinGrad(permutations, offset, gridX + 1, gridY, gridZ, x1, deltaY, deltaZ);
        double n010 = perlinGrad(permutations, offset, gridX, gridY + 1, gridZ, deltaX, y1, deltaZ);
        double n110 = perlinGrad(permutations, offset, gridX + 1, gridY + 1, gridZ, x1, y1, deltaZ);
        double n001 = perlinGrad(permutations, offset, gridX, gridY, gridZ + 1, deltaX, deltaY, z1);
        double n101 = perlinGrad(permutations, offset, gridX + 1, gridY, gridZ + 1, x1, deltaY, z1);
        double n011 = perlinGrad(permutations, offset, gridX, gridY + 1, gridZ + 1, deltaX, y1, z1);
        double n111 = perlinGrad(permutations, offset, gridX + 1, gridY + 1, gridZ + 1, x1, y1, z1);

        return lerp3(perlinFade(deltaX), perlinFade(deltaY), perlinFade(deltaZ),
                n000, n100, n010, n110, n001, n101, n011, n111);
    }

    private static double perlinGrad(byte[] permutations, int offset, int px, int py, int pz,
                                     double fx, double fy, double fz) {
        int hash = perm(permutations, offset, perm(permutations, offset, perm(permutations, offset, px) + py) + pz) & 15;
        int grad = hash << 2;
        return FLAT_SIMPLEX_GRAD[grad] * fx
                + FLAT_SIMPLEX_GRAD[grad | 1] * fy
                + FLAT_SIMPLEX_GRAD[grad | 2] * fz;
    }

    private static int perm(byte[] permutations, int offset, int index) {
        return permutations[offset + (index & 255)] & 0xFF;
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
}
