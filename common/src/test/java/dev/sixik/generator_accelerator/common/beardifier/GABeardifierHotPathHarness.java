package dev.sixik.generator_accelerator.common.beardifier;

import net.minecraft.util.Mth;

import java.util.Random;

final class GABeardifierHotPathHarness {
    private static final int TERRAIN_BURY = 1;
    private static final int TERRAIN_THIN = 2;
    private static final int TERRAIN_BOX = 3;
    private static final int TERRAIN_ENCAPSULATE = 4;
    private static final int BURY_RADIUS = 5;
    private static final int BEARD_RADIUS = 11;
    private static final int KERNEL_RADIUS = 12;
    private static final int KERNEL_SIZE = 24;
    private static final float[] BEARD_KERNEL = createKernel();
    private static final float[] BEARD_SAME_Y = createSameYTable();

    private static volatile long sink;

    private GABeardifierHotPathHarness() {
    }

    static Scenario createScenario(int pieceCount, int junctionCount, long seed) {
        Random random = new Random(seed);
        int buryCount = (pieceCount + 3) / 4;
        int thinCount = (pieceCount + 2) / 4;
        int boxCount = (pieceCount + 1) / 4;
        int encapsulateCount = pieceCount / 4;

        int[] ends = new int[4];
        GABeardifierTerrainRanges.fillEnds(buryCount, thinCount, boxCount, encapsulateCount, ends);

        int[] minX = new int[pieceCount];
        int[] maxX = new int[pieceCount];
        int[] minY = new int[pieceCount];
        int[] maxY = new int[pieceCount];
        int[] minZ = new int[pieceCount];
        int[] maxZ = new int[pieceCount];
        int[] groundY = new int[pieceCount];
        int[] influenceMinX = new int[pieceCount];
        int[] influenceMaxX = new int[pieceCount];
        int[] influenceMinY = new int[pieceCount];
        int[] influenceMaxY = new int[pieceCount];
        int[] influenceMinZ = new int[pieceCount];
        int[] influenceMaxZ = new int[pieceCount];
        byte[] terrain = new byte[pieceCount];

        int buryPos = 0;
        int thinPos = ends[0];
        int boxPos = ends[1];
        int encapsulatePos = ends[2];
        for (int rawIndex = 0; rawIndex < pieceCount; rawIndex++) {
            int terrainKind = switch (rawIndex & 3) {
                case 0 -> TERRAIN_BURY;
                case 1 -> TERRAIN_THIN;
                case 2 -> TERRAIN_BOX;
                default -> TERRAIN_ENCAPSULATE;
            };
            int pieceIndex = switch (terrainKind) {
                case TERRAIN_BURY -> buryPos++;
                case TERRAIN_THIN -> thinPos++;
                case TERRAIN_BOX -> boxPos++;
                default -> encapsulatePos++;
            };

            int pieceMinX = random.nextInt(96) - 48;
            int pieceMinY = 24 + random.nextInt(80);
            int pieceMinZ = random.nextInt(96) - 48;
            int pieceMaxX = pieceMinX + 2 + random.nextInt(12);
            int pieceMaxY = pieceMinY + 3 + random.nextInt(16);
            int pieceMaxZ = pieceMinZ + 2 + random.nextInt(12);
            int pieceGroundY = pieceMinY + random.nextInt(5) - 2;

            minX[pieceIndex] = pieceMinX;
            maxX[pieceIndex] = pieceMaxX;
            minY[pieceIndex] = pieceMinY;
            maxY[pieceIndex] = pieceMaxY;
            minZ[pieceIndex] = pieceMinZ;
            maxZ[pieceIndex] = pieceMaxZ;
            groundY[pieceIndex] = pieceGroundY;
            terrain[pieceIndex] = (byte) terrainKind;
            initInfluenceBounds(
                    terrainKind,
                    pieceMinX,
                    pieceMaxX,
                    pieceMinY,
                    pieceMaxY,
                    pieceMinZ,
                    pieceMaxZ,
                    pieceGroundY,
                    influenceMinX,
                    influenceMaxX,
                    influenceMinY,
                    influenceMaxY,
                    influenceMinZ,
                    influenceMaxZ,
                    pieceIndex
            );
        }

        int[] junctionX = new int[junctionCount];
        int[] junctionY = new int[junctionCount];
        int[] junctionZ = new int[junctionCount];
        for (int i = 0; i < junctionCount; i++) {
            junctionX[i] = random.nextInt(112) - 56;
            junctionY[i] = 28 + random.nextInt(84);
            junctionZ[i] = random.nextInt(112) - 56;
        }

        int[] activePieces = new int[pieceCount];
        int[] activeJunctions = new int[junctionCount];
        for (int i = 0; i < pieceCount; i++) {
            activePieces[i] = i;
        }
        for (int i = 0; i < junctionCount; i++) {
            activeJunctions[i] = i;
        }

        return new Scenario(
                minX,
                maxX,
                minY,
                maxY,
                minZ,
                maxZ,
                groundY,
                influenceMinX,
                influenceMaxX,
                influenceMinY,
                influenceMaxY,
                influenceMinZ,
                influenceMaxZ,
                terrain,
                junctionX,
                junctionY,
                junctionZ,
                activePieces,
                activeJunctions,
                buryCount,
                thinCount,
                boxCount,
                encapsulateCount
        );
    }

