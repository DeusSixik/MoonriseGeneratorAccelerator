package dev.sixik.generator_accelerator.common.worldgen.parallel;

import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

/**
 * Live chunk-status dispatcher. Minecraft still owns dependency graph ordering;
 * GA moves synchronous status bodies onto bounded worldgen lanes and protects
 * cross-chunk writers with a non-blocking striped admission guard.
 */
public final class GAChunkStatusPipeline {
    private static final GAConfig CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    private static final boolean ENABLED = booleanProperty(
            "ga.chunkPipeline.enabled",
            CONFIG.enableChunkStatusPipeline
    );
    private static final boolean GUARDS_ENABLED = booleanProperty(
            "ga.chunkPipeline.guards",
            CONFIG.chunkPipelineGuards
    );
    private static final boolean BIOMES_ENABLED = booleanProperty(
            "ga.chunkPipeline.biomes.enabled",
            CONFIG.chunkPipelineBiomes
    );
    private static final int FEATURE_MIN_RADIUS = Math.max(0, intProperty(
            "ga.chunkPipeline.featureMinWriteRadius",
            CONFIG.chunkPipelineFeatureMinWriteRadius
    ));
    private static final int SPAWN_MIN_RADIUS = Math.max(0, intProperty(
            "ga.chunkPipeline.spawnMinWriteRadius",
            CONFIG.chunkPipelineSpawnMinWriteRadius
    ));
    private static final int GUARD_STRIPES = nextPowerOfTwo(Math.max(1024, intProperty(
            "ga.chunkPipeline.guardStripes",
            CONFIG.chunkPipelineGuardStripes
    )));
    private static final int GUARD_MASK = GUARD_STRIPES - 1;
    private static final int MAX_FAST_SPINS = Math.max(0, intProperty(
            "ga.chunkPipeline.guardFastSpins",
            CONFIG.chunkPipelineGuardFastSpins
    ));
    private static final long MAX_PARK_NANOS = Math.max(1L, longProperty(
            "ga.chunkPipeline.guardMaxParkNanos",
            CONFIG.chunkPipelineGuardMaxParkNanos
    ));
    private static final int MAX_GUARD_RETRIES = Math.max(1, intProperty(
            "ga.chunkPipeline.guardMaxRetries",
            4096
    ));
    private static final boolean[] STAGE_ENABLED = new boolean[]{
            booleanProperty("ga.chunkPipeline.noise.enabled", CONFIG.chunkPipelineNoise),
            booleanProperty("ga.chunkPipeline.structure_starts.enabled", CONFIG.chunkPipelineStructureStarts),
            booleanProperty("ga.chunkPipeline.structure_references.enabled", CONFIG.chunkPipelineStructureReferences),
            booleanProperty("ga.chunkPipeline.surface.enabled", CONFIG.chunkPipelineSurface),
            booleanProperty("ga.chunkPipeline.carvers.enabled", CONFIG.chunkPipelineCarvers),
            booleanProperty("ga.chunkPipeline.features.enabled", CONFIG.chunkPipelineFeatures),
            booleanProperty("ga.chunkPipeline.spawn.enabled", CONFIG.chunkPipelineSpawn)
    };

    private static final AtomicLongArray GUARDS = new AtomicLongArray(GUARD_STRIPES);
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final AtomicLongArray SUBMITTED = new AtomicLongArray(STAGE_ENABLED.length);
    private static final AtomicLongArray COMPLETED = new AtomicLongArray(STAGE_ENABLED.length);
    private static final AtomicLongArray FAILED = new AtomicLongArray(STAGE_ENABLED.length);
    private static final AtomicLongArray INLINE = new AtomicLongArray(STAGE_ENABLED.length);
    private static final AtomicLongArray GUARD_RETRIES = new AtomicLongArray(STAGE_ENABLED.length);
    private static final AtomicLongArray GUARD_WAIT_NANOS = new AtomicLongArray(STAGE_ENABLED.length);
    private static final ThreadLocal<GuardScratch> GUARD_SCRATCH =
            ThreadLocal.withInitial(GuardScratch::new);
    private static final ThreadLocal<Boolean> INLINE_ON_CURRENT_LANE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private GAChunkStatusPipeline() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean biomesEnabled() {
        return ENABLED && BIOMES_ENABLED;
    }

    public static boolean inlineOnCurrentLane() {
        return INLINE_ON_CURRENT_LANE.get();
    }

    public static <T> T withInlineOnCurrentLane(Supplier<T> task) {
        boolean previous = INLINE_ON_CURRENT_LANE.get();
        INLINE_ON_CURRENT_LANE.set(Boolean.TRUE);
        try {
            return task.get();
        } finally {
            INLINE_ON_CURRENT_LANE.set(previous);
        }
    }

