package dev.sixik.generator_accelerator.common.noise;

import net.minecraft.world.level.block.state.BlockState;

public interface GAFusedTerrainNoiseChunkAccess {
    int GA_DIRECT_CELL_CLASS_UNAVAILABLE = -1;
    int GA_DIRECT_CELL_CLASS_MIXED = 0;
    int GA_DIRECT_CELL_CLASS_ALL_POSITIVE = 1;
    int GA_DIRECT_CELL_CLASS_ALL_NON_POSITIVE = 2;

    int GA_FALLBACK_BLOCK_ID = Integer.MIN_VALUE;
    long GA_FALLBACK_PACKED_BLOCK_ID = 1L << 33;
    int GA_PACKED_SCHEDULE_SHIFT = 32;
    int GA_FALLBACK_REASON_SHIFT = 34;
    int GA_FALLBACK_REASON_MASK = 0xF;
    int GA_FALLBACK_REASON_GENERIC = 0;
    int GA_FALLBACK_REASON_UNAVAILABLE = 1;
    int GA_FALLBACK_REASON_ORE_VEIN_RANGE = 2;
    int GA_FALLBACK_REASON_NON_SOLID = 3;
    int GA_FALLBACK_REASON_OUT_OF_BOUNDS = 4;
    int GA_FALLBACK_REASON_MIXED_CELL = 5;

    boolean ga$fusedTerrainAvailable();

    int ga$sampleFusedTerrainBlockId(int defaultBlockId);

    default boolean ga$fusedTerrainDirectCellAvailable() {
        return false;
    }

    default boolean ga$prepareFusedTerrainDirectCell() {
        return ga$fusedTerrainDirectCellAvailable();
    }

    default long ga$sampleFusedTerrainDirectCellPackedBlockId(
            int defaultBlockId,
            int airBlockId,
            int blockY,
            int cellValueIndex
    ) {
        return ga$packFallback(GA_FALLBACK_REASON_UNAVAILABLE);
    }

    default double ga$sampleFusedTerrainDirectCellDensity(int cellValueIndex) {
        return Double.NaN;
    }

    default double[] ga$fusedTerrainDirectCellDensityValues() {
        return null;
    }

    default int ga$fusedTerrainDirectCellDensityClass() {
        return GA_DIRECT_CELL_CLASS_UNAVAILABLE;
    }

    default double ga$fusedTerrainDirectCellMinDensity() {
        return Double.NaN;
    }

    default double ga$fusedTerrainDirectCellMaxDensity() {
        return Double.NaN;
    }

    default void ga$setFusedTerrainDirectCellDensitySummary(int cellClass, double minDensity, double maxDensity) {
    }

    default boolean ga$fusedTerrainDirectCellHasOreVeinRule() {
        return false;
    }

    default boolean ga$fusedTerrainDirectCellSkipsOreVeins() {
        return false;
    }

    default boolean ga$fusedTerrainDirectCellAirForNonSolid() {
        return false;
    }

    default boolean ga$fusedTerrainDirectCellAllDefaultSolid(int minBlockY, int cellHeight) {
        return false;
    }

    default long ga$samplePositiveDensityFusedTerrainPackedBlockId(int defaultBlockId) {
        return ga$packFallback(GA_FALLBACK_REASON_UNAVAILABLE);
    }

    default long ga$sampleNegativeDensityGlobalFluidPackedBlockId(
            int airBlockId,
            int blockX,
            int blockY,
            int blockZ
    ) {
        return ga$packFallback(GA_FALLBACK_REASON_UNAVAILABLE);
    }

    default long ga$classifyNegativeDensityCellPackedBlockId(
            int airBlockId,
            int minBlockX,
            int minBlockY,
            int minBlockZ,
            int cellWidth,
            int cellHeight,
            boolean highAirEnabled,
            int highAirMinY
    ) {
        return ga$packFallback(GA_FALLBACK_REASON_UNAVAILABLE);
    }

    default int ga$sampleFusedTerrainBlockId(int defaultBlockId, int blockX, int blockY, int blockZ) {
        return ga$sampleFusedTerrainBlockId(defaultBlockId);
    }

    default long ga$sampleFusedTerrainPackedBlockId(int defaultBlockId, int blockX, int blockY, int blockZ) {
        int blockId = ga$sampleFusedTerrainBlockId(defaultBlockId, blockX, blockY, blockZ);
        return blockId == GA_FALLBACK_BLOCK_ID ? GA_FALLBACK_PACKED_BLOCK_ID : ga$packFusedTerrain(blockId, false);
    }

    BlockState ga$sampleFusedTerrainBlockState(BlockState defaultBlock);

    static long ga$packFusedTerrain(int blockId, boolean scheduleFluidUpdate) {
        return (blockId & 0xFFFF_FFFFL) | (scheduleFluidUpdate ? (1L << GA_PACKED_SCHEDULE_SHIFT) : 0L);
    }

    static int ga$packedBlockId(long packed) {
        return (int) packed;
    }

    static boolean ga$packedScheduleFluidUpdate(long packed) {
        return (packed >>> GA_PACKED_SCHEDULE_SHIFT & 1L) != 0L;
    }

    static boolean ga$packedFallback(long packed) {
        return (packed & GA_FALLBACK_PACKED_BLOCK_ID) != 0L;
    }

    static long ga$packFallback(int reason) {
        return GA_FALLBACK_PACKED_BLOCK_ID
                | (((long) reason & GA_FALLBACK_REASON_MASK) << GA_FALLBACK_REASON_SHIFT);
    }

    static int ga$packedFallbackReason(long packed) {
        return ga$packedFallback(packed)
                ? (int) ((packed >>> GA_FALLBACK_REASON_SHIFT) & GA_FALLBACK_REASON_MASK)
                : GA_FALLBACK_REASON_GENERIC;
    }
}
