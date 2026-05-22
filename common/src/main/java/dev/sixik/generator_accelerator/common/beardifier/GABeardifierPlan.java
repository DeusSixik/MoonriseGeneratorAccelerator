package dev.sixik.generator_accelerator.common.beardifier;

/**
 * Game-detached primitive mirror of a Beardifier contributor set.
 */
public final class GABeardifierPlan {
    private static final int BIN_SHIFT = 4;
    private static final int BIN_THRESHOLD = 16;
    private static final int MAX_BIN_COUNT = 8192;
    private static final int MAX_BIN_REFS = 131_072;

    final int[] pieceMinX;
    final int[] pieceMaxX;
    final int[] pieceMinY;
    final int[] pieceMaxY;
    final int[] pieceMinZ;
    final int[] pieceMaxZ;
    final int[] pieceGroundY;
    final int[] pieceInfluenceMinX;
    final int[] pieceInfluenceMaxX;
    final int[] pieceInfluenceMinY;
    final int[] pieceInfluenceMaxY;
    final int[] pieceInfluenceMinZ;
    final int[] pieceInfluenceMaxZ;
    final byte[] pieceTerrain;
    final int[] junctionX;
    final int[] junctionY;
    final int[] junctionZ;
    final boolean hasInfluence;
    final int influenceMinX;
    final int influenceMaxX;
    final int influenceMinY;
    final int influenceMaxY;
    final int influenceMinZ;
    final int influenceMaxZ;
    final int contributorCount;
    private final int binMinX;
    private final int binMinY;
    private final int binMinZ;
    private final int binSizeX;
    private final int binSizeY;
    private final int binSizeZ;
    private final int[] binOffsets;
    private final int[] binContributors;

    private GABeardifierPlan(
            int[] pieceMinX,
            int[] pieceMaxX,
            int[] pieceMinY,
            int[] pieceMaxY,
            int[] pieceMinZ,
            int[] pieceMaxZ,
            int[] pieceGroundY,
            int[] pieceInfluenceMinX,
            int[] pieceInfluenceMaxX,
            int[] pieceInfluenceMinY,
            int[] pieceInfluenceMaxY,
            int[] pieceInfluenceMinZ,
            int[] pieceInfluenceMaxZ,
            byte[] pieceTerrain,
            int[] junctionX,
            int[] junctionY,
            int[] junctionZ,
            Bounds bounds,
            SpatialIndex spatialIndex
    ) {
        this.pieceMinX = pieceMinX;
        this.pieceMaxX = pieceMaxX;
        this.pieceMinY = pieceMinY;
        this.pieceMaxY = pieceMaxY;
        this.pieceMinZ = pieceMinZ;
        this.pieceMaxZ = pieceMaxZ;
        this.pieceGroundY = pieceGroundY;
        this.pieceInfluenceMinX = pieceInfluenceMinX;
        this.pieceInfluenceMaxX = pieceInfluenceMaxX;
        this.pieceInfluenceMinY = pieceInfluenceMinY;
        this.pieceInfluenceMaxY = pieceInfluenceMaxY;
        this.pieceInfluenceMinZ = pieceInfluenceMinZ;
        this.pieceInfluenceMaxZ = pieceInfluenceMaxZ;
        this.pieceTerrain = pieceTerrain;
        this.junctionX = junctionX;
        this.junctionY = junctionY;
        this.junctionZ = junctionZ;
        this.hasInfluence = bounds.hasInfluence;
        this.influenceMinX = bounds.minX;
        this.influenceMaxX = bounds.maxX;
        this.influenceMinY = bounds.minY;
        this.influenceMaxY = bounds.maxY;
        this.influenceMinZ = bounds.minZ;
        this.influenceMaxZ = bounds.maxZ;
        this.contributorCount = pieceTerrain.length + junctionX.length;
        this.binMinX = spatialIndex.binMinX;
        this.binMinY = spatialIndex.binMinY;
        this.binMinZ = spatialIndex.binMinZ;
        this.binSizeX = spatialIndex.binSizeX;
        this.binSizeY = spatialIndex.binSizeY;
        this.binSizeZ = spatialIndex.binSizeZ;
        this.binOffsets = spatialIndex.offsets;
        this.binContributors = spatialIndex.contributors;
    }