    public static <T> CompletableFuture<T> supplyInlineOnCurrentLane(Supplier<T> task) {
        try {
            return CompletableFuture.completedFuture(task.get());
        } catch (Throwable throwable) {
            return failedFuture(throwable);
        }
    }

    public static CompletableFuture<ChunkAccess> schedule(
            Stage stage,
            GAScheduler.Lane lane,
            ChunkStep step,
            ChunkAccess chunk,
            Supplier<ChunkAccess> task
    ) {
        int stageIndex = stage.ordinal();
        if (!stageEnabled(stage)) {
            INLINE.incrementAndGet(stageIndex);
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (Throwable throwable) {
                return failedFuture(throwable);
            }
        }
        if (inlineOnCurrentLane()) {
            INLINE.incrementAndGet(stageIndex);
            try {
                return CompletableFuture.completedFuture(runGuarded(stage, step, chunk, task));
            } catch (Throwable throwable) {
                return failedFuture(throwable);
            }
        }

        SUBMITTED.incrementAndGet(stageIndex);
        CompletableFuture<ChunkAccess> scheduled = new CompletableFuture<>();
        GAScheduler.executeAsync(
                lane,
                () -> completeScheduledResult(stageIndex, scheduled, runGuarded(stage, step, chunk, task), null),
                failure -> completeScheduledResult(stageIndex, scheduled, null, failure)
        );
        return scheduled;
    }

    public static CompletableFuture<ChunkAccess> scheduleFuture(
            Stage stage,
            GAScheduler.Lane lane,
            ChunkStep step,
            ChunkAccess chunk,
            Supplier<CompletableFuture<ChunkAccess>> task
    ) {
        int stageIndex = stage.ordinal();
        if (!stageEnabled(stage)) {
            INLINE.incrementAndGet(stageIndex);
            try {
                return requireFuture(task.get());
            } catch (Throwable throwable) {
                return failedFuture(throwable);
            }
        }
        if (inlineOnCurrentLane()) {
            INLINE.incrementAndGet(stageIndex);
            try {
                return beginGuardedFuture(stage, step, chunk, task);
            } catch (Throwable throwable) {
                return failedFuture(throwable);
            }
        }

        SUBMITTED.incrementAndGet(stageIndex);
        CompletableFuture<ChunkAccess> scheduled = new CompletableFuture<>();
        GAScheduler.executeAsync(
                lane,
                () -> beginGuardedFuture(stage, step, chunk, task,
                        (result, failure) -> completeScheduledResult(stageIndex, scheduled, result, failure)),
                failure -> completeScheduledResult(stageIndex, scheduled, null, failure)
        );
        return scheduled;
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("guardsEnabled", GUARDS_ENABLED);
        out.put("biomesEnabled", biomesEnabled());
        out.put("guardStripes", GUARD_STRIPES);
        out.put("featureMinWriteRadius", FEATURE_MIN_RADIUS);
        out.put("spawnMinWriteRadius", SPAWN_MIN_RADIUS);
        Map<String, Object> stages = new LinkedHashMap<>();
        for (Stage stage : Stage.values()) {
            int index = stage.ordinal();
            Map<String, Object> stageOut = new LinkedHashMap<>();
            stageOut.put("enabled", ENABLED && STAGE_ENABLED[index]);
            stageOut.put("submitted", SUBMITTED.get(index));
            stageOut.put("completed", COMPLETED.get(index));
            stageOut.put("failed", FAILED.get(index));
            stageOut.put("inline", INLINE.get(index));
            stageOut.put("guardRetries", GUARD_RETRIES.get(index));
            stageOut.put("guardWaitNanos", GUARD_WAIT_NANOS.get(index));
            stages.put(stage.jsonName(), stageOut);
        }
        out.put("stages", stages);
        return out;
    }

    public static void resetMetrics() {
        for (int i = 0; i < Stage.values().length; i++) {
            SUBMITTED.set(i, 0L);
            COMPLETED.set(i, 0L);
            FAILED.set(i, 0L);
            INLINE.set(i, 0L);
            GUARD_RETRIES.set(i, 0L);
            GUARD_WAIT_NANOS.set(i, 0L);
        }
    }

    private static ChunkAccess runGuarded(
            Stage stage,
            ChunkStep step,
            ChunkAccess chunk,
            Supplier<ChunkAccess> task
    ) {
        int radius = writeRadius(stage, step);
        if (!GUARDS_ENABLED || radius <= 0 || chunk == null) {
            return task.get();
        }

        GuardScratch scratch = GUARD_SCRATCH.get();
        ChunkPos pos = chunk.getPos();
        long token = nextToken();
        long waitStart = 0L;
        int retries = 0;
        while (!tryAcquireRegion(pos.x, pos.z, radius, token, scratch)) {
            if (retries == 0) {
                waitStart = System.nanoTime();
            }
            retries++;
            if (retries >= MAX_GUARD_RETRIES || Thread.currentThread().isInterrupted()) {
                recordGuardWait(stage, retries, waitStart);
                return task.get();
            }
            backoff(retries);
            if (Thread.currentThread().isInterrupted()) {
                throw interruptedGuardWait(stage, pos, radius);
            }
        }

        recordGuardWait(stage, retries, waitStart);

        try {
            return task.get();
        } finally {
            releaseRegion(token, scratch);
        }
    }

