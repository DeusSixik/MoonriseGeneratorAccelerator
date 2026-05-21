package dev.sixik.generator_accelerator.common.worldgen.region;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deduplicated compile-lane prewarm orchestration for regional worldgen caches.
 */
public final class GARegionalPrewarmManager {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("ga.region.prewarm.enabled", "false")
    );

    private static final ConcurrentHashMap<Object, CompletableFuture<Void>> TASKS = new ConcurrentHashMap<>();

    private static final AtomicLong REQUESTED = new AtomicLong();
    private static final AtomicLong INFLIGHT_HIT = new AtomicLong();
    private static final AtomicLong READY_HIT = new AtomicLong();
    private static final AtomicLong INLINE_MISS = new AtomicLong();
    private static final AtomicLong CANCELLED = new AtomicLong();
    private static final AtomicLong WAIT_NANOS = new AtomicLong();
    private static final AtomicLongArray REQUESTED_BY_TYPE = new AtomicLongArray(RequestType.values().length);
    private static final AtomicLongArray READY_BY_TYPE = new AtomicLongArray(RequestType.values().length);
    private static final AtomicLongArray INFLIGHT_BY_TYPE = new AtomicLongArray(RequestType.values().length);
    private static final AtomicLongArray INLINE_BY_TYPE = new AtomicLongArray(RequestType.values().length);

    private GARegionalPrewarmManager() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static CompletableFuture<Void> request(Object key, Runnable action) {
        return request(RequestType.TERRAIN, key, action);
    }

    public static CompletableFuture<Void> request(RequestType type, Object key, Runnable action) {
        if (!ENABLED || key == null || action == null) {
            return CompletableFuture.completedFuture(null);
        }
        REQUESTED.incrementAndGet();
        REQUESTED_BY_TYPE.incrementAndGet(type.ordinal());
        CompletableFuture<Void> current = TASKS.get(key);
        if (current != null) {
            if (current.isDone()) {
                READY_HIT.incrementAndGet();
                READY_BY_TYPE.incrementAndGet(type.ordinal());
            } else {
                INFLIGHT_HIT.incrementAndGet();
                INFLIGHT_BY_TYPE.incrementAndGet(type.ordinal());
            }
            return current;
        }
        CompletableFuture<Void> created = new CompletableFuture<>();
        CompletableFuture<Void> existing = TASKS.putIfAbsent(key, created);
        if (existing != null) {
            if (existing.isDone()) {
                READY_HIT.incrementAndGet();
                READY_BY_TYPE.incrementAndGet(type.ordinal());
            } else {
                INFLIGHT_HIT.incrementAndGet();
                INFLIGHT_BY_TYPE.incrementAndGet(type.ordinal());
            }
            return existing;
        }
        GAScheduler.executeAsync(GAScheduler.Lane.COMPILE, () -> {
            try {
                action.run();
                created.complete(null);
            } catch (Throwable throwable) {
                created.completeExceptionally(throwable);
                throw throwable;
            }
        }, created::completeExceptionally);
        created.whenComplete((ignored, throwable) -> TASKS.remove(key, created));
        return created;
    }

    public static void ensureInline(Object key, Runnable action) {
        ensureInline(RequestType.TERRAIN, key, action);
    }

    public static void ensureInline(RequestType type, Object key, Runnable action) {
        if (!ENABLED || key == null || action == null) {
            action.run();
            return;
        }
        CompletableFuture<Void> current = TASKS.get(key);
        if (current == null) {
            INLINE_MISS.incrementAndGet();
            INLINE_BY_TYPE.incrementAndGet(type.ordinal());
            action.run();
            return;
        }
        // Regional prewarm is only an optimization. Waiting here can stall the NOISE/SURFACE
        // hot path or self-deadlock through nested region work, so always assist inline instead.
        action.run();
    }

    public static boolean cancel(Object key) {
        if (!ENABLED || key == null) {
            return false;
        }
        CompletableFuture<Void> future = TASKS.remove(key);
        if (future == null) {
            return false;
        }
        boolean cancelled = future.cancel(false);
        if (cancelled) {
            CANCELLED.incrementAndGet();
        }
        return cancelled;
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("requested", REQUESTED.get());
        out.put("inflightHit", INFLIGHT_HIT.get());
        out.put("readyHit", READY_HIT.get());
        out.put("inlineMiss", INLINE_MISS.get());
        out.put("cancelled", CANCELLED.get());
        out.put("waitNanos", WAIT_NANOS.get());
        out.put("inflightTasks", TASKS.size());
        Map<String, Object> byType = new LinkedHashMap<>();
        for (RequestType type : RequestType.values()) {
            Map<String, Object> typeOut = new LinkedHashMap<>();
            int index = type.ordinal();
            typeOut.put("requested", REQUESTED_BY_TYPE.get(index));
            typeOut.put("readyHit", READY_BY_TYPE.get(index));
            typeOut.put("inflightHit", INFLIGHT_BY_TYPE.get(index));
            typeOut.put("inlineMiss", INLINE_BY_TYPE.get(index));
            byType.put(type.jsonName, typeOut);
        }
        out.put("byType", byType);
        return out;
    }

    static void clearForTests() {
        TASKS.clear();
        REQUESTED.set(0L);
        INFLIGHT_HIT.set(0L);
        READY_HIT.set(0L);
        INLINE_MISS.set(0L);
        CANCELLED.set(0L);
        WAIT_NANOS.set(0L);
        for (int i = 0; i < RequestType.values().length; i++) {
            REQUESTED_BY_TYPE.set(i, 0L);
            READY_BY_TYPE.set(i, 0L);
            INFLIGHT_BY_TYPE.set(i, 0L);
            INLINE_BY_TYPE.set(i, 0L);
        }
    }

    public enum RequestType {
        TERRAIN("terrain"),
        SURFACE("surface"),
        CLIMATE("climate");

        private final String jsonName;

        RequestType(String jsonName) {
            this.jsonName = jsonName;
        }
    }
}
