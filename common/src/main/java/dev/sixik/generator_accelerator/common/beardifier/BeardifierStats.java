package dev.sixik.generator_accelerator.common.beardifier;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicInteger;

public final class BeardifierStats {

    private static final int SAMPLE_MASK = 255;

    private static final LongAdder COMPUTE_CELL_CALLS = new LongAdder();
    private static final LongAdder FILL_CELL_CALLS = new LongAdder();
    private static final LongAdder ACCUMULATE_CELL_CALLS = new LongAdder();
    private static final LongAdder CELL_ACTIVE_PIECES = new LongAdder();
    private static final LongAdder CELL_ACTIVE_JUNCTIONS = new LongAdder();
    private static final LongAdder OUTSIDE_INFLUENCE_RETURNS = new LongAdder();
    private static final LongAdder EMPTY_ACTIVE_RETURNS = new LongAdder();
    private static final LongAdder COLUMNS_PROCESSED = new LongAdder();
    private static final LongAdder COLUMN_CACHE_HITS = new LongAdder();
    private static final LongAdder DIRECT_COMPUTE_FALLBACKS = new LongAdder();
    private static final LongAdder EMPTY_COLUMNS_AFTER_FILTER = new LongAdder();
    private static final LongAdder COLUMN_PIECES_BEFORE_FILTER = new LongAdder();
    private static final LongAdder COLUMN_PIECES_AFTER_FILTER = new LongAdder();
    private static final LongAdder COLUMN_JUNCTIONS_BEFORE_FILTER = new LongAdder();
    private static final LongAdder COLUMN_JUNCTIONS_AFTER_FILTER = new LongAdder();
    private static final LongAdder FILTERED_BURY_PIECES = new LongAdder();
    private static final LongAdder FILTERED_THIN_PIECES = new LongAdder();
    private static final LongAdder FILTERED_BOX_PIECES = new LongAdder();
    private static final LongAdder FILTERED_ENCAPSULATE_PIECES = new LongAdder();
    private static final LongAdder COMPUTE_CELL_TIMED_CALLS = new LongAdder();
    private static final LongAdder COMPUTE_CELL_TOTAL_NANOS = new LongAdder();
    private static final LongAdder REBUILD_COLUMN_TIMED_CALLS = new LongAdder();
    private static final LongAdder REBUILD_COLUMN_TOTAL_NANOS = new LongAdder();
    private static final LongAdder DIRECT_COMPUTE_TIMED_CALLS = new LongAdder();
    private static final LongAdder DIRECT_COMPUTE_TOTAL_NANOS = new LongAdder();

    private static final AtomicInteger COMPUTE_CELL_SAMPLE_COUNTER = new AtomicInteger();
    private static final AtomicInteger REBUILD_COLUMN_SAMPLE_COUNTER = new AtomicInteger();
    private static final AtomicInteger DIRECT_COMPUTE_SAMPLE_COUNTER = new AtomicInteger();

    private BeardifierStats() {
    }

    public record Stats(
            long computeCellCalls,
            long fillCellCalls,
            long accumulateCellCalls,
            long cellActivePieces,
            long cellActiveJunctions,
            long outsideInfluenceReturns,
            long emptyActiveReturns,
            long columnsProcessed,
            long columnCacheHits,
            long directComputeFallbacks,
            long emptyColumnsAfterFilter,
            long columnPiecesBeforeFilter,
            long columnPiecesAfterFilter,
            long columnJunctionsBeforeFilter,
            long columnJunctionsAfterFilter,
            long filteredBuryPieces,
            long filteredThinPieces,
            long filteredBoxPieces,
            long filteredEncapsulatePieces,
            long computeCellTimedCalls,
            long computeCellTotalNanos,
            long rebuildColumnTimedCalls,
            long rebuildColumnTotalNanos,
            long directComputeTimedCalls,
            long directComputeTotalNanos
    ) {
    }

    public static Stats snapshotStats() {
        return new Stats(
                COMPUTE_CELL_CALLS.sum(),
                FILL_CELL_CALLS.sum(),
                ACCUMULATE_CELL_CALLS.sum(),
                CELL_ACTIVE_PIECES.sum(),
                CELL_ACTIVE_JUNCTIONS.sum(),
                OUTSIDE_INFLUENCE_RETURNS.sum(),
                EMPTY_ACTIVE_RETURNS.sum(),
                COLUMNS_PROCESSED.sum(),
                COLUMN_CACHE_HITS.sum(),
                DIRECT_COMPUTE_FALLBACKS.sum(),
                EMPTY_COLUMNS_AFTER_FILTER.sum(),
                COLUMN_PIECES_BEFORE_FILTER.sum(),
                COLUMN_PIECES_AFTER_FILTER.sum(),
                COLUMN_JUNCTIONS_BEFORE_FILTER.sum(),
                COLUMN_JUNCTIONS_AFTER_FILTER.sum(),
                FILTERED_BURY_PIECES.sum(),
                FILTERED_THIN_PIECES.sum(),
                FILTERED_BOX_PIECES.sum(),
                FILTERED_ENCAPSULATE_PIECES.sum(),
                COMPUTE_CELL_TIMED_CALLS.sum(),
                COMPUTE_CELL_TOTAL_NANOS.sum(),
                REBUILD_COLUMN_TIMED_CALLS.sum(),
                REBUILD_COLUMN_TOTAL_NANOS.sum(),
                DIRECT_COMPUTE_TIMED_CALLS.sum(),
                DIRECT_COMPUTE_TOTAL_NANOS.sum()
        );
    }