    private static CompletableFuture<ChunkAccess> beginGuardedFuture(
            Stage stage,
            ChunkStep step,
            ChunkAccess chunk,
            Supplier<CompletableFuture<ChunkAccess>> task
    ) {
        GuardLease lease = acquireLease(stage, step, chunk);
        try {
            CompletableFuture<ChunkAccess> future = requireFuture(task.get());
            return future.whenComplete((ignored, failure) -> lease.release());
        } catch (Throwable throwable) {
            lease.release();
            throw throwable;
        }
    }

    private static void beginGuardedFuture(
            Stage stage,
            ChunkStep step,
            ChunkAccess chunk,
            Supplier<CompletableFuture<ChunkAccess>> task,
            ChunkCompletionCallback callback
    ) {
        GuardLease lease = acquireLease(stage, step, chunk);
        try {
            CompletableFuture<ChunkAccess> future = requireFuture(task.get());
            future.whenComplete((result, failure) -> {
                lease.release();
                callback.accept(result, failure);
            });
        } catch (Throwable throwable) {
            lease.release();
            throw throwable;
        }
    }

    private static GuardLease acquireLease(Stage stage, ChunkStep step, ChunkAccess chunk) {
        int radius = writeRadius(stage, step);
        if (!GUARDS_ENABLED || radius <= 0 || chunk == null) {
            return GuardLease.NOOP;
        }

        GuardScratch scratch = GUARD_SCRATCH.get();
        ChunkPos pos = chunk.getPos();
        long token = nextToken();
        long waitStart = 0L;
        int retries = 0;
        while (!tryAcquireRegion(pos.x, pos.z, radius, token, scratch)) {
            if (retries == 0) {
                waitStart = System.nanoTime();
            }
            retries++;
            if (retries >= MAX_GUARD_RETRIES || Thread.currentThread().isInterrupted()) {
                recordGuardWait(stage, retries, waitStart);
                return GuardLease.NOOP;
            }
            backoff(retries);
            if (Thread.currentThread().isInterrupted()) {
                throw interruptedGuardWait(stage, pos, radius);
            }
        }

        recordGuardWait(stage, retries, waitStart);

        return GuardLease.copy(token, scratch);
    }

    private static void recordGuardWait(Stage stage, int retries, long waitStart) {
        if (retries <= 0) {
            return;
        }
        int index = stage.ordinal();
        GUARD_RETRIES.addAndGet(index, retries);
        GUARD_WAIT_NANOS.addAndGet(index, System.nanoTime() - waitStart);
    }

    private static boolean stageEnabled(Stage stage) {
        return ENABLED && STAGE_ENABLED[stage.ordinal()];
    }

    private static int writeRadius(Stage stage, ChunkStep step) {
        int stepRadius = step == null ? 0 : Math.max(0, step.blockStateWriteRadius());
        return switch (stage) {
            case FEATURES -> Math.max(stepRadius, FEATURE_MIN_RADIUS);
            case SPAWN -> Math.max(stepRadius, SPAWN_MIN_RADIUS);
            case NOISE, STRUCTURE_REFERENCES, SURFACE, CARVERS -> stepRadius;
            case STRUCTURE_STARTS -> 0;
        };
    }

