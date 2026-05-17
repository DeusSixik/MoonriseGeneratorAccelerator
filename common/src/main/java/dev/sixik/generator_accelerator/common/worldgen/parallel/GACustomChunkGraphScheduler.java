package dev.sixik.generator_accelerator.common.worldgen.parallel;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$StaticCache2DExtern;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinChunkGenerationTaskAccessor;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DAG-first chunk generation runner. It keeps vanilla GenerationChunkHolder
 * future ownership, but replaces ChunkGenerationTask's global layer barrier
 * with per-node dependency dispatch over GA-owned lanes.
 */
public final class GACustomChunkGraphScheduler {
    private static final ObjectArrayList<ChunkStatus> STATUS_LIST = new ObjectArrayList<>(ChunkStatus.getStatusList());
    private static final Object[] STATUS_RAW_LIST = STATUS_LIST.elements();
    private static final int STATUS_RAW_LIST_SIZE = STATUS_LIST.size();

    private static final GAConfig CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    private static final boolean ENABLED = booleanProperty(
            "ga.chunkGraph.enabled",
            CONFIG.enableCustomChunkGraphScheduler
    );
    private static final boolean EAGER_EMPTY_RADIUS = booleanProperty(
            "ga.chunkGraph.eagerEmptyRadius",
            CONFIG.chunkGraphEagerEmptyRadius
    );
    private static final boolean COALESCE_IN_FLIGHT = booleanProperty(
            "ga.chunkGraph.coalesceInFlight",
            CONFIG.chunkGraphCoalesceInFlight
    );
    private static final int IN_FLIGHT_BUCKETS = nextPowerOfTwo(Math.max(1024, intProperty(
            "ga.chunkGraph.inFlightBuckets",
            CONFIG.chunkGraphInFlightBuckets
    )));
    private static final int IN_FLIGHT_BUCKET_MASK = IN_FLIGHT_BUCKETS - 1;
    private static final int IN_FLIGHT_WAYS = Math.max(1, intProperty(
            "ga.chunkGraph.inFlightWays",
            CONFIG.chunkGraphInFlightWays
    ));

    private static final AtomicReferenceArray<InFlightStep> IN_FLIGHT_STEPS =
            new AtomicReferenceArray<>(IN_FLIGHT_BUCKETS * IN_FLIGHT_WAYS);
    private static final AtomicLong IN_FLIGHT_STEP_COUNT = new AtomicLong();
    private static final AtomicLong TASKS_SUBMITTED = new AtomicLong();
    private static final AtomicLong TASKS_COMPLETED = new AtomicLong();
    private static final AtomicLong TASKS_FAILED = new AtomicLong();
    private static final AtomicLong TASKS_CANCELLED = new AtomicLong();
    private static final AtomicLong EMPTY_NODES_SUBMITTED = new AtomicLong();
    private static final AtomicLong GRAPH_NODES_SUBMITTED = new AtomicLong();
    private static final AtomicLong NODES_COMPLETED = new AtomicLong();
    private static final AtomicLong NODES_FAILED = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong NODES_ALREADY_COMPLETE = new AtomicLong();
    private static final AtomicLong NODES_JOINED_IN_FLIGHT = new AtomicLong();
    private static final AtomicLong STEP_EXECUTIONS_SUBMITTED = new AtomicLong();
    private static final AtomicLong STEP_EXECUTIONS_COMPLETED = new AtomicLong();
    private static final AtomicLong STEP_EXECUTIONS_FAILED = new AtomicLong();
    private static final AtomicLong MAX_IN_FLIGHT_STEPS = new AtomicLong();
    private static final AtomicLong IN_FLIGHT_COLLISIONS = new AtomicLong();
    private static final AtomicLong GENERATION_GRAPHS = new AtomicLong();
    private static final AtomicLong LOADING_GRAPHS = new AtomicLong();