    public static void reset() {
        COMPUTE_CELL_CALLS.reset();
        FILL_CELL_CALLS.reset();
        ACCUMULATE_CELL_CALLS.reset();
        CELL_ACTIVE_PIECES.reset();
        CELL_ACTIVE_JUNCTIONS.reset();
        OUTSIDE_INFLUENCE_RETURNS.reset();
        EMPTY_ACTIVE_RETURNS.reset();
        COLUMNS_PROCESSED.reset();
        COLUMN_CACHE_HITS.reset();
        DIRECT_COMPUTE_FALLBACKS.reset();
        EMPTY_COLUMNS_AFTER_FILTER.reset();
        COLUMN_PIECES_BEFORE_FILTER.reset();
        COLUMN_PIECES_AFTER_FILTER.reset();
        COLUMN_JUNCTIONS_BEFORE_FILTER.reset();
        COLUMN_JUNCTIONS_AFTER_FILTER.reset();
        FILTERED_BURY_PIECES.reset();
        FILTERED_THIN_PIECES.reset();
        FILTERED_BOX_PIECES.reset();
        FILTERED_ENCAPSULATE_PIECES.reset();
        COMPUTE_CELL_TIMED_CALLS.reset();
        COMPUTE_CELL_TOTAL_NANOS.reset();
        REBUILD_COLUMN_TIMED_CALLS.reset();
        REBUILD_COLUMN_TOTAL_NANOS.reset();
        DIRECT_COMPUTE_TIMED_CALLS.reset();
        DIRECT_COMPUTE_TOTAL_NANOS.reset();
    }

    public static void recordComputeCell(int activePieceCount, int activeJunctionCount) {
        COMPUTE_CELL_CALLS.increment();
        CELL_ACTIVE_PIECES.add(Math.max(0, activePieceCount));
        CELL_ACTIVE_JUNCTIONS.add(Math.max(0, activeJunctionCount));
    }

    public static void recordFillCell(int activePieceCount, int activeJunctionCount) {
        FILL_CELL_CALLS.increment();
        CELL_ACTIVE_PIECES.add(Math.max(0, activePieceCount));
        CELL_ACTIVE_JUNCTIONS.add(Math.max(0, activeJunctionCount));
    }

    public static void recordAccumulateCell(int activePieceCount, int activeJunctionCount) {
        ACCUMULATE_CELL_CALLS.increment();
        CELL_ACTIVE_PIECES.add(Math.max(0, activePieceCount));
        CELL_ACTIVE_JUNCTIONS.add(Math.max(0, activeJunctionCount));
    }

    public static void recordColumnFilter(
            int piecesBefore,
            int piecesAfter,
            int junctionsBefore,
            int junctionsAfter,
            int buryCount,
            int thinCount,
            int boxCount,
            int encapsulateCount
    ) {
        COLUMNS_PROCESSED.increment();
        COLUMN_PIECES_BEFORE_FILTER.add(Math.max(0, piecesBefore));
        COLUMN_PIECES_AFTER_FILTER.add(Math.max(0, piecesAfter));
        COLUMN_JUNCTIONS_BEFORE_FILTER.add(Math.max(0, junctionsBefore));
        COLUMN_JUNCTIONS_AFTER_FILTER.add(Math.max(0, junctionsAfter));
        FILTERED_BURY_PIECES.add(Math.max(0, buryCount));
        FILTERED_THIN_PIECES.add(Math.max(0, thinCount));
        FILTERED_BOX_PIECES.add(Math.max(0, boxCount));
        FILTERED_ENCAPSULATE_PIECES.add(Math.max(0, encapsulateCount));
        if (piecesAfter == 0 && junctionsAfter == 0) {
            EMPTY_COLUMNS_AFTER_FILTER.increment();
        }
    }

    public static void recordColumnCacheHit() {
        COLUMN_CACHE_HITS.increment();
    }

    public static void recordDirectComputeFallback() {
        DIRECT_COMPUTE_FALLBACKS.increment();
    }

    public static void recordOutsideInfluenceReturn() {
        OUTSIDE_INFLUENCE_RETURNS.increment();
    }

    public static void recordEmptyActiveReturn() {
        EMPTY_ACTIVE_RETURNS.increment();
    }

    public static long sampleComputeCellStart() {
        return (COMPUTE_CELL_SAMPLE_COUNTER.incrementAndGet() & SAMPLE_MASK) == 0 ? System.nanoTime() : 0L;
    }

    public static long sampleRebuildColumnStart() {
        return (REBUILD_COLUMN_SAMPLE_COUNTER.incrementAndGet() & SAMPLE_MASK) == 0 ? System.nanoTime() : 0L;
    }

    public static long sampleDirectComputeStart() {
        return (DIRECT_COMPUTE_SAMPLE_COUNTER.incrementAndGet() & SAMPLE_MASK) == 0 ? System.nanoTime() : 0L;
    }

    public static void recordComputeCellTimed(long nanos) {
        COMPUTE_CELL_TIMED_CALLS.increment();
        COMPUTE_CELL_TOTAL_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordRebuildColumnTimed(long nanos) {
        REBUILD_COLUMN_TIMED_CALLS.increment();
        REBUILD_COLUMN_TOTAL_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordDirectComputeTimed(long nanos) {
        DIRECT_COMPUTE_TIMED_CALLS.increment();
        DIRECT_COMPUTE_TOTAL_NANOS.add(Math.max(0L, nanos));
    }

}
