package dev.sixik.generator_accelerator.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

/**
 * Default-off aggregate timers for chunk-generation wall-time experiments.
 *
 * <p>Call sites are placed at chunk/status or bulk-operation boundaries only;
 * this class is not intended for per-block timing.</p>
 */
public final class GAWallTimeTelemetry {
    private static final long NO_TIMER = Long.MIN_VALUE;

    public static volatile boolean ENABLED = Boolean.getBoolean("ga.wallTimeTelemetry");

    private static final Stage[] STAGES = Stage.values();
    private static final AtomicLongArray COUNTS = new AtomicLongArray(STAGES.length);
    private static final AtomicLongArray NANOS = new AtomicLongArray(STAGES.length);
    private static final AtomicLongArray MAX_NANOS = new AtomicLongArray(STAGES.length);

    private GAWallTimeTelemetry() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static long start(Stage stage) {
        return ENABLED && stage != null ? System.nanoTime() : NO_TIMER;
    }

    public static void end(Stage stage, long startNanos) {
        if (startNanos == NO_TIMER || stage == null) {
            return;
        }
        addElapsed(stage, System.nanoTime() - startNanos);
    }

    public static void addElapsed(Stage stage, long elapsedNanos) {
        if (!ENABLED || stage == null) {
            return;
        }
        long positive = Math.max(0L, elapsedNanos);
        int index = stage.ordinal();
        COUNTS.incrementAndGet(index);
        NANOS.addAndGet(index, positive);
        updateMax(index, positive);
    }

    public static <T> T time(Stage stage, Supplier<T> task) {
        long start = start(stage);
        try {
            return task.get();
        } finally {
            end(stage, start);
        }
    }

    public static <T> CompletableFuture<T> endWhenComplete(Stage stage, long startNanos, CompletableFuture<T> future) {
        if (startNanos == NO_TIMER || stage == null) {
            return future;
        }
        return future.whenComplete((ignored, failure) -> end(stage, startNanos));
    }

    public static void reset() {
        for (int i = 0; i < STAGES.length; i++) {
            COUNTS.set(i, 0L);
            NANOS.set(i, 0L);
            MAX_NANOS.set(i, 0L);
        }
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);

        Map<String, Object> stages = new LinkedHashMap<>();
        for (Stage stage : STAGES) {
            int index = stage.ordinal();
            long count = COUNTS.get(index);
            long nanos = NANOS.get(index);

            Map<String, Object> stageOut = new LinkedHashMap<>();
            stageOut.put("count", count);
            stageOut.put("nanos", nanos);
            stageOut.put("millis", nanos / 1_000_000.0D);
            stageOut.put("avgNanos", count == 0L ? 0L : nanos / count);
            stageOut.put("maxNanos", MAX_NANOS.get(index));
            stages.put(stage.jsonName(), stageOut);
        }
        out.put("stages", stages);
        return out;
    }

    public static String summary() {
        if (!ENABLED) {
            return "GA wall telemetry: disabled";
        }
        StringBuilder builder = new StringBuilder(256);
        builder.append("GA wall telemetry:");
        boolean any = false;
        for (Stage stage : STAGES) {
            int index = stage.ordinal();
            long count = COUNTS.get(index);
            if (count == 0L) {
                continue;
            }
            long nanos = NANOS.get(index);
            any = true;
            builder.append(' ')
                    .append(stage.jsonName())
                    .append('=')
                    .append(nanos / 1_000_000L)
                    .append("ms/")
                    .append(count);
        }
        if (!any) {
            builder.append(" no samples");
        }
        return builder.toString();
    }

    private static void updateMax(int index, long value) {
        long current;
        do {
            current = MAX_NANOS.get(index);
            if (value <= current) {
                return;
            }
        } while (!MAX_NANOS.compareAndSet(index, current, value));
    }

    public enum Stage {
        NOISE("noise"),
        NOISE_DO_FILL("noise/doFill"),
        SURFACE("surface"),
        CARVERS("carvers"),
        FEATURES("features"),
        STRUCTURE_STARTS("structure_starts"),
        STRUCTURE_REFERENCES("structure_references"),
        SPAWN("spawn"),
        WORKSPACE_IMPORT("workspace/import"),
        WORKSPACE_COMPUTE("workspace/compute"),
        WORKSPACE_FINALIZE("workspace/finalize"),
        WORKSPACE_REPACK("workspace/repack"),
        CHUNK_STATUS_HANDOFF("chunk_status_handoff"),
        SCHEDULER_WAIT_IDLE("scheduler_wait_idle");

        private final String jsonName;

        Stage(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }
    }
}