    static double legacyScalarAt(Scenario scenario, int blockX, int blockY, int blockZ) {
        double sum = 0.0D;
        for (int pieceIndex = 0; pieceIndex < scenario.terrain.length; pieceIndex++) {
            int terrainKind = scenario.terrain[pieceIndex] & 0xFF;
            if (blockX < scenario.influenceMinX[pieceIndex] || blockX > scenario.influenceMaxX[pieceIndex]
                    || blockY < scenario.influenceMinY[pieceIndex] || blockY > scenario.influenceMaxY[pieceIndex]
                    || blockZ < scenario.influenceMinZ[pieceIndex] || blockZ > scenario.influenceMaxZ[pieceIndex]) {
                continue;
            }

            int dx = Math.max(0, Math.max(scenario.minX[pieceIndex] - blockX, blockX - scenario.maxX[pieceIndex]));
            int dz = Math.max(0, Math.max(scenario.minZ[pieceIndex] - blockZ, blockZ - scenario.maxZ[pieceIndex]));
            if (terrainKind == TERRAIN_BURY) {
                sum += GABeardifierContributionMath.bury(dx, blockY - scenario.groundY[pieceIndex], dz);
            } else if (terrainKind == TERRAIN_THIN) {
                sum += beardSameY(dx, blockY - scenario.groundY[pieceIndex], dz) * 0.8D;
            } else if (terrainKind == TERRAIN_BOX) {
                int verticalOffset = blockY - scenario.groundY[pieceIndex];
                int yDistance = GABeardifierContributionMath.boxYDistance(blockY, scenario.groundY[pieceIndex], scenario.maxY[pieceIndex]);
                sum += beardUnchecked(dx, yDistance, dz, verticalOffset) * 0.8D;
            } else if (terrainKind == TERRAIN_ENCAPSULATE) {
                int yDistance = GABeardifierContributionMath.encapsulateYDistance(blockY, scenario.minY[pieceIndex], scenario.maxY[pieceIndex]);
                sum += GABeardifierContributionMath.buryHalfScaled(dx, yDistance, dz) * 0.8D;
            }
        }

        for (int junctionIndex = 0; junctionIndex < scenario.junctionX.length; junctionIndex++) {
            int dx = blockX - scenario.junctionX[junctionIndex];
            int dy = blockY - scenario.junctionY[junctionIndex];
            int dz = blockZ - scenario.junctionZ[junctionIndex];
            if (inKernelRange(dx) && inKernelRange(dy) && inKernelRange(dz)) {
                sum += beardSameY(dx, dy, dz) * 0.4D;
            }
        }
        return sum;
    }