    public static GABeardifierPlan create(
            int[] pieceMinX,
            int[] pieceMaxX,
            int[] pieceMinY,
            int[] pieceMaxY,
            int[] pieceMinZ,
            int[] pieceMaxZ,
            int[] pieceGroundY,
            byte[] pieceTerrain,
            int[] junctionX,
            int[] junctionY,
            int[] junctionZ
    ) {
        int pieceCount = pieceTerrain.length;
        int[] influenceMinX = new int[pieceCount];
        int[] influenceMaxX = new int[pieceCount];
        int[] influenceMinY = new int[pieceCount];
        int[] influenceMaxY = new int[pieceCount];
        int[] influenceMinZ = new int[pieceCount];
        int[] influenceMaxZ = new int[pieceCount];
        Bounds bounds = new Bounds();
        for (int i = 0; i < pieceCount; i++) {
            switch (pieceTerrain[i]) {
                case GABeardifierKernel.KIND_BURY -> setPieceInfluence(
                        i,
                        pieceMinX[i] - 5,
                        pieceMaxX[i] + 5,
                        pieceGroundY[i] - 11,
                        pieceGroundY[i] + 11,
                        pieceMinZ[i] - 5,
                        pieceMaxZ[i] + 5,
                        influenceMinX,
                        influenceMaxX,
                        influenceMinY,
                        influenceMaxY,
                        influenceMinZ,
                        influenceMaxZ,
                        bounds
                );
                case GABeardifierKernel.KIND_BEARD_THIN -> setPieceInfluence(
                        i,
                        pieceMinX[i] - 11,
                        pieceMaxX[i] + 11,
                        pieceGroundY[i] - 12,
                        pieceGroundY[i] + 11,
                        pieceMinZ[i] - 11,
                        pieceMaxZ[i] + 11,
                        influenceMinX,
                        influenceMaxX,
                        influenceMinY,
                        influenceMaxY,
                        influenceMinZ,
                        influenceMaxZ,
                        bounds
                );
                case GABeardifierKernel.KIND_BEARD_BOX -> setPieceInfluence(
                        i,
                        pieceMinX[i] - 11,
                        pieceMaxX[i] + 11,
                        pieceGroundY[i] - 11,
                        pieceMaxY[i] + 11,
                        pieceMinZ[i] - 11,
                        pieceMaxZ[i] + 11,
                        influenceMinX,
                        influenceMaxX,
                        influenceMinY,
                        influenceMaxY,
                        influenceMinZ,
                        influenceMaxZ,
                        bounds
                );
                case GABeardifierKernel.KIND_ENCAPSULATE -> setPieceInfluence(
                        i,
                        pieceMinX[i] - 11,
                        pieceMaxX[i] + 11,
                        pieceMinY[i] - 11,
                        pieceMaxY[i] + 11,
                        pieceMinZ[i] - 11,
                        pieceMaxZ[i] + 11,
                        influenceMinX,
                        influenceMaxX,
                        influenceMinY,
                        influenceMaxY,
                        influenceMinZ,
                        influenceMaxZ,
                        bounds
                );
                default -> {
                    influenceMinX[i] = 1;
                    influenceMaxX[i] = 0;
                    influenceMinY[i] = 1;
                    influenceMaxY[i] = 0;
                    influenceMinZ[i] = 1;
                    influenceMaxZ[i] = 0;
                }
            }
        }
        for (int i = 0; i < junctionX.length; i++) {
            bounds.merge(
                    junctionX[i] - 12,
                    junctionX[i] + 11,
                    junctionY[i] - 12,
                    junctionY[i] + 11,
                    junctionZ[i] - 12,
                    junctionZ[i] + 11
            );
        }
        SpatialIndex spatialIndex = buildSpatialIndex(
                influenceMinX,
                influenceMaxX,
                influenceMinY,
                influenceMaxY,
                influenceMinZ,
                influenceMaxZ,
                pieceTerrain,
                junctionX,
                junctionY,
                junctionZ,
                bounds
        );
        return new GABeardifierPlan(
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
                pieceTerrain,
                junctionX,
                junctionY,
                junctionZ,
                bounds,
                spatialIndex
        );
    }

    public boolean outside(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        return !this.hasInfluence
                || minX > this.influenceMaxX
                || maxX < this.influenceMinX
                || minY > this.influenceMaxY
                || maxY < this.influenceMinY
                || minZ > this.influenceMaxZ
                || maxZ < this.influenceMinZ;
    }