    private static boolean tryAcquireRegion(int centerX, int centerZ, int radius, long token, GuardScratch scratch) {
        scratch.reset((radius * 2 + 1) * (radius * 2 + 1));
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int stripe = stripe(centerX + dx, centerZ + dz);
                scratch.addStripe(stripe);
            }
        }
        scratch.sortAndCompactStripes();

        for (int i = 0; i < scratch.stripeCount; i++) {
            int stripe = scratch.stripes[i];
            if (GUARDS.compareAndSet(stripe, 0L, token)) {
                scratch.acquired[scratch.acquiredCount++] = stripe;
                continue;
            }
            rollback(token, scratch);
            return false;
        }
        return true;
    }

    private static void rollback(long token, GuardScratch scratch) {
        for (int i = scratch.acquiredCount - 1; i >= 0; i--) {
            GUARDS.compareAndSet(scratch.acquired[i], token, 0L);
        }
        scratch.acquiredCount = 0;
    }

    private static void releaseRegion(long token, GuardScratch scratch) {
        for (int i = scratch.acquiredCount - 1; i >= 0; i--) {
            GUARDS.compareAndSet(scratch.acquired[i], token, 0L);
        }
        scratch.acquiredCount = 0;
    }

    private static int stripe(int chunkX, int chunkZ) {
        long x = chunkX * 0x9E3779B97F4A7C15L;
        long z = chunkZ * 0xC2B2AE3D27D4EB4FL;
        long h = x ^ Long.rotateLeft(z, 31);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (int) h & GUARD_MASK;
    }

    private static void backoff(int retries) {
        if (retries <= MAX_FAST_SPINS) {
            Thread.onSpinWait();
            return;
        }
        long nanos = Math.min(MAX_PARK_NANOS, 1_000L << Math.min(8, retries - MAX_FAST_SPINS));
        java.util.concurrent.locks.LockSupport.parkNanos(nanos);
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
        }
    }

    private static CancellationException interruptedGuardWait(Stage stage, ChunkPos pos, int radius) {
        Thread.currentThread().interrupt();
        return new CancellationException(
                "Interrupted while waiting for " + stage.jsonName()
                        + " chunk-status guard at " + pos
                        + " radius=" + radius
        );
    }

    private static long nextToken() {
        long token = NEXT_TOKEN.getAndIncrement();
        if (token == 0L) {
            token = NEXT_TOKEN.getAndIncrement();
        }
        return token;
    }

    private static int nextPowerOfTwo(int value) {
        int highest = Integer.highestOneBit(value);
        if (highest == value) {
            return value;
        }
        return highest >= (1 << 30) ? 1 << 30 : highest << 1;
    }

    private static boolean booleanProperty(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int intProperty(String property, int fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longProperty(String property, long fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(throwable);
        return failed;
    }

    private static CompletableFuture<ChunkAccess> requireFuture(CompletableFuture<ChunkAccess> future) {
        if (future == null) {
            throw new NullPointerException("Chunk status task returned null future");
        }
        return future;
    }

    private static void completeScheduledResult(
            int stageIndex,
            CompletableFuture<ChunkAccess> future,
            ChunkAccess result,
            Throwable failure
    ) {
        if (failure == null) {
            COMPLETED.incrementAndGet(stageIndex);
            future.complete(result);
        } else {
            FAILED.incrementAndGet(stageIndex);
            future.completeExceptionally(failure);
        }
    }

    @FunctionalInterface
    private interface ChunkCompletionCallback {
        void accept(ChunkAccess result, Throwable failure);
    }

    public enum Stage {
        NOISE("noise"),
        STRUCTURE_STARTS("structure_starts"),
        STRUCTURE_REFERENCES("structure_references"),
        SURFACE("surface"),
        CARVERS("carvers"),
        FEATURES("features"),
        SPAWN("spawn");

        private final String jsonName;

        Stage(String jsonName) {
            this.jsonName = jsonName;
        }

        String jsonName() {
            return jsonName;
        }
    }

    private static final class GuardScratch {
        private int[] stripes = new int[32];
        private int[] acquired = new int[32];
        private int stripeCount;
        private int acquiredCount;

        private void reset(int required) {
            ensureCapacity(required);
            stripeCount = 0;
            acquiredCount = 0;
        }

        private void addStripe(int stripe) {
            stripes[stripeCount++] = stripe;
        }

        private void sortAndCompactStripes() {
            if (stripeCount <= 1) {
                return;
            }
            java.util.Arrays.sort(stripes, 0, stripeCount);
            int unique = 1;
            int previous = stripes[0];
            for (int i = 1; i < stripeCount; i++) {
                int stripe = stripes[i];
                if (stripe == previous) {
                    continue;
                }
                stripes[unique++] = stripe;
                previous = stripe;
            }
            stripeCount = unique;
        }

        private void ensureCapacity(int required) {
            if (stripes.length >= required) {
                return;
            }
            int newLength = stripes.length;
            while (newLength < required) {
                newLength <<= 1;
            }
            stripes = java.util.Arrays.copyOf(stripes, newLength);
            acquired = java.util.Arrays.copyOf(acquired, newLength);
        }
    }

    private record GuardLease(long token, int[] acquired, int acquiredCount) {
        private static final GuardLease NOOP = new GuardLease(0L, new int[0], 0);

        private static GuardLease copy(long token, GuardScratch scratch) {
            int count = scratch.acquiredCount;
            if (count == 0) {
                return NOOP;
            }
            return new GuardLease(token, java.util.Arrays.copyOf(scratch.acquired, count), count);
        }

        private void release() {
            for (int i = acquiredCount - 1; i >= 0; i--) {
                GUARDS.compareAndSet(acquired[i], token, 0L);
            }
        }
    }
}