    private GACustomChunkGraphScheduler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean schedule(ChunkMap chunkMap, ChunkGenerationTask task) {
        if (!ENABLED) {
            return false;
        }
        TASKS_SUBMITTED.incrementAndGet();
        new TaskRun(chunkMap, task).start();
        return true;
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ENABLED);
        out.put("eagerEmptyRadius", EAGER_EMPTY_RADIUS);
        out.put("coalesceInFlight", COALESCE_IN_FLIGHT);
        out.put("inFlightBuckets", IN_FLIGHT_BUCKETS);
        out.put("inFlightWays", IN_FLIGHT_WAYS);
        out.put("tasksSubmitted", TASKS_SUBMITTED.get());
        out.put("tasksCompleted", TASKS_COMPLETED.get());
        out.put("tasksFailed", TASKS_FAILED.get());
        out.put("tasksCancelled", TASKS_CANCELLED.get());
        out.put("emptyNodesSubmitted", EMPTY_NODES_SUBMITTED.get());
        out.put("graphNodesSubmitted", GRAPH_NODES_SUBMITTED.get());
        out.put("nodesCompleted", NODES_COMPLETED.get());
        out.put("nodesFailed", NODES_FAILED.get());
        out.put("cacheMisses", CACHE_MISSES.get());
        out.put("nodesAlreadyComplete", NODES_ALREADY_COMPLETE.get());
        out.put("nodesJoinedInFlight", NODES_JOINED_IN_FLIGHT.get());
        out.put("stepExecutionsSubmitted", STEP_EXECUTIONS_SUBMITTED.get());
        out.put("stepExecutionsCompleted", STEP_EXECUTIONS_COMPLETED.get());
        out.put("stepExecutionsFailed", STEP_EXECUTIONS_FAILED.get());
        out.put("inFlightSteps", IN_FLIGHT_STEP_COUNT.get());
        out.put("maxInFlightSteps", MAX_IN_FLIGHT_STEPS.get());
        out.put("inFlightCollisions", IN_FLIGHT_COLLISIONS.get());
        out.put("generationGraphs", GENERATION_GRAPHS.get());
        out.put("loadingGraphs", LOADING_GRAPHS.get());
        return out;
    }

    public static void resetMetrics() {
        TASKS_SUBMITTED.set(0L);
        TASKS_COMPLETED.set(0L);
        TASKS_FAILED.set(0L);
        TASKS_CANCELLED.set(0L);
        EMPTY_NODES_SUBMITTED.set(0L);
        GRAPH_NODES_SUBMITTED.set(0L);
        NODES_COMPLETED.set(0L);
        NODES_FAILED.set(0L);
        CACHE_MISSES.set(0L);
        NODES_ALREADY_COMPLETE.set(0L);
        NODES_JOINED_IN_FLIGHT.set(0L);
        STEP_EXECUTIONS_SUBMITTED.set(0L);
        STEP_EXECUTIONS_COMPLETED.set(0L);
        STEP_EXECUTIONS_FAILED.set(0L);
        MAX_IN_FLIGHT_STEPS.set(0L);
        IN_FLIGHT_COLLISIONS.set(0L);
        GENERATION_GRAPHS.set(0L);
        LOADING_GRAPHS.set(0L);
    }

    private static GAScheduler.Lane laneFor(ChunkStatus status) {
        if (status == ChunkStatus.EMPTY) {
            // EMPTY loads only submit/load holder futures; do not serialize them behind commit work.
            return GAScheduler.Lane.WORKSPACE;
        }
        if (status == ChunkStatus.FULL) {
            return GAScheduler.Lane.COMMIT;
        }
        if (status == ChunkStatus.FEATURES || status == ChunkStatus.SPAWN) {
            return GAScheduler.Lane.TRANSACTIONAL;
        }
        if (status == ChunkStatus.NOISE) {
            return GAScheduler.Lane.NOISE;
        }
        return GAScheduler.Lane.WORKSPACE;
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

    private static int nextPowerOfTwo(int value) {
        int highest = Integer.highestOneBit(value);
        if (highest == value) {
            return value;
        }
        return highest >= (1 << 30) ? 1 << 30 : highest << 1;
    }

    private static final class TaskRun {

        private static final int PHASE_NONE = 0;
        private static final int PHASE_AFTER_INITIAL_EMPTY = 1;
        private static final int PHASE_BUILD_GRAPH_GENERATION = 2;
        private static final int PHASE_FINISH_SUCCESS = 3;

        private final ChunkMap chunkMap;
        private final ChunkGenerationTask task;
        private final MixinChunkGenerationTaskAccessor taskAccess;
        private final ChunkPos center;
        private final StaticCache2D<GenerationChunkHolder> cache;
        private final GA$StaticCache2DExtern<GenerationChunkHolder> cacheExtern;

        private final Object[] cacheRawData;
        private final int cacheIndex;

        private final ChunkStatus targetStatus;
        private final ConcurrentLinkedQueue<Node> ready = new ConcurrentLinkedQueue<>();
        private final AtomicInteger drainWork = new AtomicInteger();
        private final AtomicInteger remaining = new AtomicInteger();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private volatile int nextPhaseId = PHASE_NONE;
        private int storedGenerationEmptyRadius;
        private int initialRadius;

        private TaskRun(ChunkMap chunkMap, ChunkGenerationTask task) {
            this.chunkMap = chunkMap;
            this.task = task;
            this.taskAccess = (MixinChunkGenerationTaskAccessor) (Object) task;
            this.center = this.taskAccess.ga$getPos();
            this.cache = this.taskAccess.ga$getCache();
            this.targetStatus = task.targetStatus;

            cacheExtern = GA$StaticCache2DExtern.get(cache);
            cacheIndex = cacheExtern.ga$getIndex(center.x, center.z);
            cacheRawData = cacheExtern.ga$getRawData();
        }

        private void start() {
            try {
                if (this.taskAccess.ga$isMarkedForCancellation()) {
                    this.finishCancelled();
                    return;
                }
                int loadingEmptyRadius = ChunkPyramid.LOADING_PYRAMID
                        .getStepTo(this.targetStatus)
                        .getAccumulatedRadiusOf(ChunkStatus.EMPTY);
                int generationEmptyRadius = ChunkPyramid.GENERATION_PYRAMID
                        .getStepTo(this.targetStatus)
                        .getAccumulatedRadiusOf(ChunkStatus.EMPTY);
                int initialRadius = EAGER_EMPTY_RADIUS ? generationEmptyRadius : loadingEmptyRadius;
                this.storedGenerationEmptyRadius = generationEmptyRadius;
                this.initialRadius = initialRadius;
                this.loadEmptyRadius(initialRadius, PHASE_AFTER_INITIAL_EMPTY);
            } catch (Throwable throwable) {
                this.fail(throwable);
            }
        }

        private void afterInitialEmptyLoaded(int loadedRadius, int generationEmptyRadius) {
            if (this.isTerminal()) {
                return;
            }
            if (this.taskAccess.ga$isMarkedForCancellation()) {
                this.finishCancelled();
                return;
            }
            if (this.targetStatus == ChunkStatus.EMPTY) {
                this.finishSuccess();
                return;
            }

            boolean needsGeneration = this.needsGenerationAfterEmptyLoad();
            if (!needsGeneration) {
                this.buildAndSubmitGraph(false);
                return;
            }

            if (loadedRadius < generationEmptyRadius) {
                this.loadEmptyRadius(generationEmptyRadius, PHASE_BUILD_GRAPH_GENERATION);
                return;
            }
            this.buildAndSubmitGraph(true);
        }

        private boolean needsGenerationAfterEmptyLoad() {
            ChunkStatus centerPersisted = ((GenerationChunkHolder)this.cacheRawData[cacheIndex]).getPersistedStatus();
            if (centerPersisted == null || centerPersisted.isBefore(this.targetStatus)) {
                return true;
            }

            ChunkDependencies dependencies = ChunkPyramid.LOADING_PYRAMID
                    .getStepTo(this.targetStatus)
                    .accumulatedDependencies();
            int radius = dependencies.getRadius();

            int minX = this.center.x - radius;
            int maxX = this.center.x + radius;
            int minZ = this.center.z - radius;
            int maxZ = this.center.z + radius;

            for (int x = minX; x <= maxX; x++) {
                int xIndex = cacheExtern.ga$getX(x);
                int baseIndex = xIndex + cacheExtern.ga$getZ(minZ);

                int distX = Math.abs(this.center.x - x);

                for (int z = minZ; z <= maxZ; z++) {
                    int finalIndex = baseIndex + (z - minZ);
                    int distance = Math.max(distX, Math.abs(this.center.z - z));

                    ChunkStatus required = dependencies.get(distance);
                    ChunkStatus persisted = ((GenerationChunkHolder)this.cacheRawData[finalIndex]).getPersistedStatus();

                    if (persisted == null || persisted.isBefore(required)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void loadEmptyRadius(int radius, int nextPhaseId) {
            ObjectArrayList<Node> nodes = new ObjectArrayList<>();
            ChunkStep emptyStep = ChunkPyramid.LOADING_PYRAMID.getStepTo(ChunkStatus.EMPTY);

            int minX = this.center.x - radius;
            int maxX = this.center.x + radius;
            int minZ = this.center.z - radius;
            int maxZ = this.center.z + radius;

            for (int x = minX; x <= maxX; x++) {
                int xIndex = cacheExtern.ga$getX(x);
                int baseIndex = xIndex + cacheExtern.ga$getZ(minZ);

                for (int z = minZ; z <= maxZ; z++) {
                    int finalIndex = baseIndex + (z - minZ);

                    GenerationChunkHolder holder = (GenerationChunkHolder) this.cacheRawData[finalIndex];
                    if (holder.getChunkIfPresentUnchecked(ChunkStatus.EMPTY) != null) {
                        continue;
                    }
                    nodes.add(new Node(holder, ChunkStatus.EMPTY, emptyStep, true));
                }
            }
            this.startPhase(nodes.elements(), nodes.size(), nextPhaseId);
        }

        private void buildAndSubmitGraph(boolean needsGeneration) {
            if (this.isTerminal()) {
                return;
            }
            if (this.taskAccess.ga$isMarkedForCancellation()) {
                this.finishCancelled();
                return;
            }

            ChunkPyramid targetPyramid = needsGeneration ? ChunkPyramid.GENERATION_PYRAMID : ChunkPyramid.LOADING_PYRAMID;
            ChunkStep targetStep = targetPyramid.getStepTo(this.targetStatus);
            ObjectArrayList<Node> nodes = new ObjectArrayList<>();
            NodeIndex index = new NodeIndex(this.center, targetStep.getAccumulatedRadiusOf(ChunkStatus.EMPTY));

            for (int i = 0; i < STATUS_RAW_LIST_SIZE; i++) {
                ChunkStatus status = (ChunkStatus) STATUS_RAW_LIST[i];
                if (status == ChunkStatus.EMPTY || status.isAfter(this.targetStatus)) {
                    continue;
                }
                int radius = targetStep.getAccumulatedRadiusOf(status);

                int minX = this.center.x - radius;
                int maxX = this.center.x + radius;
                int minZ = this.center.z - radius;
                int maxZ = this.center.z + radius;

                for (int x = minX; x <= maxX; x++) {
                    int xIndex = cacheExtern.ga$getX(x);
                    int baseIndex = xIndex + cacheExtern.ga$getZ(minZ);

                    for (int z = minZ; z <= maxZ; z++) {
                        int finalIndex = baseIndex + (z - minZ);

                        GenerationChunkHolder holder = (GenerationChunkHolder) this.cacheRawData[finalIndex];
                        if (holder.getChunkIfPresentUnchecked(status) != null) {
                            continue;
                        }

                        ChunkStep step = this.selectStep(status, holder, needsGeneration);
                        Node node = new Node(holder, status, step, false);
                        nodes.add(node);
                        index.put(x, z, status, node);
                    }
                }
            }

            if (nodes.isEmpty()) {
                this.finishSuccess();
                return;
            }

            final Object[] elements = nodes.elements();
            final int size = nodes.size();
            for (int i = 0; i < size; i++) {
                Node node = (Node) elements[i];
                this.linkDependencies(node, index);
                if (this.isTerminal()) {
                    return;
                }
            }

            if (needsGeneration) {
                GENERATION_GRAPHS.incrementAndGet();
            } else {
                LOADING_GRAPHS.incrementAndGet();
            }
            this.startPhase(elements, size, PHASE_FINISH_SUCCESS);
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

        private void linkDependencies(Node node, NodeIndex index) {
            ChunkDependencies dependencies = node.step.directDependencies();
            int radius = dependencies.getRadius();
            ChunkPos pos = node.holder.getPos();
            for (int x = pos.x - radius; x <= pos.x + radius; x++) {
                int xIndex = cacheExtern.ga$getX(x);
                for (int z = pos.z - radius; z <= pos.z + radius; z++) {
                    int distance = pos.getChessboardDistance(x, z);
                    ChunkStatus required = dependencies.get(distance);
                    Node dependency = index.get(x, z, required);
                    if (dependency != null) {
                        dependency.addDependent(node);
                        node.incrementPendingDependencies();
                        continue;
                    }
                    int zIndex = cacheExtern.ga$getZ(z);
                    ChunkAccess existing = ((GenerationChunkHolder)this.cacheRawData[xIndex + zIndex]).getChunkIfPresentUnchecked(required);
                    if (existing == null) {
                        CACHE_MISSES.incrementAndGet();
                        this.fail(new IllegalStateException(
                                "Missing dependency " + required + " at " + x + "," + z
                                        + " for " + node.status + " at " + pos
                        ));
                        return;
                    }
                }
            }
        }

        private void startPhase(Object[] nodes, int size, int nextPhaseId) {
            if (this.isTerminal()) {
                return;
            }
            if (size == 0) {
                this.runNextPhase(nextPhaseId);
                return;
            }

            this.nextPhaseId = nextPhaseId;

            this.remaining.set(size);
            for (int i = 0; i < size; i++) {
                Node node = (Node) nodes[i];
                if (node.pendingDependencies() == 0) {
                    this.ready.add(node);
                }
            }

            this.drainReady();
        }

        private void drainReady() {
            if (this.drainWork.getAndIncrement() != 0) {
                return;
            }

            int missed = 1;
            do {
                if (this.isTerminal()) {
                    this.ready.clear();
                    this.drainWork.addAndGet(-missed);
                    return;
                }

                Node node;
                while ((node = this.ready.poll()) != null) {
                    this.submit(node);
                    if (this.isTerminal()) {
                        this.ready.clear();
                        break;
                    }
                }

                missed = this.drainWork.addAndGet(-missed);
            } while (missed != 0);
        }

        private void submit(Node node) {
            if (this.isTerminal()) {
                return;
            }
            if (this.taskAccess.ga$isMarkedForCancellation()) {
                this.finishCancelled();
                return;
            }
            if (node.isComplete()) {
                NODES_ALREADY_COMPLETE.incrementAndGet();
                this.completeNode(node);
                return;
            }

            if (node.emptyLoad) {
                EMPTY_NODES_SUBMITTED.incrementAndGet();
            } else {
                GRAPH_NODES_SUBMITTED.incrementAndGet();
            }

            CompletableFuture<ChunkResult<ChunkAccess>> future = this.scheduleOrJoinStep(node);
            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    this.fail(throwable);
                    return;
                }
                if (result == null || !result.isSuccess()) {
                    this.fail(new IllegalStateException(result == null ? "Null chunk result" : result.getError()));
                    return;
                }
                if (result.orElse(null) == null) {
                    this.fail(new IllegalStateException("Chunk step completed with null chunk for " + node.status + " at " + node.holder.getPos()));
                    return;
                }
                this.completeNode(node);
            });
        }

        private CompletableFuture<ChunkResult<ChunkAccess>> scheduleOrJoinStep(Node node) {
            if (!COALESCE_IN_FLIGHT) {
                return this.scheduleStep(node);
            }

            int statusIndex = node.status.getIndex();
            int hash = stepHash(node.holder, statusIndex);
            int baseIndex = (hash & IN_FLIGHT_BUCKET_MASK) * IN_FLIGHT_WAYS;
            for (;;) {
                int emptyIndex = -1;
                for (int way = 0; way < IN_FLIGHT_WAYS; way++) {
                    int index = baseIndex + way;
                    InFlightStep current = IN_FLIGHT_STEPS.get(index);
                    if (current == null) {
                        if (emptyIndex < 0) {
                            emptyIndex = index;
                        }
                        continue;
                    }
                    if (current.matches(node.holder, statusIndex)) {
                        NODES_JOINED_IN_FLIGHT.incrementAndGet();
                        return current.future;
                    }
                }

                if (emptyIndex < 0) {
                    IN_FLIGHT_COLLISIONS.incrementAndGet();
                    return this.scheduleStep(node);
                }

                CompletableFuture<ChunkResult<ChunkAccess>> shared = new CompletableFuture<>();
                InFlightStep created = new InFlightStep(node.holder, statusIndex, emptyIndex, shared);
                if (IN_FLIGHT_STEPS.compareAndSet(emptyIndex, null, created)) {
                    long count = IN_FLIGHT_STEP_COUNT.incrementAndGet();
                    updateMax(MAX_IN_FLIGHT_STEPS, count);
                    shared.whenComplete((result, throwable) -> {
                        if (IN_FLIGHT_STEPS.compareAndSet(created.slotIndex, created, null)) {
                            IN_FLIGHT_STEP_COUNT.decrementAndGet();
                        }
                        if (throwable == null && result != null && result.isSuccess()) {
                            STEP_EXECUTIONS_COMPLETED.incrementAndGet();
                        } else {
                            STEP_EXECUTIONS_FAILED.incrementAndGet();
                        }
                    });
                    this.startSharedStep(node, shared);
                    return shared;
                }
            }
        }

        private CompletableFuture<ChunkResult<ChunkAccess>> scheduleStep(Node node) {
            CompletableFuture<ChunkResult<ChunkAccess>> shared = new CompletableFuture<>();
            shared.whenComplete((result, throwable) -> {
                if (throwable == null && result != null && result.isSuccess()) {
                    STEP_EXECUTIONS_COMPLETED.incrementAndGet();
                } else {
                    STEP_EXECUTIONS_FAILED.incrementAndGet();
                }
            });
            this.startSharedStep(node, shared);
            return shared;
        }

        private void startSharedStep(Node node, CompletableFuture<ChunkResult<ChunkAccess>> shared) {
            STEP_EXECUTIONS_SUBMITTED.incrementAndGet();
            CompletableFuture<Void> submitted = GAScheduler.supplyAsync(laneFor(node.status), () -> {
                CompletableFuture<ChunkResult<ChunkAccess>> future = GAChunkStatusPipeline.withInlineOnCurrentLane(
                        () -> ((MixinGenerationChunkHolderAccessor) node.holder)
                                .ga$applyStep(node.step, this.chunkMap, this.cache)
                );
                if (future == null) {
                    throw new NullPointerException("Chunk step returned null future for " + node.status + " at " + node.holder.getPos());
                }
                future.whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        shared.completeExceptionally(throwable);
                    } else {
                        shared.complete(result);
                    }
                });
                return null;
            });
            submitted.whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    shared.completeExceptionally(throwable);
                }
            });
        }

        private void completeNode(Node node) {
            if (this.isTerminal()) {
                return;
            }

            NODES_COMPLETED.incrementAndGet();
            ObjectArrayList<Node> dependents = node.dependents;
            if (dependents != null) {
                final Object[] elements = dependents.elements();
                final int size = dependents.size();

                for (int i = 0; i < size; i++) {
                    Node dependent = (Node) elements[i];
                    dependent.decrementPendingDependencies();
                    if (dependent.pendingDependencies() == 0) {
                        this.ready.add(dependent);
                    }
                }
            }

            if (this.remaining.decrementAndGet() == 0) {
                int nextPhase = this.nextPhaseId;
                this.nextPhaseId = PHASE_NONE;
                this.runNextPhase(nextPhase);
                return;
            }
            this.drainReady();
        }

        private void runNextPhase(int phaseId) {
            if (phaseId == PHASE_NONE || this.isTerminal()) {
                return;
            }
            try {
                switch (phaseId) {
                    case PHASE_AFTER_INITIAL_EMPTY:
                        this.afterInitialEmptyLoaded(initialRadius, this.storedGenerationEmptyRadius);
                        break;
                    case PHASE_BUILD_GRAPH_GENERATION:
                        this.buildAndSubmitGraph(true);
                        break;
                    case PHASE_FINISH_SUCCESS:
                        this.finishSuccess();
                        break;
                }
            } catch (Throwable throwable) {
                this.fail(throwable);
            }
        }

        private void finishSuccess() {
            if (this.taskAccess.ga$isMarkedForCancellation()) {
                this.finishCancelled();
                return;
            }
            if (this.terminal.compareAndSet(false, true)) {
                TASKS_COMPLETED.incrementAndGet();
                this.taskAccess.ga$releaseClaim();
            }
        }

        private void finishCancelled() {
            if (this.terminal.compareAndSet(false, true)) {
                TASKS_CANCELLED.incrementAndGet();
                this.taskAccess.ga$releaseClaim();
            }
        }

        private void fail(Throwable throwable) {
            if (this.terminal.compareAndSet(false, true)) {
                TASKS_FAILED.incrementAndGet();
                NODES_FAILED.incrementAndGet();
                GeneratorAccelerator.LOGGER.warn(
                        "GA custom chunk graph failed for {} -> {}",
                        this.center,
                        this.targetStatus,
                        throwable
                );
                this.task.markForCancellation();
                this.taskAccess.ga$releaseClaim();
            }
        }

        private boolean isTerminal() {
            return this.terminal.get();
        }

        private static void updateMax(AtomicLong value, long next) {
            long current;
            do {
                current = value.get();
                if (next <= current) {
                    return;
                }
            } while (!value.compareAndSet(current, next));
        }

        private static int stepHash(GenerationChunkHolder holder, int statusIndex) {
            int hash = System.identityHashCode(holder);
            hash ^= statusIndex * 0x85ebca6b;
            hash ^= hash >>> 16;
            hash *= 0x7feb352d;
            hash ^= hash >>> 15;
            return hash;
        }
    }

    private static final class Node {
        private static final AtomicIntegerFieldUpdater<Node> PENDING_DEPENDENCIES =
                AtomicIntegerFieldUpdater.newUpdater(Node.class, "pendingDependencies");

        private final GenerationChunkHolder holder;
        private final ChunkStatus status;
        private final ChunkStep step;
        private final boolean emptyLoad;
        private volatile int pendingDependencies;
        private ObjectArrayList<Node> dependents;

        private Node(GenerationChunkHolder holder, ChunkStatus status, ChunkStep step, boolean emptyLoad) {
            this.holder = holder;
            this.status = status;
            this.step = step;
            this.emptyLoad = emptyLoad;
        }

        private boolean isComplete() {
            return this.holder.getChunkIfPresentUnchecked(this.status) != null;
        }

        private int pendingDependencies() {
            return this.pendingDependencies;
        }

        private void incrementPendingDependencies() {
            this.pendingDependencies++;
        }

        private int decrementPendingDependencies() {
            return PENDING_DEPENDENCIES.decrementAndGet(this);
        }

        private void addDependent(Node dependent) {
            ObjectArrayList<Node> current = this.dependents;
            if (current == null) {
                current = new ObjectArrayList<>(4);
                this.dependents = current;
            }
            current.add(dependent);
        }
    }

    private static final class NodeIndex {
        private final int minX;
        private final int minZ;
        private final int width;
        private final int statusCount;
        private final Node[] nodes;

        private NodeIndex(ChunkPos center, int radius) {
            this.minX = center.x - radius;
            this.minZ = center.z - radius;
            this.width = radius * 2 + 1;
            this.statusCount = STATUS_RAW_LIST_SIZE;
            this.nodes = new Node[this.width * this.width * this.statusCount];
        }

        private void put(int x, int z, ChunkStatus status, Node node) {
            int offset = this.offset(x, z, status);
            if (offset >= 0) {
                this.nodes[offset] = node;
            }
        }

        private Node get(int x, int z, ChunkStatus status) {
            int offset = this.offset(x, z, status);
            return offset < 0 ? null : this.nodes[offset];
        }

        private int offset(int x, int z, ChunkStatus status) {
            int localX = x - this.minX;
            int localZ = z - this.minZ;
            if (localX < 0 || localZ < 0 || localX >= this.width || localZ >= this.width) {
                return -1;
            }
            return ((localX * this.width) + localZ) * this.statusCount + status.getIndex();
        }
    }

    private static final class InFlightStep {
        private final GenerationChunkHolder holder;
        private final int statusIndex;
        private final int slotIndex;
        private final CompletableFuture<ChunkResult<ChunkAccess>> future;

        private InFlightStep(
                GenerationChunkHolder holder,
                int statusIndex,
                int slotIndex,
                CompletableFuture<ChunkResult<ChunkAccess>> future
        ) {
            this.holder = holder;
            this.statusIndex = statusIndex;
            this.slotIndex = slotIndex;
            this.future = future;
        }

        private boolean matches(GenerationChunkHolder holder, int statusIndex) {
            return this.holder == holder && this.statusIndex == statusIndex;
        }
    }
}
