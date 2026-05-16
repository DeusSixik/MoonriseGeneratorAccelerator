package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class GAOuterLifecycleMetrics {
    private static final AtomicLong LIGHTING_HANDOFFS = new AtomicLong();
    private static final AtomicLong DIRTY_LIGHT_COLUMNS = new AtomicLong();
    private static final AtomicLong SERIALIZATION_BATCHES = new AtomicLong();
    private static final AtomicLong SERIALIZED_CHUNKS = new AtomicLong();
    private static final AtomicLong PROMOTION_ALLOWS = new AtomicLong();
    private static final AtomicLong PROMOTION_DEFERS = new AtomicLong();
    private static final AtomicLong PROMOTION_FALLBACKS = new AtomicLong();

    private GAOuterLifecycleMetrics() {
    }

    public static void recordLightingHandoff(GALightingHandoffMask mask) {
        if (mask == null) {
            throw new NullPointerException("mask");
        }
        LIGHTING_HANDOFFS.incrementAndGet();
        DIRTY_LIGHT_COLUMNS.addAndGet(mask.dirtyColumnCount());
    }

    public static void recordSerializationBatch(GASerializationBatchPlan plan) {
        if (plan == null) {
            throw new NullPointerException("plan");
        }
        SERIALIZATION_BATCHES.incrementAndGet();
        SERIALIZED_CHUNKS.addAndGet(plan.chunkCount());
    }

    public static void recordPublishingDecision(GAPublishingGuardDecision decision) {
        if (decision == null) {
            throw new NullPointerException("decision");
        }
        switch (decision.action()) {
            case ALLOW_OPTIMIZED -> PROMOTION_ALLOWS.incrementAndGet();
            case DEFER_TO_VANILLA -> PROMOTION_DEFERS.incrementAndGet();
            case FALLBACK_SERIAL -> PROMOTION_FALLBACKS.incrementAndGet();
        }
    }

    public static GAOuterLifecycleSnapshot snapshot() {
        return new GAOuterLifecycleSnapshot(
                LIGHTING_HANDOFFS.get(),
                DIRTY_LIGHT_COLUMNS.get(),
                SERIALIZATION_BATCHES.get(),
                SERIALIZED_CHUNKS.get(),
                PROMOTION_ALLOWS.get(),
                PROMOTION_DEFERS.get(),
                PROMOTION_FALLBACKS.get()
        );
    }

    public static Map<String, Object> snapshotMap() {
        GAOuterLifecycleSnapshot snapshot = snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lightingHandoffs", snapshot.lightingHandoffs());
        out.put("dirtyLightColumns", snapshot.dirtyLightColumns());
        out.put("serializationBatches", snapshot.serializationBatches());
        out.put("serializedChunks", snapshot.serializedChunks());
        out.put("promotionAllows", snapshot.promotionAllows());
        out.put("promotionDefers", snapshot.promotionDefers());
        out.put("promotionFallbacks", snapshot.promotionFallbacks());
        return out;
    }

    public static void resetGlobal() {
        LIGHTING_HANDOFFS.set(0L);
        DIRTY_LIGHT_COLUMNS.set(0L);
        SERIALIZATION_BATCHES.set(0L);
        SERIALIZED_CHUNKS.set(0L);
        PROMOTION_ALLOWS.set(0L);
        PROMOTION_DEFERS.set(0L);
        PROMOTION_FALLBACKS.set(0L);
    }
}
