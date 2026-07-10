package dev.sixik.generator_accelerator.common.surface_compiler.runtime;

import dev.sixik.generator_accelerator.common.surface_compiler.callout.SurfaceCalloutScratch;
import dev.sixik.generator_accelerator.common.surface_compiler.validate.StateTraceValidator;

public final class SurfaceWorkerState {
    private static final ThreadLocal<SurfaceWorkerState> LOCAL = ThreadLocal.withInitial(SurfaceWorkerState::new);

    private final SurfaceCalloutScratch calloutScratch = new SurfaceCalloutScratch();
    private final StateTraceValidator.Trace trace = new StateTraceValidator.Trace();
    private long epoch;

    private SurfaceWorkerState() {
    }

    public static SurfaceWorkerState acquire() {
        SurfaceWorkerState state = LOCAL.get();
        state.reset();
        return state;
    }

    public SurfaceCalloutScratch calloutScratch() {
        return this.calloutScratch;
    }

    public StateTraceValidator.Trace trace() {
        return this.trace;
    }

    public long epoch() {
        return this.epoch;
    }

    public void reset() {
        this.calloutScratch.reset();
        this.trace.clear();
        this.epoch++;
    }
}