    public boolean outsidePoint(int x, int y, int z) {
        return !this.hasInfluence
                || x < this.influenceMinX
                || x > this.influenceMaxX
                || y < this.influenceMinY
                || y > this.influenceMaxY
                || z < this.influenceMinZ
                || z > this.influenceMaxZ;
    }

    public int cellValueCount(int cellWidth, int cellHeight) {
        return cellWidth * cellWidth * cellHeight;
    }

    boolean hasSpatialIndex() {
        return this.binOffsets != null;
    }

    public void collectActive(GABeardifierCellScratch scratch, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        scratch.beginCollect();
        if (this.binOffsets == null) {
            collectActiveScan(scratch, minX, maxX, minY, maxY, minZ, maxZ);
            return;
        }
        int bx0 = Math.max(toBin(minX) - this.binMinX, 0);
        int bx1 = Math.min(toBin(maxX) - this.binMinX, this.binSizeX - 1);
        int by0 = Math.max(toBin(minY) - this.binMinY, 0);
        int by1 = Math.min(toBin(maxY) - this.binMinY, this.binSizeY - 1);
        int bz0 = Math.max(toBin(minZ) - this.binMinZ, 0);
        int bz1 = Math.min(toBin(maxZ) - this.binMinZ, this.binSizeZ - 1);
        if (bx0 > bx1 || by0 > by1 || bz0 > bz1) {
            return;
        }
        scratch.nextCollectStamp(this.contributorCount);
        for (int by = by0; by <= by1; by++) {
            int yBase = by * this.binSizeZ * this.binSizeX;
            for (int bz = bz0; bz <= bz1; bz++) {
                int zBase = yBase + bz * this.binSizeX;
                for (int bx = bx0; bx <= bx1; bx++) {
                    int bin = zBase + bx;
                    int start = this.binOffsets[bin];
                    int end = this.binOffsets[bin + 1];
                    for (int i = start; i < end; i++) {
                        int encoded = this.binContributors[i];
                        if (encoded >= 0) {
                            if (scratch.markSeen(encoded)
                                    && overlaps(
                                    minX,
                                    maxX,
                                    minY,
                                    maxY,
                                    minZ,
                                    maxZ,
                                    this.pieceInfluenceMinX[encoded],
                                    this.pieceInfluenceMaxX[encoded],
                                    this.pieceInfluenceMinY[encoded],
                                    this.pieceInfluenceMaxY[encoded],
                                    this.pieceInfluenceMinZ[encoded],
                                    this.pieceInfluenceMaxZ[encoded]
                            )) {
                                scratch.addPiece(encoded, this.pieceTerrain[encoded]);
                            }
                        } else {
                            int junctionIndex = -1 - encoded;
                            int mark = this.pieceTerrain.length + junctionIndex;
                            if (scratch.markSeen(mark)
                                    && overlaps(
                                    minX,
                                    maxX,
                                    minY,
                                    maxY,
                                    minZ,
                                    maxZ,
                                    this.junctionX[junctionIndex] - 12,
                                    this.junctionX[junctionIndex] + 11,
                                    this.junctionY[junctionIndex] - 12,
                                    this.junctionY[junctionIndex] + 11,
                                    this.junctionZ[junctionIndex] - 12,
                                    this.junctionZ[junctionIndex] + 11
                            )) {
                                scratch.addJunction(junctionIndex);
                            }
                        }
                    }
                }
            }
        }
    }

    private void collectActiveScan(
            GABeardifierCellScratch scratch,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ
    ) {
        byte[] terrain = this.pieceTerrain;
        for (int i = 0; i < terrain.length; i++) {
            if (overlaps(
                    minX,
                    maxX,
                    minY,
                    maxY,
                    minZ,
                    maxZ,
                    this.pieceInfluenceMinX[i],
                    this.pieceInfluenceMaxX[i],
                    this.pieceInfluenceMinY[i],
                    this.pieceInfluenceMaxY[i],
                    this.pieceInfluenceMinZ[i],
                    this.pieceInfluenceMaxZ[i]
            )) {
                scratch.addPiece(i, terrain[i]);
            }
        }

        int[] junctionX = this.junctionX;
        int[] junctionY = this.junctionY;
        int[] junctionZ = this.junctionZ;
        for (int i = 0; i < junctionX.length; i++) {
            if (overlaps(
                    minX,
                    maxX,
                    minY,
                    maxY,
                    minZ,
                    maxZ,
                    junctionX[i] - 12,
                    junctionX[i] + 11,
                    junctionY[i] - 12,
                    junctionY[i] + 11,
                    junctionZ[i] - 12,
                    junctionZ[i] + 11
            )) {
                scratch.addJunction(i);
            }
        }
    }

