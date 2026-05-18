package dev.sixik.generator_accelerator.common.density.compiler.opencl;

final class DfcOpenClSlabVmSmoke {
    static final int CELL_WIDTH = 4;
    static final int COUNT = CELL_WIDTH * CELL_WIDTH;
    static final double EPSILON = 1.0E-9;

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
}