    static double optimizedScalarAt(Scenario scenario, int blockX, int blockY, int blockZ) {
        return singlePassScalarAt(scenario, blockX, blockY, blockZ);
    }

    static double singlePassScalarAt(Scenario scenario, int blockX, int blockY, int blockZ) {
        double sum = 0.0D;
        int[] minX = scenario.minX;
        int[] maxX = scenario.maxX;
        int[] minY = scenario.minY;
        int[] maxY = scenario.maxY;
        int[] minZ = scenario.minZ;
        int[] maxZ = scenario.maxZ;
        int[] groundY = scenario.groundY;
        int[] influenceMinX = scenario.influenceMinX;
        int[] influenceMaxX = scenario.influenceMaxX;
        int[] influenceMinY = scenario.influenceMinY;
        int[] influenceMaxY = scenario.influenceMaxY;
        int[] influenceMinZ = scenario.influenceMinZ;
        int[] influenceMaxZ = scenario.influenceMaxZ;
        byte[] terrain = scenario.terrain;
        for (int pieceIndex = 0; pieceIndex < terrain.length; pieceIndex++) {
            if (blockX < influenceMinX[pieceIndex] || blockX > influenceMaxX[pieceIndex]
                    || blockY < influenceMinY[pieceIndex] || blockY > influenceMaxY[pieceIndex]
                    || blockZ < influenceMinZ[pieceIndex] || blockZ > influenceMaxZ[pieceIndex]) {
                continue;
            }
            int dx = minX[pieceIndex] - blockX;
            if (dx < 0) {
                dx = blockX - maxX[pieceIndex];
                if (dx < 0) {
                    dx = 0;
                }
            }
            int dz = minZ[pieceIndex] - blockZ;
            if (dz < 0) {
                dz = blockZ - maxZ[pieceIndex];
                if (dz < 0) {
                    dz = 0;
                }
            }

            int terrainKind = terrain[pieceIndex] & 0xFF;
            if (terrainKind == TERRAIN_BURY) {
                sum += GABeardifierContributionMath.bury(dx, blockY - groundY[pieceIndex], dz);
            } else if (terrainKind == TERRAIN_THIN) {
                sum += beardSameY(dx, blockY - groundY[pieceIndex], dz) * 0.8D;
            } else if (terrainKind == TERRAIN_BOX) {
                int ground = groundY[pieceIndex];
                int verticalOffset = blockY - ground;
                int yDistance = ground - blockY;
                if (yDistance < 0) {
                    yDistance = blockY - maxY[pieceIndex];
                    if (yDistance < 0) {
                        yDistance = 0;
                    }
                }
                sum += beardUnchecked(dx, yDistance, dz, verticalOffset) * 0.8D;
            } else if (terrainKind == TERRAIN_ENCAPSULATE) {
                int yDistance = minY[pieceIndex] - blockY;
                if (yDistance < 0) {
                    yDistance = blockY - maxY[pieceIndex];
                    if (yDistance < 0) {
                        yDistance = 0;
                    }
                }
                sum += GABeardifierContributionMath.buryHalfScaled(dx, yDistance, dz) * 0.8D;
            }
        }

        for (int junctionIndex = 0; junctionIndex < scenario.junctionX.length; junctionIndex++) {
            int dx = blockX - scenario.junctionX[junctionIndex];
            int dy = blockY - scenario.junctionY[junctionIndex];
            int dz = blockZ - scenario.junctionZ[junctionIndex];
            if (inKernelRange(dx) && inKernelRange(dy) && inKernelRange(dz)) {
                sum += beardSameY(dx, dy, dz) * 0.4D;
            }
        }
        return sum;
    }

