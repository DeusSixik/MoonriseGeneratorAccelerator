package dev.sixik.generator_accelerator.common.surface_compiler.telemetry;

import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceTier;
import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterSafetyClass;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class SurfaceTelemetry {
    private final Map<String, AtomicLong> counters = new LinkedHashMap<>();
    private final Map<String, AdapterCounter> adapterCounters = new LinkedHashMap<>();
    private final Map<String, AtomicLong> opaqueNodeCounters = new LinkedHashMap<>();

    public synchronized void reset() {
        this.counters.clear();
        this.adapterCounters.clear();
        this.opaqueNodeCounters.clear();
    }

    public synchronized void tier(String fingerprint, SurfaceTier tier) {
        increment("tier." + tier + "." + fingerprint);
    }

    public synchronized void fallback(String fingerprint, FallbackReason reason) {
        increment("fallback." + reason + "." + fingerprint);
    }

    public synchronized void opaqueNode(String sourceClassName, String reason, boolean vanillaOwned, boolean condition) {
        String kind = condition ? "condition" : "rule";
        String owner = vanillaOwned ? "vanilla" : "external";
        String key = kind + "|" + owner + "|" + sourceClassName + "|" + reason;
        this.opaqueNodeCounters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    public synchronized void execution(String fingerprint, SurfaceTier tier, boolean success) {
        increment("execute." + tier + "." + (success ? "success" : "failure") + "." + fingerprint);
    }

    public synchronized void adapter(String adapterId, AdapterSafetyClass safetyClass, boolean success) {
        adapter(adapterId, safetyClass, success, false);
    }

    public synchronized void adapter(String adapterId, AdapterSafetyClass safetyClass, boolean success, boolean vectorEligible) {
        AdapterCounter counter = this.adapterCounters.computeIfAbsent(adapterId, ignored -> new AdapterCounter(safetyClass));
        counter.vectorEligible |= vectorEligible;
        counter.calls.incrementAndGet();
        if (!success) {
            counter.failures.incrementAndGet();
        }
    }

    public synchronized void vectorAdapterExecution(String adapterId, AdapterSafetyClass safetyClass, boolean success) {
        AdapterCounter counter = this.adapterCounters.computeIfAbsent(adapterId, ignored -> new AdapterCounter(safetyClass));
        counter.vectorEligible = true;
        counter.vectorCalls.incrementAndGet();
        if (!success) {
            counter.vectorFailures.incrementAndGet();
        }
    }

    public synchronized Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        this.counters.forEach((key, value) -> out.put(key, value.get()));
        return out;
    }

    public synchronized Map<String, Long> opaqueNodes() {
        Map<String, Long> out = new LinkedHashMap<>();
        this.opaqueNodeCounters.forEach((key, value) -> out.put(key, value.get()));
        return out;
    }

    public synchronized Map<String, SurfaceAdapterStats> adapterStats() {
        Map<String, SurfaceAdapterStats> out = new LinkedHashMap<>();
        this.adapterCounters.forEach((adapterId, counter) -> out.put(adapterId,
                new SurfaceAdapterStats(adapterId, counter.safetyClass, counter.calls.get(), counter.failures.get(),
                        counter.vectorEligible, counter.vectorCalls.get(), counter.vectorFailures.get())));
        return out;
    }

    private void increment(String key) {
        this.counters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static final class AdapterCounter {
        private final AdapterSafetyClass safetyClass;
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong vectorCalls = new AtomicLong();
        private final AtomicLong vectorFailures = new AtomicLong();
        private boolean vectorEligible;

        private AdapterCounter(AdapterSafetyClass safetyClass) {
            this.safetyClass = safetyClass;
        }
    }
}