    private static void setPieceInfluence(
            int index,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            int[] influenceMinX,
            int[] influenceMaxX,
            int[] influenceMinY,
            int[] influenceMaxY,
            int[] influenceMinZ,
            int[] influenceMaxZ,
            Bounds bounds
    ) {
        influenceMinX[index] = minX;
        influenceMaxX[index] = maxX;
        influenceMinY[index] = minY;
        influenceMaxY[index] = maxY;
        influenceMinZ[index] = minZ;
        influenceMaxZ[index] = maxZ;
        bounds.merge(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static SpatialIndex buildSpatialIndex(
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
            Bounds bounds
    ) {
        int activeContributors = 0;
        for (byte kind : terrain) {
            if (kind != GABeardifierKernel.KIND_NONE) {
                activeContributors++;
            }
        }
        activeContributors += junctionX.length;
        if (!bounds.hasInfluence || activeContributors <= BIN_THRESHOLD) {
            return SpatialIndex.EMPTY;
        }

        int binMinX = toBin(bounds.minX);
        int binMaxX = toBin(bounds.maxX);
        int binMinY = toBin(bounds.minY);
        int binMaxY = toBin(bounds.maxY);
        int binMinZ = toBin(bounds.minZ);
        int binMaxZ = toBin(bounds.maxZ);
        int binSizeX = binMaxX - binMinX + 1;
        int binSizeY = binMaxY - binMinY + 1;
        int binSizeZ = binMaxZ - binMinZ + 1;
        long binCountLong = (long) binSizeX * (long) binSizeY * (long) binSizeZ;
        if (binCountLong <= 0L || binCountLong > MAX_BIN_COUNT) {
            return SpatialIndex.EMPTY;
        }
        int binCount = (int) binCountLong;
        int[] counts = new int[binCount];
        int totalRefs = countPieceRefs(
                influenceMinX,
                influenceMaxX,
                influenceMinY,
                influenceMaxY,
                influenceMinZ,
                influenceMaxZ,
                terrain,
                binMinX,
                binMinY,
                binMinZ,
                binSizeX,
                binSizeZ,
                counts,
                false,
                null
        );
        totalRefs += countJunctionRefs(junctionX, junctionY, junctionZ, binMinX, binMinY, binMinZ, binSizeX, binSizeZ, counts, false, null);
        if (totalRefs <= 0 || totalRefs > MAX_BIN_REFS) {
            return SpatialIndex.EMPTY;
        }
        int[] offsets = new int[binCount + 1];
        for (int i = 0; i < binCount; i++) {
            offsets[i + 1] = offsets[i] + counts[i];
            counts[i] = offsets[i];
        }
        int[] contributors = new int[totalRefs];
        countPieceRefs(
                influenceMinX,
                influenceMaxX,
                influenceMinY,
                influenceMaxY,
                influenceMinZ,
                influenceMaxZ,
                terrain,
                binMinX,
                binMinY,
                binMinZ,
                binSizeX,
                binSizeZ,
                counts,
                true,
                contributors
        );
        countJunctionRefs(junctionX, junctionY, junctionZ, binMinX, binMinY, binMinZ, binSizeX, binSizeZ, counts, true, contributors);
        return new SpatialIndex(binMinX, binMinY, binMinZ, binSizeX, binSizeY, binSizeZ, offsets, contributors);
    }

    private static int countPieceRefs(
            int[] influenceMinX,
            int[] influenceMaxX,
            int[] influenceMinY,
            int[] influenceMaxY,
            int[] influenceMinZ,
            int[] influenceMaxZ,
            byte[] terrain,
            int binMinX,
            int binMinY,
            int binMinZ,
            int binSizeX,
            int binSizeZ,
            int[] counts,
            boolean write,
            int[] contributors
    ) {
        int refs = 0;
        for (int i = 0; i < terrain.length; i++) {
            if (terrain[i] == GABeardifierKernel.KIND_NONE) {
                continue;
            }
            refs += addRefs(
                    toBin(influenceMinX[i]) - binMinX,
                    toBin(influenceMaxX[i]) - binMinX,
                    toBin(influenceMinY[i]) - binMinY,
                    toBin(influenceMaxY[i]) - binMinY,
                    toBin(influenceMinZ[i]) - binMinZ,
                    toBin(influenceMaxZ[i]) - binMinZ,
                    binSizeX,
                    binSizeZ,
                    i,
                    counts,
                    write,
                    contributors
            );
        }
        return refs;
    }

    private static int countJunctionRefs(
            int[] junctionX,
            int[] junctionY,
            int[] junctionZ,
            int binMinX,
            int binMinY,
            int binMinZ,
            int binSizeX,
            int binSizeZ,
            int[] counts,
            boolean write,
            int[] contributors
    ) {
        int refs = 0;
        for (int i = 0; i < junctionX.length; i++) {
            refs += addRefs(
                    toBin(junctionX[i] - 12) - binMinX,
                    toBin(junctionX[i] + 11) - binMinX,
                    toBin(junctionY[i] - 12) - binMinY,
                    toBin(junctionY[i] + 11) - binMinY,
                    toBin(junctionZ[i] - 12) - binMinZ,
                    toBin(junctionZ[i] + 11) - binMinZ,
                    binSizeX,
                    binSizeZ,
                    -1 - i,
                    counts,
                    write,
                    contributors
            );
        }
        return refs;
    }

    private static int addRefs(
            int bx0,
            int bx1,
            int by0,
            int by1,
            int bz0,
            int bz1,
            int binSizeX,
            int binSizeZ,
            int encoded,
            int[] counts,
            boolean write,
            int[] contributors
    ) {
        int refs = 0;
        for (int by = by0; by <= by1; by++) {
            int yBase = by * binSizeZ * binSizeX;
            for (int bz = bz0; bz <= bz1; bz++) {
                int zBase = yBase + bz * binSizeX;
                for (int bx = bx0; bx <= bx1; bx++) {
                    int bin = zBase + bx;
                    if (write) {
                        contributors[counts[bin]++] = encoded;
                    } else {
                        counts[bin]++;
                    }
                    refs++;
                }
            }
        }
        return refs;
    }

    private static boolean overlaps(
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            int influenceMinX,
            int influenceMaxX,
            int influenceMinY,
            int influenceMaxY,
            int influenceMinZ,
            int influenceMaxZ
    ) {
        return maxX >= influenceMinX
                && minX <= influenceMaxX
                && maxY >= influenceMinY
                && minY <= influenceMaxY
                && maxZ >= influenceMinZ
                && minZ <= influenceMaxZ;
    }

    private static int toBin(int block) {
        return block >> BIN_SHIFT;
    }

    private static final class SpatialIndex {
        static final SpatialIndex EMPTY = new SpatialIndex(0, 0, 0, 0, 0, 0, null, null);

        final int binMinX;
        final int binMinY;
        final int binMinZ;
        final int binSizeX;
        final int binSizeY;
        final int binSizeZ;
        final int[] offsets;
        final int[] contributors;

        SpatialIndex(
                int binMinX,
                int binMinY,
                int binMinZ,
                int binSizeX,
                int binSizeY,
                int binSizeZ,
                int[] offsets,
                int[] contributors
        ) {
            this.binMinX = binMinX;
            this.binMinY = binMinY;
            this.binMinZ = binMinZ;
            this.binSizeX = binSizeX;
            this.binSizeY = binSizeY;
            this.binSizeZ = binSizeZ;
            this.offsets = offsets;
            this.contributors = contributors;
        }
    }

    private static final class Bounds {
        boolean hasInfluence;
        int minX;
        int maxX;
        int minY;
        int maxY;
        int minZ;
        int maxZ;

        void merge(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            if (!this.hasInfluence) {
                this.minX = minX;
                this.maxX = maxX;
                this.minY = minY;
                this.maxY = maxY;
                this.minZ = minZ;
                this.maxZ = maxZ;
                this.hasInfluence = true;
                return;
            }
            if (minX < this.minX) this.minX = minX;
            if (maxX > this.maxX) this.maxX = maxX;
            if (minY < this.minY) this.minY = minY;
            if (maxY > this.maxY) this.maxY = maxY;
            if (minZ < this.minZ) this.minZ = minZ;
            if (maxZ > this.maxZ) this.maxZ = maxZ;
        }
    }
}