    static double[] fillCellLegacy(Scenario scenario, int startX, int startY, int startZ, int cellW, int cellH) {
        double[] out = new double[cellW * cellW * cellH];
        fillCellLegacy(scenario, startX, startY, startZ, cellW, cellH, out);
        return out;
    }

    static double[] fillCellOptimized(Scenario scenario, int startX, int startY, int startZ, int cellW, int cellH) {
        double[] out = new double[cellW * cellW * cellH];
        fillCellOptimized(scenario, startX, startY, startZ, cellW, cellH, out);
        return out;
    }

    static long benchmarkLegacyScalar(Scenario scenario, int warmup, int iterations) {
        int[][] points = samplePoints(64);
        warmupScalar(scenario, warmup, points, true);
        long started = System.nanoTime();
        runScalar(scenario, iterations, points, true);
        return System.nanoTime() - started;
    }

    static long benchmarkOptimizedScalar(Scenario scenario, int warmup, int iterations) {
        int[][] points = samplePoints(64);
        warmupScalar(scenario, warmup, points, false);
        long started = System.nanoTime();
        runScalar(scenario, iterations, points, false);
        return System.nanoTime() - started;
    }

    static long benchmarkLegacyCell(Scenario scenario, int warmup, int iterations) {
        int[][] starts = sampleCellStarts(32);
        warmupCell(scenario, warmup, starts, true);
        long started = System.nanoTime();
        runCell(scenario, iterations, starts, true);
        return System.nanoTime() - started;
    }

    static long benchmarkOptimizedCell(Scenario scenario, int warmup, int iterations) {
        int[][] starts = sampleCellStarts(32);
        warmupCell(scenario, warmup, starts, false);
        long started = System.nanoTime();
        runCell(scenario, iterations, starts, false);
        return System.nanoTime() - started;
    }

    static void printMetric(String label, long legacyNanos, long optimizedNanos, int iterations) {
        double legacyNsOp = (double) legacyNanos / iterations;
        double optimizedNsOp = (double) optimizedNanos / iterations;
        double speedup = legacyNsOp / Math.max(1.0D, optimizedNsOp);
        System.out.printf("%s legacy=%.1f ns/op optimized=%.1f ns/op speedup=%.2fx%n",
                label, legacyNsOp, optimizedNsOp, speedup);
    }

    private static void fillCellLegacy(Scenario scenario, int startX, int startY, int startZ, int cellW, int cellH, double[] out) {
        int idx = 0;
        for (int inCellX = 0; inCellX < cellW; inCellX++) {
            int blockX = startX + inCellX;
            for (int inCellZ = 0; inCellZ < cellW; inCellZ++) {
                int blockZ = startZ + inCellZ;
                for (int inCellY = cellH - 1; inCellY >= 0; inCellY--) {
                    out[idx++] = legacyScalarAt(scenario, blockX, startY + inCellY, blockZ);
                }
            }
        }
    }

    private static void fillCellOptimized(Scenario scenario, int startX, int startY, int startZ, int cellW, int cellH, double[] out) {
        int[] pieceDx = new int[scenario.activePieces.length];
        int[] pieceDz = new int[scenario.activePieces.length];
        int[] junctionDx = new int[scenario.activeJunctions.length];
        int[] junctionDz = new int[scenario.activeJunctions.length];
        int idx = 0;
        for (int inCellX = 0; inCellX < cellW; inCellX++) {
            int blockX = startX + inCellX;
            for (int inCellZ = 0; inCellZ < cellW; inCellZ++) {
                int blockZ = startZ + inCellZ;
                GABeardifierColumnMath.fillPieceDistances(
                        blockX,
                        blockZ,
                        scenario.activePieces,
                        scenario.activePieces.length,
                        scenario.minX,
                        scenario.maxX,
                        scenario.minZ,
                        scenario.maxZ,
                        pieceDx,
                        pieceDz
                );
                GABeardifierColumnMath.fillJunctionOffsets(
                        blockX,
                        blockZ,
                        scenario.activeJunctions,
                        scenario.activeJunctions.length,
                        scenario.junctionX,
                        scenario.junctionZ,
                        junctionDx,
                        junctionDz
                );

                for (int inCellY = cellH - 1; inCellY >= 0; inCellY--) {
                    out[idx++] = optimizedColumnAt(scenario, startY + inCellY, pieceDx, pieceDz, junctionDx, junctionDz);
                }
            }
        }
    }

