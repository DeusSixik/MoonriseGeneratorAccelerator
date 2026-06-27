package dev.sixik.generator_accelerator.common.aquifer;

import java.util.concurrent.atomic.LongAdder;

public final class AquiferStats {

    private static final int SAMPLE_MASK = 255;

    private static final LongAdder COMPUTE_SUBSTANCE_CALLS = new LongAdder();
    private static final LongAdder POSITIVE_DENSITY_RETURNS = new LongAdder();
    private static final LongAdder GLOBAL_LAVA_RETURNS = new LongAdder();
    private static final LongAdder REFRESH_DIST_CALLS = new LongAdder();
    private static final LongAdder BARRIER_NOISE_COMPUTES = new LongAdder();
    private static final LongAdder WATER_BELOW_LAVA_RETURNS = new LongAdder();
    private static final LongAdder PRESSURE_ABORT_RETURNS = new LongAdder();
    private static final LongAdder FINAL_SOLID_RETURNS = new LongAdder();
    private static final LongAdder LAZY_THIRD_RESOLVES = new LongAdder();
    private static final LongAdder REFRESH_DIST_TIMED_CALLS = new LongAdder();
    private static final LongAdder REFRESH_DIST_TOTAL_NANOS = new LongAdder();
    private static final LongAdder LAZY_THIRD_TIMED_CALLS = new LongAdder();
    private static final LongAdder LAZY_THIRD_TOTAL_NANOS = new LongAdder();
    private static final LongAdder AQUIFER_STATUS_TIMED_CALLS = new LongAdder();
    private static final LongAdder AQUIFER_STATUS_TOTAL_NANOS = new LongAdder();

    private static final ThreadLocal<SamplerState> SAMPLER = ThreadLocal.withInitial(SamplerState::new);

    private AquiferStats() {
    }

    public record Stats(
            long computeSubstanceCalls,
            long positiveDensityReturns,
            long globalLavaReturns,
            long refreshDistCalls,
            long barrierNoiseComputes,
            long waterBelowLavaReturns,
            long pressureAbortReturns,
            long finalSolidReturns,
            long lazyThirdResolves,
            long refreshDistTimedCalls,
            long refreshDistTotalNanos,
            long lazyThirdTimedCalls,
            long lazyThirdTotalNanos,
            long aquiferStatusTimedCalls,
            long aquiferStatusTotalNanos
    ) {
    }

    public static Stats snapshotStats() {
        return new Stats(
                COMPUTE_SUBSTANCE_CALLS.sum(),
                POSITIVE_DENSITY_RETURNS.sum(),
                GLOBAL_LAVA_RETURNS.sum(),
                REFRESH_DIST_CALLS.sum(),
                BARRIER_NOISE_COMPUTES.sum(),
                WATER_BELOW_LAVA_RETURNS.sum(),
                PRESSURE_ABORT_RETURNS.sum(),
                FINAL_SOLID_RETURNS.sum(),
                LAZY_THIRD_RESOLVES.sum(),
                REFRESH_DIST_TIMED_CALLS.sum(),
                REFRESH_DIST_TOTAL_NANOS.sum(),
                LAZY_THIRD_TIMED_CALLS.sum(),
                LAZY_THIRD_TOTAL_NANOS.sum(),
                AQUIFER_STATUS_TIMED_CALLS.sum(),
                AQUIFER_STATUS_TOTAL_NANOS.sum()
        );
    }

    public static void recordComputeSubstanceCall() {
        COMPUTE_SUBSTANCE_CALLS.increment();
    }

    public static void recordPositiveDensityReturn() {
        POSITIVE_DENSITY_RETURNS.increment();
    }

    public static void recordGlobalLavaReturn() {
        GLOBAL_LAVA_RETURNS.increment();
    }

    public static void recordRefreshDistCall() {
        REFRESH_DIST_CALLS.increment();
    }

    public static void recordBarrierNoiseCompute() {
        BARRIER_NOISE_COMPUTES.increment();
    }

    public static void recordWaterBelowLavaReturn() {
        WATER_BELOW_LAVA_RETURNS.increment();
    }

    public static void recordPressureAbortReturn() {
        PRESSURE_ABORT_RETURNS.increment();
    }

    public static void recordFinalSolidReturn() {
        FINAL_SOLID_RETURNS.increment();
    }

    public static void recordLazyThirdResolve() {
        LAZY_THIRD_RESOLVES.increment();
    }

    public static long sampleRefreshDistStart() {
        SamplerState state = SAMPLER.get();
        state.refreshDistCounter = (state.refreshDistCounter + 1) & SAMPLE_MASK;
        return state.refreshDistCounter == 0 ? System.nanoTime() : 0L;
    }

    public static long sampleLazyThirdStart() {
        SamplerState state = SAMPLER.get();
        state.lazyThirdCounter = (state.lazyThirdCounter + 1) & SAMPLE_MASK;
        return state.lazyThirdCounter == 0 ? System.nanoTime() : 0L;
    }

    public static long sampleAquiferStatusStart() {
        SamplerState state = SAMPLER.get();
        state.aquiferStatusCounter = (state.aquiferStatusCounter + 1) & SAMPLE_MASK;
        return state.aquiferStatusCounter == 0 ? System.nanoTime() : 0L;
    }

    public static void recordRefreshDistTimed(long nanos) {
        REFRESH_DIST_TIMED_CALLS.increment();
        REFRESH_DIST_TOTAL_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordLazyThirdTimed(long nanos) {
        LAZY_THIRD_TIMED_CALLS.increment();
        LAZY_THIRD_TOTAL_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordAquiferStatusTimed(long nanos) {
        AQUIFER_STATUS_TIMED_CALLS.increment();
        AQUIFER_STATUS_TOTAL_NANOS.add(Math.max(0L, nanos));
    }

    private static final class SamplerState {
        private int refreshDistCounter;
        private int lazyThirdCounter;
        private int aquiferStatusCounter;
    }
}
