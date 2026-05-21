package dev.sixik.generator_accelerator.common.beardifier;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GABeardifierKernelTest {
    @Test
    void buryLookupMatchesFormula() {
        GABeardifierPlan plan = singlePiece(GABeardifierKernel.KIND_BURY);
        for (int x = 0; x <= 5; x++) {
            for (int y = -11; y <= 11; y++) {
                for (int z = 0; z <= 5; z++) {
                    double expected = GABeardifierKernel.getBuryContribution(x, (double) y * 0.5D, z);
                    assertEquals(expected, GABeardifierKernel.computeAt(plan, x, y, z), 0.0D);
                }
            }
        }
    }

    @Test
    void encapsulateLookupMatchesFormula() {
        GABeardifierPlan plan = singlePiece(GABeardifierKernel.KIND_ENCAPSULATE);
        for (int x = 0; x <= 11; x++) {
            for (int y = 0; y <= 11; y++) {
                for (int z = 0; z <= 11; z++) {
                    double expected = GABeardifierKernel.getBuryContribution(
                            (double) x * 0.5D,
                            (double) y * 0.5D,
                            (double) z * 0.5D
                    ) * 0.8D;
                    assertEquals(expected, GABeardifierKernel.computeAt(plan, x, y, z), 0.0D);
                }
            }
        }
    }

    @Test
    void cellFillMatchesScalarScanWithSpatialIndex() {
        GABeardifierKernel.setBeardKernel(kernel());
        GABeardifierPlan plan = densePlan();
        GABeardifierCellScratch scratch = new GABeardifierCellScratch();
        int cellWidth = 16;
        int cellHeight = 12;
        int startX = 10;
        int startY = 18;
        int startZ = 8;
        double[] out = new double[cellWidth * cellWidth * cellHeight];

        GABeardifierKernel.fillCell(plan, scratch, out, cellWidth, cellHeight, startX, startY, startZ);

        for (int lx = 0; lx < cellWidth; lx++) {
            for (int lz = 0; lz < cellWidth; lz++) {
                int base = ((lx * cellWidth) + lz) * cellHeight;
                for (int ly = 0; ly < cellHeight; ly++) {
                    int y = startY + ly;
                    int idx = base + (cellHeight - 1 - ly);
                    double expected = GABeardifierKernel.computeAt(plan, startX + lx, y, startZ + lz);
                    assertEquals(expected, out[idx], 1.0E-9D);
                }
            }
        }
    }

    @Test
    void spatialPointPathMatchesScalarScan() {
        GABeardifierKernel.setBeardKernel(kernel());
        GABeardifierPlan plan = densePlan();
        GABeardifierCellScratch scratch = new GABeardifierCellScratch();
        for (int x = -8; x <= 56; x += 7) {
            for (int y = 8; y <= 44; y += 5) {
                for (int z = -8; z <= 48; z += 9) {
                    double expected = GABeardifierKernel.computeAt(plan, x, y, z);
                    double actual = GABeardifierKernel.computeAt(plan, scratch, x, y, z);
                    assertEquals(expected, actual, 1.0E-9D);
                }
            }
        }
    }

    @Test
    void cachedCellSamplingMatchesFilledLayout() {
        GABeardifierKernel.setBeardKernel(kernel());
        GABeardifierPlan plan = densePlan();
        GABeardifierCellScratch scratch = new GABeardifierCellScratch();
        int cellWidth = 4;
        int cellHeight = 8;
        int startX = 12;
        int startY = 20;
        int startZ = 16;
        int cellValues = cellWidth * cellWidth * cellHeight;
        double[] out = scratch.ensureCachedCellValues(cellValues);

        GABeardifierKernel.fillCell(plan, scratch, out, cellWidth, cellHeight, startX, startY, startZ);
        scratch.cacheCell(plan, cellWidth, cellHeight, startX, startY, startZ, cellValues);

        for (int localX = 0; localX < cellWidth; localX++) {
            for (int localZ = 0; localZ < cellWidth; localZ++) {
                for (int localY = 0; localY < cellHeight; localY++) {
                    double expected = GABeardifierKernel.computeAt(
                            plan,
                            startX + localX,
                            startY + localY,
                            startZ + localZ
                    );
                    assertEquals(expected, scratch.sampleCachedCell(localX, localY, localZ), 1.0E-9D);
                }
            }
        }
    }

    @Test
    void kernelLookupOutOfRangeMatchesVanillaZero() throws ReflectiveOperationException {
        GABeardifierKernel.setBeardKernel(kernel());
        Method sameY = GABeardifierKernel.class.getDeclaredMethod(
                "getBeardContributionSameY",
                int.class,
                int.class,
                int.class
        );
        sameY.setAccessible(true);
        Method unchecked = GABeardifierKernel.class.getDeclaredMethod(
                "getBeardContributionUnchecked",
                int.class,
                int.class,
                int.class,
                int.class
        );
        unchecked.setAccessible(true);
        Method buryHalfY = GABeardifierKernel.class.getDeclaredMethod(
                "getBuryContributionHalfY",
                int.class,
                int.class,
                int.class
        );
        buryHalfY.setAccessible(true);
        Method encapsulate = GABeardifierKernel.class.getDeclaredMethod(
                "getEncapsulateContribution",
                int.class,
                int.class,
                int.class
        );
        encapsulate.setAccessible(true);

        assertEquals(0.0D, (double) sameY.invoke(null, -23, 0, 0), 0.0D);
        assertEquals(0.0D, (double) sameY.invoke(null, 0, -13, 0), 0.0D);
        assertEquals(0.0D, (double) sameY.invoke(null, 0, 0, 12), 0.0D);
        assertEquals(0.0D, (double) unchecked.invoke(null, 12, 0, 0, 0), 0.0D);
        assertEquals(0.0D, (double) unchecked.invoke(null, 0, -13, 0, 0), 0.0D);
        assertEquals(0.0D, (double) buryHalfY.invoke(null, 6, 0, 0), 0.0D);
        assertEquals(0.0D, (double) buryHalfY.invoke(null, 0, 12, 0), 0.0D);
        assertEquals(0.0D, (double) encapsulate.invoke(null, 12, 0, 0), 0.0D);
        assertEquals(0.0D, (double) encapsulate.invoke(null, 14, 9, 5), 0.0D);
    }

    private static GABeardifierPlan densePlan() {
        int pieceCount = 24;
        int[] minX = new int[pieceCount];
        int[] maxX = new int[pieceCount];
        int[] minY = new int[pieceCount];
        int[] maxY = new int[pieceCount];
        int[] minZ = new int[pieceCount];
        int[] maxZ = new int[pieceCount];
        int[] groundY = new int[pieceCount];
        byte[] terrain = new byte[pieceCount];
        for (int i = 0; i < pieceCount; i++) {
            int x = (i % 6) * 8;
            int y = 18 + (i % 4) * 4;
            int z = (i / 6) * 8;
            minX[i] = x;
            maxX[i] = x + 3;
            minY[i] = y;
            maxY[i] = y + 5;
            minZ[i] = z;
            maxZ[i] = z + 3;
            groundY[i] = y + 1;
            terrain[i] = switch (i & 3) {
                case 0 -> GABeardifierKernel.KIND_BURY;
                case 1 -> GABeardifierKernel.KIND_BEARD_THIN;
                case 2 -> GABeardifierKernel.KIND_BEARD_BOX;
                default -> GABeardifierKernel.KIND_ENCAPSULATE;
            };
        }
        return GABeardifierPlan.create(
                minX,
                maxX,
                minY,
                maxY,
                minZ,
                maxZ,
                groundY,
                terrain,
                new int[]{9, 21, 35, 46},
                new int[]{20, 28, 24, 32},
                new int[]{10, 18, 30, 38}
        );
    }

    private static GABeardifierPlan singlePiece(byte terrain) {
        return GABeardifierPlan.create(
                new int[]{0},
                new int[]{0},
                new int[]{0},
                new int[]{0},
                new int[]{0},
                new int[]{0},
                new int[]{0},
                new byte[]{terrain},
                new int[0],
                new int[0],
                new int[0]
        );
    }

    private static float[] kernel() {
        float[] kernel = new float[24 * 24 * 24];
        for (int z = -12; z < 12; z++) {
            for (int x = -12; x < 12; x++) {
                for (int y = -12; y < 12; y++) {
                    int index = ((z + 12) * 24 + (x + 12)) * 24 + (y + 12);
                    kernel[index] = 1.0F / (1.0F + Math.abs(x) + Math.abs(y) + Math.abs(z));
                }
            }
        }
        return kernel;
    }
}