    private static double optimizedColumnAt(
            Scenario scenario,
            int blockY,
            int[] pieceDx,
            int[] pieceDz,
            int[] junctionDx,
            int[] junctionDz
    ) {
        double sum = 0.0D;
        int buryEnd = scenario.buryCount;
        int thinEnd = buryEnd + scenario.thinCount;
        int boxEnd = thinEnd + scenario.boxCount;
        int encapsulateEnd = boxEnd + scenario.encapsulateCount;

        for (int activeIndex = 0; activeIndex < buryEnd; activeIndex++) {
            int dx = pieceDx[activeIndex];
            int dz = pieceDz[activeIndex];
            if (dx > BURY_RADIUS || dz > BURY_RADIUS) {
                continue;
            }
            int verticalOffset = blockY - scenario.groundY[activeIndex];
            if (verticalOffset < -11 || verticalOffset > 11) {
                continue;
            }
            sum += GABeardifierContributionMath.bury(dx, verticalOffset, dz);
        }
        for (int activeIndex = buryEnd; activeIndex < thinEnd; activeIndex++) {
            int dx = pieceDx[activeIndex];
            int dz = pieceDz[activeIndex];
            if (dx > BEARD_RADIUS || dz > BEARD_RADIUS) {
                continue;
            }
            int verticalOffset = blockY - scenario.groundY[activeIndex];
            if (!inKernelRange(verticalOffset)) {
                continue;
            }
            sum += beardSameY(dx, verticalOffset, dz) * 0.8D;
        }
        for (int activeIndex = thinEnd; activeIndex < boxEnd; activeIndex++) {
            int dx = pieceDx[activeIndex];
            int dz = pieceDz[activeIndex];
            int pieceIndex = activeIndex;
            if (dx > BEARD_RADIUS || dz > BEARD_RADIUS
                    || blockY < scenario.influenceMinY[pieceIndex] || blockY > scenario.influenceMaxY[pieceIndex]) {
                continue;
            }
            int verticalOffset = blockY - scenario.groundY[pieceIndex];
            int yDistance = GABeardifierContributionMath.boxYDistance(blockY, scenario.groundY[pieceIndex], scenario.maxY[pieceIndex]);
            sum += beardUnchecked(dx, yDistance, dz, verticalOffset) * 0.8D;
        }
        for (int activeIndex = boxEnd; activeIndex < encapsulateEnd; activeIndex++) {
            int dx = pieceDx[activeIndex];
            int dz = pieceDz[activeIndex];
            int pieceIndex = activeIndex;
            if (dx > BEARD_RADIUS || dz > BEARD_RADIUS
                    || blockY < scenario.influenceMinY[pieceIndex] || blockY > scenario.influenceMaxY[pieceIndex]) {
                continue;
            }
            int yDistance = GABeardifierContributionMath.encapsulateYDistance(blockY, scenario.minY[pieceIndex], scenario.maxY[pieceIndex]);
            sum += GABeardifierContributionMath.buryHalfScaled(dx, yDistance, dz) * 0.8D;
        }

        for (int junctionIndex = 0; junctionIndex < scenario.activeJunctions.length; junctionIndex++) {
            int dx = junctionDx[junctionIndex];
            int dz = junctionDz[junctionIndex];
            if (!inKernelRange(dx) || !inKernelRange(dz)) {
                continue;
            }
            int dy = blockY - scenario.junctionY[junctionIndex];
            if (!inKernelRange(dy)) {
                continue;
            }
            sum += beardSameY(dx, dy, dz) * 0.4D;
        }
        return sum;
    }

