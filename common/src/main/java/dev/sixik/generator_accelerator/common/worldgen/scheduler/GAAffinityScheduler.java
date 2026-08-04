package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$StaticCache2DExtern;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.common.worldgen.parallel.GAChunkStatusPipeline;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinChunkGenerationTaskAccessor;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinChunkMapAccessor;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinGenerationChunkHolderAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class GAAffinityScheduler {
    private static final Class<?> C2ME_CHUNK_SYSTEM_ACCESS = optionalClass(
            "com.ishland.c2me.rewrites.chunksystem.common.ducks.IChunkSystemAccess"
    );
    private static final Class<?> MOONRISE_CHUNK_SYSTEM_CHUNK_MAP = optionalClass(
            "ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemChunkMap"
    );
    private static final GAConfig CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    private static final boolean ALLOW_STARTUP_INTERCEPT = booleanProperty(
            "ga.scheduler.v2.allowStartupIntercept",
            false
    );
    private static final AtomicLong TASKS_SUBMITTED = new AtomicLong();
    private static final AtomicLong TASKS_ACCEPTED = new AtomicLong();
    private static final AtomicLong TASKS_COMPLETED = new AtomicLong();
    private static final AtomicLong TASKS_FAILED = new AtomicLong();
    private static final AtomicLong TASKS_CANCELLED = new AtomicLong();
    private static final AtomicLong TASKS_FALLBACK_TO_VANILLA = new AtomicLong();
    private static final AtomicLong EMPTY_NODES_SUBMITTED = new AtomicLong();
    private static final AtomicLong GRAPH_NODES_SUBMITTED = new AtomicLong();
    private static final AtomicLong NODES_COMPLETED = new AtomicLong();
    private static final AtomicLong NODES_FAILED = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong BATCHES_BUILT = new AtomicLong();
    private static final AtomicLong BATCHES_SUBMITTED = new AtomicLong();
    private static final AtomicLong ACTIVE_TASKS = new AtomicLong();
    private static final AtomicLong ACTIVE_NODES = new AtomicLong();
    private static final AtomicLong STALLED_PHASES = new AtomicLong();
    private static final Map<String, AtomicLong> FALLBACK_REASONS = new ConcurrentHashMap<>();
    private static final java.util.Set<TaskRun> ACTIVE_RUNS = ConcurrentHashMap.newKeySet();

    private static volatile GABatchDispatcher dispatcher;
    private static volatile boolean shutdownRequested;
    private static volatile boolean startupPhase = true;

    private GAAffinityScheduler() {
    }

    public static void init(GAWorkerConfig config) {
        if (!config.chunkSchedulerEnabled()) {
            return;
        }
        synchronized (GAAffinityScheduler.class) {
            if (dispatcher != null) {
                return;
            }
            dispatcher = new GABatchDispatcher("GA-V2-DISPATCHER", Math.max(64, config.batchMaxCenters() * Math.max(1, config.workers())));
            dispatcher.start();
        }
    }

    public static boolean enabled() {
        return GAScheduler.v2Enabled();
    }

    public static boolean canInterceptGenerationTasks() {
        return enabled() && !shutdownRequested && (ALLOW_STARTUP_INTERCEPT || !startupPhase);
    }

    public static boolean canInterceptGenerationTasks(ChunkMap chunkMap) {
        if (!canInterceptGenerationTasks()) {
            return false;
        }
        if (usesMoonriseChunkSystem(chunkMap) || usesC2MEChunkSystem(chunkMap)) {
            recordFallback(GAMetrics.FallbackReason.COMPAT_CHUNK_SCHEDULER);
            return false;
        }
        return true;
    }

    public static boolean schedule(ChunkMap chunkMap, ChunkGenerationTask task) {
        if (!canInterceptGenerationTasks(chunkMap)) {
            return false;
        }
        GASchedulerRuntime runtime = GAScheduler.v2Runtime();
        if (runtime == null || !runtime.admissionOpen()) {
            recordFallback(GAMetrics.FallbackReason.DISABLED);
            return false;
        }
        GAAdmissionDecision decision = classify(task.targetStatus, runtime.config());
        if (!decision.admitted()) {
            recordFallback(decision.reason());
            runtime.metrics().recordFallback(decision.reason());
            return false;
        }
        GAAdmissionDecision preflight = preflightTask(task, decision, runtime.config());
        if (!preflight.admitted()) {
            recordFallback(preflight.reason());
            runtime.metrics().recordFallback(preflight.reason());
            return false;
        }
        TASKS_SUBMITTED.incrementAndGet();
        GABatchDispatcher currentDispatcher = dispatcher;
        if (currentDispatcher == null) {
            init(runtime.config());
            currentDispatcher = dispatcher;
        }
        if (currentDispatcher == null) {
            recordFallback(GAMetrics.FallbackReason.DISPATCHER_FAILURE);
            return false;
        }
        TaskRun run = new TaskRun(runtime, chunkMap, task, decision);
        boolean accepted = currentDispatcher.submit(run::start);
        if (!accepted) {
            recordFallback(GAMetrics.FallbackReason.PRESSURE);
            runtime.metrics().recordFallback(GAMetrics.FallbackReason.PRESSURE);
            return false;
        }
        TASKS_ACCEPTED.incrementAndGet();
        return true;
    }

    public static GAAdmissionDecision classify(ChunkStatus status, GAWorkerConfig config) {
        String name = statusName(status);
        if (config.forceLegacyStatus(name)) {
            return GAAdmissionDecision.legacy(GAMetrics.FallbackReason.FORCE_LEGACY_STATUS, "forced legacy status " + name);
        }
        if (status == ChunkStatus.EMPTY) {
            return GAAdmissionDecision.boundary("EMPTY holder/load boundary");
        }
        if (status == ChunkStatus.NOISE) {
            return GAAdmissionDecision.full(GATaskClass.CPU_NOISE, "NOISE is CPU-heavy non-writer target");
        }
        if (name.contains("biome")) {
            return GAAdmissionDecision.full(GATaskClass.CPU_WORKSPACE, "BIOMES admitted only as pure/read-only status");
        }
        if (name.contains("surface") || name.contains("carver")) {
            if (booleanProperty("ga.scheduler.v2.admitWorkspaceWriters", false)) {
                return GAAdmissionDecision.workspace("workspace-local writer status explicitly admitted");
            }
            return GAAdmissionDecision.legacy(GAMetrics.FallbackReason.UNSAFE_STATUS, "workspace writer status not explicitly certified: " + name);
        }
        if (status == ChunkStatus.FEATURES || status == ChunkStatus.SPAWN) {
            return GAAdmissionDecision.legacy(GAMetrics.FallbackReason.UNSAFE_STATUS, "FEATURES/SPAWN remain guarded legacy writer path");
        }
        if (status == ChunkStatus.FULL || name.contains("light")) {
            return GAAdmissionDecision.legacy(GAMetrics.FallbackReason.UNSAFE_STATUS, "commit/light/full boundary not owned by v2 core");
        }
        return GAAdmissionDecision.legacy(GAMetrics.FallbackReason.UNSAFE_STATUS, "status safety not certified: " + name);
    }

    private static GAAdmissionDecision preflightTask(ChunkGenerationTask task, GAAdmissionDecision targetDecision, GAWorkerConfig config) {
        if (targetDecision.kind() == GAAdmissionDecision.Kind.ADMIT_BOUNDARY) {
            return targetDecision;
        }
        MixinChunkGenerationTaskAccessor taskAccess = (MixinChunkGenerationTaskAccessor) (Object) task;
        StaticCache2D<GenerationChunkHolder> cache = taskAccess.ga$getCache();
        GA$StaticCache2DExtern<GenerationChunkHolder> cacheExtern = GA$StaticCache2DExtern.get(cache);
        Object[] raw = cacheExtern.ga$getRawData();
        ChunkPos center = taskAccess.ga$getPos();
        ChunkStatus targetStatus = task.targetStatus;
        ChunkStep targetStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(targetStatus);
        Object[] statuses = statusRawList();
        int statusCount = statusRawListSize();
        for (int i = 0; i < statusCount; i++) {
            ChunkStatus status = (ChunkStatus) statuses[i];
            if (status == ChunkStatus.EMPTY || status.isAfter(targetStatus)) {
                continue;
            }
            GAAdmissionDecision statusDecision = classify(status, config);
            if (statusDecision.admitted()) {
                continue;
            }
            int radius = targetStep.getAccumulatedRadiusOf(status);
            for (int x = center.x - radius; x <= center.x + radius; x++) {
                int xIndex = cacheExtern.ga$getX(x);
                for (int z = center.z - radius; z <= center.z + radius; z++) {
                    GenerationChunkHolder holder = (GenerationChunkHolder) raw[xIndex + cacheExtern.ga$getZ(z)];
                    if (holder.getChunkIfPresentUnchecked(status) == null) {
                        return GAAdmissionDecision.legacy(statusDecision.reason(),
                                "unsafe prerequisite missing: " + statusName(status) + " for " + statusName(targetStatus));
                    }
                }
            }
        }
        return targetDecision;
    }

    public static void beginShutdown() {
        shutdownDispatcher();
    }

    public static void resetShutdownRequest() {
        shutdownRequested = false;
        startupPhase = true;
    }

    public static void shutdownForTests() {
        GABatchDispatcher current = shutdownDispatcher();
        if (current != null) {
            try {
                current.join(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static GABatchDispatcher shutdownDispatcher() {
        shutdownRequested = true;
        for (TaskRun run : ACTIVE_RUNS) {
            run.requestCancellation();
        }
        GABatchDispatcher current;
        synchronized (GAAffinityScheduler.class) {
            current = dispatcher;
            dispatcher = null;
        }
        if (current != null) {
            current.shutdown();
        }
        return current;
    }

    public static void markServerTickStarted() {
        startupPhase = false;
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("canInterceptGenerationTasks", canInterceptGenerationTasks());
        out.put("c2meChunkSystemClassPresent", C2ME_CHUNK_SYSTEM_ACCESS != null);
        out.put("moonriseChunkSystemClassPresent", MOONRISE_CHUNK_SYSTEM_CHUNK_MAP != null);
        out.put("allowStartupIntercept", ALLOW_STARTUP_INTERCEPT);
        out.put("startupPhase", startupPhase);
        out.put("tasksSubmitted", TASKS_SUBMITTED.get());
        out.put("tasksAccepted", TASKS_ACCEPTED.get());
        out.put("tasksCompleted", TASKS_COMPLETED.get());
        out.put("tasksFailed", TASKS_FAILED.get());
        out.put("tasksCancelled", TASKS_CANCELLED.get());
        out.put("tasksFallbackToVanilla", TASKS_FALLBACK_TO_VANILLA.get());
        out.put("emptyNodesSubmitted", EMPTY_NODES_SUBMITTED.get());
        out.put("graphNodesSubmitted", GRAPH_NODES_SUBMITTED.get());
        out.put("nodesCompleted", NODES_COMPLETED.get());
        out.put("nodesFailed", NODES_FAILED.get());
        out.put("cacheMisses", CACHE_MISSES.get());
        out.put("batchesBuilt", BATCHES_BUILT.get());
        out.put("batchesSubmitted", BATCHES_SUBMITTED.get());
        out.put("activeTasks", ACTIVE_TASKS.get());
        out.put("activeNodes", ACTIVE_NODES.get());
        out.put("stalledPhases", STALLED_PHASES.get());
        out.put("fallbackReasons", fallbackReasonsSnapshot());
        GABatchDispatcher current = dispatcher;
        out.put("dispatcher", current == null ? Map.of("alive", false) : current.snapshot());
        return out;
    }

    public static void resetMetrics() {
        TASKS_SUBMITTED.set(0L);
        TASKS_ACCEPTED.set(0L);
        TASKS_COMPLETED.set(0L);
        TASKS_FAILED.set(0L);
        TASKS_CANCELLED.set(0L);
        TASKS_FALLBACK_TO_VANILLA.set(0L);
        EMPTY_NODES_SUBMITTED.set(0L);
        GRAPH_NODES_SUBMITTED.set(0L);
        NODES_COMPLETED.set(0L);
        NODES_FAILED.set(0L);
        CACHE_MISSES.set(0L);
        BATCHES_BUILT.set(0L);
        BATCHES_SUBMITTED.set(0L);
        STALLED_PHASES.set(0L);
        FALLBACK_REASONS.clear();
    }

    private static void recordFallback(GAMetrics.FallbackReason reason) {
        if (reason == null) {
            return;
        }
        FALLBACK_REASONS.computeIfAbsent(reason.jsonName(), ignored -> new AtomicLong()).incrementAndGet();
    }

    private static Map<String, Object> fallbackReasonsSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : FALLBACK_REASONS.entrySet()) {
            out.put(entry.getKey(), entry.getValue().get());
        }
        return out;
    }

    private static String statusName(ChunkStatus status) {
        return String.valueOf(status).toLowerCase(Locale.ROOT);
    }

    private static boolean booleanProperty(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static boolean usesMoonriseChunkSystem(Object chunkMap) {
        return MOONRISE_CHUNK_SYSTEM_CHUNK_MAP != null
                && MOONRISE_CHUNK_SYSTEM_CHUNK_MAP.isInstance(chunkMap);
    }

    private static boolean usesC2MEChunkSystem(Object chunkMap) {
        return CONFIG.schedulerV2CompatRefuseUnknownChunkScheduler
                && C2ME_CHUNK_SYSTEM_ACCESS != null
                && C2ME_CHUNK_SYSTEM_ACCESS.isInstance(chunkMap);
    }

    private static Class<?> optionalClass(String className) {
        try {
            return Class.forName(className, false, GAAffinityScheduler.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static final class TaskRun {
        private static final int PHASE_NONE = 0;
        private static final int PHASE_AFTER_INITIAL_EMPTY = 1;
        private static final int PHASE_BUILD_GRAPH_GENERATION = 2;
        private static final int PHASE_FINISH_SUCCESS = 3;

        private final GASchedulerRuntime runtime;
        private final ChunkMap chunkMap;
        private final ChunkGenerationTask task;
        private final MixinChunkGenerationTaskAccessor taskAccess;
        private final ChunkPos center;
        private final StaticCache2D<GenerationChunkHolder> cache;
        private final GA$StaticCache2DExtern<GenerationChunkHolder> cacheExtern;
        private final Object[] cacheRawData;
        private final int cacheIndex;
        private final ChunkStatus targetStatus;
        private final GAAdmissionDecision decision;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean registered = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean fallbackQueued = new AtomicBoolean();
        private final AtomicInteger remaining = new AtomicInteger();
        private volatile int nextPhaseId = PHASE_NONE;
        private int storedGenerationEmptyRadius;
        private int initialRadius;

        private TaskRun(GASchedulerRuntime runtime, ChunkMap chunkMap, ChunkGenerationTask task, GAAdmissionDecision decision) {
            this.runtime = runtime;
            this.chunkMap = chunkMap;
            this.task = task;
            this.taskAccess = (MixinChunkGenerationTaskAccessor) (Object) task;
            this.center = this.taskAccess.ga$getPos();
            this.cache = this.taskAccess.ga$getCache();
            this.cacheExtern = GA$StaticCache2DExtern.get(cache);
            this.cacheIndex = cacheExtern.ga$getIndex(center.x, center.z);
            this.cacheRawData = cacheExtern.ga$getRawData();
            this.targetStatus = task.targetStatus;
            this.decision = decision;
        }

        private void start() {
            registerActiveRun();
            try {
                if (shouldCancel()) {
                    requestCancellation();
                    return;
                }
                int loadingEmptyRadius = ChunkPyramid.LOADING_PYRAMID
                        .getStepTo(this.targetStatus)
                        .getAccumulatedRadiusOf(ChunkStatus.EMPTY);
                int generationEmptyRadius = ChunkPyramid.GENERATION_PYRAMID
                        .getStepTo(this.targetStatus)
                        .getAccumulatedRadiusOf(ChunkStatus.EMPTY);
                this.storedGenerationEmptyRadius = generationEmptyRadius;
                this.initialRadius = generationEmptyRadius == 0 ? loadingEmptyRadius : generationEmptyRadius;
                loadEmptyRadius(this.initialRadius, PHASE_AFTER_INITIAL_EMPTY);
            } catch (Throwable throwable) {
                fail(throwable);
            }
        }

        private void afterInitialEmptyLoaded(int loadedRadius, int generationEmptyRadius) {
            if (isTerminal()) {
                return;
            }
            if (shouldCancel()) {
                requestCancellation();
                return;
            }
            if (this.targetStatus == ChunkStatus.EMPTY) {
                finishSuccess();
                return;
            }
            boolean needsGeneration = needsGenerationAfterEmptyLoad();
            if (!needsGeneration) {
                buildAndSubmitGraph(false);
                return;
            }
            if (loadedRadius < generationEmptyRadius) {
                loadEmptyRadius(generationEmptyRadius, PHASE_BUILD_GRAPH_GENERATION);
                return;
            }
            buildAndSubmitGraph(true);
        }

        private boolean needsGenerationAfterEmptyLoad() {
            ChunkStatus centerPersisted = ((GenerationChunkHolder) this.cacheRawData[cacheIndex]).getPersistedStatus();
            if (centerPersisted == null || centerPersisted.isBefore(this.targetStatus)) {
                return true;
            }
            ChunkDependencies dependencies = ChunkPyramid.LOADING_PYRAMID
                    .getStepTo(this.targetStatus)
                    .accumulatedDependencies();
            int radius = dependencies.getRadius();
            for (int x = this.center.x - radius; x <= this.center.x + radius; x++) {
                int xIndex = cacheExtern.ga$getX(x);
                int distX = Math.abs(this.center.x - x);
                for (int z = this.center.z - radius; z <= this.center.z + radius; z++) {
                    int distance = Math.max(distX, Math.abs(this.center.z - z));
                    ChunkStatus required = dependencies.get(distance);
                    ChunkStatus persisted = ((GenerationChunkHolder) this.cacheRawData[xIndex + cacheExtern.ga$getZ(z)]).getPersistedStatus();
                    if (persisted == null || persisted.isBefore(required)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void loadEmptyRadius(int radius, int nextPhaseId) {
            ObjectArrayList<NodeSpec> nodes = new ObjectArrayList<>();
            ChunkStep emptyStep = ChunkPyramid.LOADING_PYRAMID.getStepTo(ChunkStatus.EMPTY);
            for (int x = this.center.x - radius; x <= this.center.x + radius; x++) {
                if (shouldCancel()) {
                    requestCancellation();
                    return;
                }
                int xIndex = cacheExtern.ga$getX(x);
                for (int z = this.center.z - radius; z <= this.center.z + radius; z++) {
                    GenerationChunkHolder holder = (GenerationChunkHolder) this.cacheRawData[xIndex + cacheExtern.ga$getZ(z)];
                    if (holder.getChunkIfPresentUnchecked(ChunkStatus.EMPTY) == null) {
                        nodes.add(new NodeSpec(holder, ChunkStatus.EMPTY, emptyStep, true));
                    }
                }
            }
            startPhase(nodes, null, nextPhaseId);
        }

        private void buildAndSubmitGraph(boolean needsGeneration) {
            if (isTerminal()) {
                return;
            }
            ChunkPyramid targetPyramid = needsGeneration ? ChunkPyramid.GENERATION_PYRAMID : ChunkPyramid.LOADING_PYRAMID;
            ChunkStep targetStep = targetPyramid.getStepTo(this.targetStatus);
            ObjectArrayList<NodeSpec> nodes = new ObjectArrayList<>();
            NodeIndex index = new NodeIndex(this.center, targetStep.getAccumulatedRadiusOf(ChunkStatus.EMPTY));
            Object[] statuses = statusRawList();
            int statusCount = statusRawListSize();
            for (int i = 0; i < statusCount; i++) {
                ChunkStatus status = (ChunkStatus) statuses[i];
                if (status == ChunkStatus.EMPTY || status.isAfter(this.targetStatus)) {
                    continue;
                }
                GAAdmissionDecision statusDecision = classify(status, this.runtime.config());
                if (!statusDecision.admitted()) {
                    fail(new IllegalStateException("GA v2 rejected graph status " + status + ": " + statusDecision.detail()));
                    return;
                }
                int radius = targetStep.getAccumulatedRadiusOf(status);
                for (int x = this.center.x - radius; x <= this.center.x + radius; x++) {
                    if (shouldCancel()) {
                        requestCancellation();
                        return;
                    }
                    int xIndex = cacheExtern.ga$getX(x);
                    for (int z = this.center.z - radius; z <= this.center.z + radius; z++) {
                        GenerationChunkHolder holder = (GenerationChunkHolder) this.cacheRawData[xIndex + cacheExtern.ga$getZ(z)];
                        if (holder.getChunkIfPresentUnchecked(status) != null) {
                            continue;
                        }
                        ChunkStep step = selectStep(status, holder, needsGeneration);
                        NodeSpec spec = new NodeSpec(holder, status, step, false);
                        nodes.add(spec);
                        index.put(x, z, status, nodes.size() - 1);
                    }
                }
            }
            if (nodes.isEmpty()) {
                finishSuccess();
                return;
            }
            startPhase(nodes, index, PHASE_FINISH_SUCCESS);
        }

        private ChunkStep selectStep(ChunkStatus status, GenerationChunkHolder holder, boolean graphCanGenerate) {
            ChunkStatus persisted = holder.getPersistedStatus();
            if (persisted == null) {
                throw new IllegalStateException("EMPTY dependency missing before scheduling " + status + " for " + holder.getPos());
            }
            if (status.isAfter(persisted)) {
                if (!graphCanGenerate) {
                    throw new IllegalStateException("Load-only chunk graph requires generation for " + status + " at " + holder.getPos());
                }
                return ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            }
            return ChunkPyramid.LOADING_PYRAMID.getStepTo(status);
        }

        private void startPhase(ObjectArrayList<NodeSpec> nodes, NodeIndex index, int nextPhaseId) {
            if (isTerminal()) {
                return;
            }
            int size = nodes.size();
            if (size == 0) {
                runNextPhase(nextPhaseId);
                return;
            }
            long buildStart = System.nanoTime();
            this.nextPhaseId = nextPhaseId;
            this.remaining.set(size);
            GAGenerationBatch batch = GAGenerationBatch.create(runtime);
            for (int i = 0; i < size; i++) {
                NodeSpec spec = nodes.get(i);
                GAChunkWorkKey key = workKey(spec.holder, spec.status);
                long handle = batch.addNode(key, taskClass(spec.status), new StepNodeBody(this, spec, key));
                spec.handle = handle;
                if (spec.emptyLoad) {
                    EMPTY_NODES_SUBMITTED.incrementAndGet();
                } else {
                    GRAPH_NODES_SUBMITTED.incrementAndGet();
                }
            }
            if (index != null) {
                for (int i = 0; i < size; i++) {
                    NodeSpec spec = nodes.get(i);
                    linkDependencies(batch, spec, index, nodes);
                    if (isTerminal()) {
                        return;
                    }
                }
            }
            BATCHES_BUILT.incrementAndGet();
            runtime.metrics().addBatchBuildNanos(System.nanoTime() - buildStart);
            int published = batch.submit();
            BATCHES_SUBMITTED.incrementAndGet();
            if (published == 0) {
                failNoProgress("phase has no ready roots");
            }
        }

        private void linkDependencies(GAGenerationBatch batch, NodeSpec spec, NodeIndex index, ObjectArrayList<NodeSpec> nodes) {
            linkLocalParentDependency(batch, spec, index, nodes);
            ChunkDependencies dependencies = spec.step.directDependencies();
            int radius = dependencies.getRadius();
            ChunkPos pos = spec.holder.getPos();
            for (int x = pos.x - radius; x <= pos.x + radius; x++) {
                int xIndex = cacheExtern.ga$getX(x);
                for (int z = pos.z - radius; z <= pos.z + radius; z++) {
                    int distance = pos.getChessboardDistance(x, z);
                    ChunkStatus required = dependencies.get(distance);
                    int dependencyIndex = index.get(x, z, required);
                    if (dependencyIndex >= 0) {
                        batch.addDependency(nodes.get(dependencyIndex).handle, spec.handle);
                        continue;
                    }
                    ChunkAccess existing = ((GenerationChunkHolder) this.cacheRawData[xIndex + cacheExtern.ga$getZ(z)])
                            .getChunkIfPresentUnchecked(required);
                    if (existing == null) {
                        CACHE_MISSES.incrementAndGet();
                        fail(new IllegalStateException(
                                "Missing dependency " + required + " at " + x + "," + z
                                        + " for " + spec.status + " at " + pos
                        ));
                        return;
                    }
                }
            }
        }

        private void linkLocalParentDependency(GAGenerationBatch batch, NodeSpec spec, NodeIndex index, ObjectArrayList<NodeSpec> nodes) {
            ChunkStatus parent = parentStatus(spec.status);
            if (parent == null) {
                return;
            }
            ChunkPos pos = spec.holder.getPos();
            int dependencyIndex = index.get(pos.x, pos.z, parent);
            if (dependencyIndex >= 0) {
                batch.addDependency(nodes.get(dependencyIndex).handle, spec.handle);
            }
        }

        private GATaskClass taskClass(ChunkStatus status) {
            if (status == ChunkStatus.NOISE) {
                return GATaskClass.CPU_NOISE;
            }
            GAAdmissionDecision statusDecision = classify(status, runtime.config());
            return statusDecision.taskClass();
        }

        private GAChunkWorkKey workKey(GenerationChunkHolder holder, ChunkStatus status) {
            ChunkPos pos = holder.getPos();
            int worldId = runtime.topology().worldId(chunkMap);
            return new GAChunkWorkKey(worldId, pos.x, pos.z, (byte) status.getIndex());
        }

        private void completePhaseNode() {
            NODES_COMPLETED.incrementAndGet();
            ACTIVE_NODES.updateAndGet(value -> Math.max(0L, value - 1L));
            if (remaining.decrementAndGet() == 0) {
                int nextPhase = this.nextPhaseId;
                this.nextPhaseId = PHASE_NONE;
                runNextPhase(nextPhase);
            }
        }

        private void failPhaseNode(Throwable throwable) {
            ACTIVE_NODES.updateAndGet(value -> Math.max(0L, value - 1L));
            fail(throwable);
        }

        private void cancelPhaseNode() {
            ACTIVE_NODES.updateAndGet(value -> Math.max(0L, value - 1L));
            requestCancellation();
        }

        private void runNextPhase(int phaseId) {
            if (phaseId == PHASE_NONE || isTerminal()) {
                return;
            }
            try {
                switch (phaseId) {
                    case PHASE_AFTER_INITIAL_EMPTY -> afterInitialEmptyLoaded(initialRadius, storedGenerationEmptyRadius);
                    case PHASE_BUILD_GRAPH_GENERATION -> buildAndSubmitGraph(true);
                    case PHASE_FINISH_SUCCESS -> finishSuccess();
                    default -> failNoProgress("unknown phase " + phaseId);
                }
            } catch (Throwable throwable) {
                fail(throwable);
            }
        }

        private void finishSuccess() {
            if (shouldCancel()) {
                requestCancellation();
                return;
            }
            if (terminal.compareAndSet(false, true)) {
                TASKS_COMPLETED.incrementAndGet();
                try {
                    taskAccess.ga$releaseClaim();
                } finally {
                    unregisterActiveRun();
                }
            }
        }

        private void finishCancelled() {
            if (terminal.compareAndSet(false, true)) {
                TASKS_CANCELLED.incrementAndGet();
                try {
                    taskAccess.ga$releaseClaim();
                } finally {
                    unregisterActiveRun();
                }
            }
        }

        private void fail(Throwable throwable) {
            if (terminal.compareAndSet(false, true)) {
                TASKS_FAILED.incrementAndGet();
                NODES_FAILED.incrementAndGet();
                recordFallback(GAMetrics.FallbackReason.TASK_FAILURE);
                runtime.metrics().recordFallback(GAMetrics.FallbackReason.TASK_FAILURE);
                GeneratorAccelerator.LOGGER.warn("GA v2 affinity graph failed for {} -> {}", this.center, this.targetStatus, throwable);
                fallbackToVanilla();
            }
        }

        private void failNoProgress(String reason) {
            STALLED_PHASES.incrementAndGet();
            fail(new IllegalStateException("GA v2 affinity graph stalled: " + reason
                    + ", center=" + this.center
                    + ", target=" + this.targetStatus
                    + ", remaining=" + this.remaining.get()
                    + ", phase=" + this.nextPhaseId));
        }

        private void fallbackToVanilla() {
            if (!fallbackQueued.compareAndSet(false, true)) {
                return;
            }
            TASKS_FALLBACK_TO_VANILLA.incrementAndGet();
            taskAccess.ga$setMarkedForCancellation(false);
            MixinChunkMapAccessor chunkMapAccess = (MixinChunkMapAccessor) (Object) this.chunkMap;
            Runnable resume = () -> chunkMapAccess.ga$runGenerationTask(this.task);
            try {
                chunkMapAccess.ga$getMainThreadExecutor().tell(resume);
            } catch (Throwable throwable) {
                try {
                    resume.run();
                } catch (Throwable resumeFailure) {
                    resumeFailure.addSuppressed(throwable);
                    this.task.markForCancellation();
                    this.taskAccess.ga$releaseClaim();
                    GeneratorAccelerator.LOGGER.warn("GA v2 affinity graph failed to return {} -> {} to vanilla", this.center, this.targetStatus, resumeFailure);
                }
            } finally {
                unregisterActiveRun();
            }
        }

        private boolean isTerminal() {
            return terminal.get();
        }

        private boolean shouldCancel() {
            return shutdownRequested || cancellationRequested.get() || taskAccess.ga$isMarkedForCancellation();
        }

        private void requestCancellation() {
            cancellationRequested.set(true);
            task.markForCancellation();
            finishCancelled();
        }

        private void registerActiveRun() {
            if (registered.compareAndSet(false, true)) {
                ACTIVE_RUNS.add(this);
                ACTIVE_TASKS.incrementAndGet();
            }
        }

        private void unregisterActiveRun() {
            if (registered.compareAndSet(true, false)) {
                ACTIVE_TASKS.decrementAndGet();
                ACTIVE_RUNS.remove(this);
            }
        }

        private static ChunkStatus parentStatus(ChunkStatus status) {
            return status == ChunkStatus.EMPTY ? null : status.getParent();
        }
    }

    private static final class NodeSpec {
        private final GenerationChunkHolder holder;
        private final ChunkStatus status;
        private final ChunkStep step;
        private final boolean emptyLoad;
        private long handle;

        private NodeSpec(GenerationChunkHolder holder, ChunkStatus status, ChunkStep step, boolean emptyLoad) {
            this.holder = holder;
            this.status = status;
            this.step = step;
            this.emptyLoad = emptyLoad;
        }
    }

    private static final class StepNodeBody implements GAChunkGraphArena.NodeBody {
        private final TaskRun run;
        private final NodeSpec spec;
        private final GAChunkWorkKey key;
        private boolean started;
        private boolean completed;
        private boolean activeRecorded;
        private boolean externalWaitRecorded;
        private ChunkResult<ChunkAccess> result;
        private Throwable throwable;

        private StepNodeBody(TaskRun run, NodeSpec spec, GAChunkWorkKey key) {
            this.run = run;
            this.spec = spec;
            this.key = key;
        }

        @Override
        public synchronized GAChunkGraphArena.RunState run(GAChunkGraphArena.ExecutionContext context) {
            if (completed || run.isTerminal()) {
                finishExternalWait();
                finishActiveNode();
                return GAChunkGraphArena.RunState.COMPLETE;
            }
            if (run.shouldCancel()) {
                completed = true;
                finishExternalWait();
                finishActiveNode();
                run.requestCancellation();
                return GAChunkGraphArena.RunState.COMPLETE;
            }
            if (spec.holder.getChunkIfPresentUnchecked(spec.status) != null) {
                completed = true;
                run.completePhaseNode();
                return GAChunkGraphArena.RunState.COMPLETE;
            }
            if (!started) {
                started = true;
                recordActiveNode();
                recordExternalWait();
                CompletableFuture<ChunkResult<ChunkAccess>> future = run.runtime.workTable().coalesce(key, () ->
                        GAChunkStatusPipeline.withInlineOnCurrentLane(() -> ((MixinGenerationChunkHolderAccessor) spec.holder)
                                .ga$applyStep(spec.step, run.chunkMap, run.cache))
                );
                future.whenComplete((nextResult, failure) -> {
                    synchronized (StepNodeBody.this) {
                        result = nextResult;
                        throwable = failure;
                    }
                    context.resume();
                });
                if (future.isDone()) {
                    return finishCompletedStep();
                }
                return GAChunkGraphArena.RunState.WAITING;
            }
            return finishCompletedStep();
        }

        private GAChunkGraphArena.RunState finishCompletedStep() {
            finishExternalWait();
            if (throwable != null) {
                completed = true;
                finishActiveNode();
                run.fail(propagate(throwable));
                return GAChunkGraphArena.RunState.COMPLETE;
            }
            if (result == null || !result.isSuccess()) {
                completed = true;
                finishActiveNode();
                run.fail(new IllegalStateException(result == null ? "Null chunk result" : result.getError()));
                return GAChunkGraphArena.RunState.COMPLETE;
            }
            if (result.orElse(null) == null) {
                completed = true;
                finishActiveNode();
                run.fail(new IllegalStateException("Chunk step completed with null chunk for " + spec.status + " at " + spec.holder.getPos()));
                return GAChunkGraphArena.RunState.COMPLETE;
            }
            completed = true;
            activeRecorded = false;
            run.completePhaseNode();
            return GAChunkGraphArena.RunState.COMPLETE;
        }

        private void recordActiveNode() {
            activeRecorded = true;
            ACTIVE_NODES.incrementAndGet();
        }

        private void finishActiveNode() {
            if (activeRecorded) {
                activeRecorded = false;
                ACTIVE_NODES.updateAndGet(value -> Math.max(0L, value - 1L));
            }
        }

        private void recordExternalWait() {
            externalWaitRecorded = true;
            run.runtime.metrics().incrementExternalFutureWait();
        }

        private void finishExternalWait() {
            if (externalWaitRecorded) {
                externalWaitRecorded = false;
                run.runtime.metrics().decrementExternalFutureWait();
            }
        }

        private static RuntimeException propagate(Throwable throwable) {
            if (throwable instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            return new RuntimeException(throwable);
        }
    }

    private static Object[] statusRawList() {
        return StatusListHolder.STATUS_RAW_LIST;
    }

    private static int statusRawListSize() {
        return StatusListHolder.STATUS_RAW_LIST_SIZE;
    }

    private static final class StatusListHolder {
        private static final ObjectArrayList<ChunkStatus> STATUS_LIST = new ObjectArrayList<>(ChunkStatus.getStatusList());
        private static final Object[] STATUS_RAW_LIST = STATUS_LIST.elements();
        private static final int STATUS_RAW_LIST_SIZE = STATUS_LIST.size();
    }

    private static final class NodeIndex {
        private final int minX;
        private final int minZ;
        private final int width;
        private final int statusCount;
        private final int[] indices;

        private NodeIndex(ChunkPos center, int radius) {
            this.minX = center.x - radius;
            this.minZ = center.z - radius;
            this.width = radius * 2 + 1;
            this.statusCount = statusRawListSize();
            this.indices = new int[this.width * this.width * this.statusCount];
            java.util.Arrays.fill(this.indices, -1);
        }

        private void put(int x, int z, ChunkStatus status, int nodeIndex) {
            int offset = offset(x, z, status);
            if (offset >= 0) {
                indices[offset] = nodeIndex;
            }
        }

        private int get(int x, int z, ChunkStatus status) {
            int offset = offset(x, z, status);
            return offset < 0 ? -1 : indices[offset];
        }

        private int offset(int x, int z, ChunkStatus status) {
            int localX = x - minX;
            int localZ = z - minZ;
            if (localX < 0 || localZ < 0 || localX >= width || localZ >= width) {
                return -1;
            }
            return ((localX * width) + localZ) * statusCount + status.getIndex();
        }
    }
}
