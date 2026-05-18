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
        double squeeze = 1.0D / 2.0D - 1.0D / 24.0D;
        for (int i = 0; i < COUNT; i++) {
            int ix = i / CELL_WIDTH;
            int iz = i - ix * CELL_WIDTH;
            double expected = i * 0.5D
                    + (10.0D - i * 0.25D)
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
}