    private static boolean insideInfluence(Scenario scenario, int pieceIndex, int blockX, int blockY, int blockZ) {
        return blockX >= scenario.influenceMinX[pieceIndex] && blockX <= scenario.influenceMaxX[pieceIndex]
                && blockY >= scenario.influenceMinY[pieceIndex] && blockY <= scenario.influenceMaxY[pieceIndex]
                && blockZ >= scenario.influenceMinZ[pieceIndex] && blockZ <= scenario.influenceMaxZ[pieceIndex];
    }

    private static void warmupScalar(Scenario scenario, int warmup, int[][] points, boolean legacy) {
        runScalar(scenario, warmup, points, legacy);
    }

    private static void runScalar(Scenario scenario, int iterations, int[][] points, boolean legacy) {
        long local = sink;
        for (int i = 0; i < iterations; i++) {
            int[] point = points[i % points.length];
            double value = legacy
                    ? legacyScalarAt(scenario, point[0], point[1], point[2])
                    : optimizedScalarAt(scenario, point[0], point[1], point[2]);
            local += Double.doubleToLongBits(value);
        }
        sink = local;
    }

    private static void warmupCell(Scenario scenario, int warmup, int[][] starts, boolean legacy) {
        runCell(scenario, warmup, starts, legacy);
    }

    private static void runCell(Scenario scenario, int iterations, int[][] starts, boolean legacy) {
        double[] out = new double[4 * 4 * 8];
        long local = sink;
        for (int i = 0; i < iterations; i++) {
            int[] start = starts[i % starts.length];
            if (legacy) {
                fillCellLegacy(scenario, start[0], start[1], start[2], 4, 8, out);
            } else {
                fillCellOptimized(scenario, start[0], start[1], start[2], 4, 8, out);
            }
            local += Double.doubleToLongBits(out[(i * 17) & (out.length - 1)]);
        }
        sink = local;
    }

    private static int[][] samplePoints(int count) {
        int[][] points = new int[count][3];
        for (int i = 0; i < count; i++) {
            points[i][0] = i * 7 - 40;
            points[i][1] = 24 + (i * 5 & 95);
            points[i][2] = i * 11 - 36;
        }
        return points;
    }

    private static int[][] sampleCellStarts(int count) {
        int[][] starts = new int[count][3];
        for (int i = 0; i < count; i++) {
            starts[i][0] = i * 5 - 32;
            starts[i][1] = 28 + (i * 3 & 63);
            starts[i][2] = i * 9 - 28;
        }
        return starts;
    }

    private static void initInfluenceBounds(
            int terrainKind,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            int groundY,
            int[] influenceMinX,
            int[] influenceMaxX,
            int[] influenceMinY,
            int[] influenceMaxY,
            int[] influenceMinZ,
            int[] influenceMaxZ,
            int pieceIndex
    ) {
        if (terrainKind == TERRAIN_BURY) {
            influenceMinX[pieceIndex] = minX - 5;
            influenceMaxX[pieceIndex] = maxX + 5;
            influenceMinY[pieceIndex] = groundY - 11;
            influenceMaxY[pieceIndex] = groundY + 11;
            influenceMinZ[pieceIndex] = minZ - 5;
            influenceMaxZ[pieceIndex] = maxZ + 5;
        } else if (terrainKind == TERRAIN_THIN) {
            influenceMinX[pieceIndex] = minX - 11;
            influenceMaxX[pieceIndex] = maxX + 11;
            influenceMinY[pieceIndex] = groundY - 12;
            influenceMaxY[pieceIndex] = groundY + 11;
            influenceMinZ[pieceIndex] = minZ - 11;
            influenceMaxZ[pieceIndex] = maxZ + 11;
        } else if (terrainKind == TERRAIN_BOX) {
            influenceMinX[pieceIndex] = minX - 11;
            influenceMaxX[pieceIndex] = maxX + 11;
            influenceMinY[pieceIndex] = groundY - 11;
            influenceMaxY[pieceIndex] = maxY + 11;
            influenceMinZ[pieceIndex] = minZ - 11;
            influenceMaxZ[pieceIndex] = maxZ + 11;
        } else {
            influenceMinX[pieceIndex] = minX - 11;
            influenceMaxX[pieceIndex] = maxX + 11;
            influenceMinY[pieceIndex] = minY - 11;
            influenceMaxY[pieceIndex] = maxY + 11;
            influenceMinZ[pieceIndex] = minZ - 11;
            influenceMaxZ[pieceIndex] = maxZ + 11;
        }
    }

    private static boolean inKernelRange(int value) {
        return value >= -KERNEL_RADIUS && value < KERNEL_RADIUS;
    }

    private static double beardUnchecked(int dx, int kernelY, int dz, int verticalOffset) {
        double y = verticalOffset + 0.5D;
        double lengthSquared = (double) dx * dx + y * y + (double) dz * dz;
        double contribution = -y * Mth.fastInvSqrt(lengthSquared * 0.5D) * 0.5D;
        return contribution * (double) BEARD_KERNEL[index(dx, kernelY, dz)];
    }

    private static double beardSameY(int dx, int dy, int dz) {
        return BEARD_SAME_Y[index(dx, dy, dz)];
    }

    private static int index(int dx, int dy, int dz) {
        return ((dz + KERNEL_RADIUS) * KERNEL_SIZE + (dx + KERNEL_RADIUS)) * KERNEL_SIZE + (dy + KERNEL_RADIUS);
    }

    private static float[] createKernel() {
        float[] kernel = new float[KERNEL_SIZE * KERNEL_SIZE * KERNEL_SIZE];
        for (int dz = -KERNEL_RADIUS; dz < KERNEL_RADIUS; dz++) {
            for (int dx = -KERNEL_RADIUS; dx < KERNEL_RADIUS; dx++) {
                for (int dy = -KERNEL_RADIUS; dy < KERNEL_RADIUS; dy++) {
                    double radius = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                    kernel[index(dx, dy, dz)] = (float) (1.0D / (1.0D + radius * 0.125D));
                }
            }
        }
        return kernel;
    }

    private static float[] createSameYTable() {
        float[] table = new float[KERNEL_SIZE * KERNEL_SIZE * KERNEL_SIZE];
        for (int dz = -KERNEL_RADIUS; dz < KERNEL_RADIUS; dz++) {
            for (int dx = -KERNEL_RADIUS; dx < KERNEL_RADIUS; dx++) {
                for (int dy = -KERNEL_RADIUS; dy < KERNEL_RADIUS; dy++) {
                    double y = dy + 0.5D;
                    double lengthSquared = (double) dx * dx + y * y + (double) dz * dz;
                    double contribution = -y * Mth.fastInvSqrt(lengthSquared * 0.5D) * 0.5D;
                    table[index(dx, dy, dz)] = (float) (contribution * (double) BEARD_KERNEL[index(dx, dy, dz)]);
                }
            }
        }
        return table;
    }

    record Scenario(
            int[] minX,
            int[] maxX,
            int[] minY,
            int[] maxY,
            int[] minZ,
            int[] maxZ,
            int[] groundY,
            int[] influenceMinX,
            int[] influenceMaxX,
            int[] influenceMinY,
            int[] influenceMaxY,
            int[] influenceMinZ,
            int[] influenceMaxZ,
            byte[] terrain,
            int[] junctionX,
            int[] junctionY,
            int[] junctionZ,
            int[] activePieces,
            int[] activeJunctions,
            int buryCount,
            int thinCount,
            int boxCount,
            int encapsulateCount
    ) {
    }
}
