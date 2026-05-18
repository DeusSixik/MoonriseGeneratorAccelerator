package dev.sixik.generator_accelerator.config;


import net.shadowking21.shadowconfig.annotation.ConfigComment;

public class GAConfig {

    @ConfigComment("Enable Aquifer Patch")
    public boolean enableAquiferPatch = true;

    @ConfigComment("Enable Beardifier Patch")
    public boolean enableBeardifierPatch = true;

    @ConfigComment("Enable Biome Patch")
    public boolean enableBiomePatch = true;

    @ConfigComment("Enable Blender Patch")
    public boolean enableBlenderPatch = true;

    public boolean enableDensityCompilerPatch = true;

    @ConfigComment("Enable experimental Density Function Compiler OpenCL runtime. Disabled by default; current CPU/JNI DFC path is the stable fast path.")
    public boolean enableDensityCompilerOpenCL = false;

    public boolean enableFeaturesPatch = true;

    public boolean enableFlatBlockStructurePatch = true;

    public boolean enableHeightmapPatch = true;

    public boolean enableNoisePatch = true;

//    @ConfigComment("Enable Noise Native Patch\nATTENTION!\nWhen enabled, the generation will be radically different from the vanilla system. For example, where there used to be a plain, there's now a desert, etc.")
    public boolean enableNoiseNativePatch = false;

    public boolean enablePalettedContainerPatch = true;

    public boolean enableStructuresPatch = true;

    public boolean enableSurfacePatch = true;

    @ConfigComment("GA scheduler noise lane workers. 0 = auto.")
    public int schedulerNoiseWorkers = 0;

    @ConfigComment("GA scheduler compile/warmup lane workers. 0 = auto.")
    public int schedulerCompileWorkers = 0;

    @ConfigComment("GA scheduler workspace lane workers. 0 = auto.")
    public int schedulerWorkspaceWorkers = 0;

    @ConfigComment("GA scheduler transactional lane workers. 0 = auto.")
    public int schedulerTransactionalWorkers = 0;

    @ConfigComment("GA scheduler serial lane workers. Values above 1 are clamped to 1 for ordered work.")
    public int schedulerSerialWorkers = 1;

    @ConfigComment("GA scheduler commit/finalize lane workers. Keep 1 for deterministic chunk writes.")
    public int schedulerCommitWorkers = 1;

    @ConfigComment("Soft max queued tasks per lane. 0 = unlimited.")
    public int schedulerMaxQueuedTasks = 0;

    @ConfigComment("Adaptive worldgen governor CPU target for compile throttling under generation pressure.")
    public double schedulerCpuTarget = 0.85D;

    @ConfigComment("Throttle new worldgen compute tasks when commit lane backlog reaches this many queued/running tasks. 0 = disabled.")
    public int schedulerCommitBacklogThrottleThreshold = 64;

    @ConfigComment("Throttle new worldgen compute tasks when cross-chunk mailbox backlog reaches this many queued commands. 0 = disabled.")
    public int schedulerMailboxBacklogThrottleThreshold = 8192;

    @ConfigComment("Throttle new worldgen compute tasks when heap usage ratio reaches this value. 0 = disabled.")
    public double schedulerHeapPressureTarget = 0.92D;

    @ConfigComment("Enable GA async chunk-status dispatch. This moves synchronous vanilla generation stages onto GA scheduler lanes.")
    public boolean enableChunkStatusPipeline = true;

    @ConfigComment("Enable striped CAS guards around cross-chunk chunk-status writers.")
    public boolean chunkPipelineGuards = true;

    @ConfigComment("Run ChunkGenerator.createBiomes on the GA workspace lane instead of the vanilla background executor.")
    public boolean chunkPipelineBiomes = true;

    public boolean chunkPipelineNoise = true;

    public boolean chunkPipelineStructureStarts = true;

    public boolean chunkPipelineStructureReferences = true;

    public boolean chunkPipelineSurface = true;

    public boolean chunkPipelineCarvers = true;

    public boolean chunkPipelineFeatures = true;

    public boolean chunkPipelineSpawn = true;

    @ConfigComment("Minimum guarded write radius for feature generation in chunks.")
    public int chunkPipelineFeatureMinWriteRadius = 1;

    @ConfigComment("Minimum guarded write radius for spawn generation in chunks.")
    public int chunkPipelineSpawnMinWriteRadius = 1;

    @ConfigComment("Striped CAS guard slots for chunk-status write admission. Rounded up to a power of two.")
    public int chunkPipelineGuardStripes = 65536;

    @ConfigComment("Spin attempts before a guarded chunk-status writer parks briefly.")
    public int chunkPipelineGuardFastSpins = 128;

    @ConfigComment("Maximum nanos to park while waiting for a guarded chunk-status region.")
    public long chunkPipelineGuardMaxParkNanos = 250000L;

    @ConfigComment("Enable GA custom chunk graph scheduler. This bypasses the vanilla layer-barrier worldgen mailbox and schedules ready DAG nodes directly.")
    public boolean enableCustomChunkGraphScheduler = true;

    @ConfigComment("Load the full generation-radius EMPTY dependency shell before building a custom chunk DAG.")
    public boolean chunkGraphEagerEmptyRadius = true;

    @ConfigComment("Coalesce overlapping custom chunk graph nodes that request the same holder/status future.")
    public boolean chunkGraphCoalesceInFlight = true;

    @ConfigComment("Lock-free in-flight step coalescing bucket count. Rounded up to a power of two.")
    public int chunkGraphInFlightBuckets = 65536;

    @ConfigComment("Lock-free in-flight step coalescing ways per bucket before falling back to direct scheduling.")
    public int chunkGraphInFlightWays = 4;

    @ConfigComment("Max GA chunk workspaces allowed in-flight. 0 = auto.")
    public int maxInFlightWorkspaces = 0;

    @ConfigComment("Max retained bytes per GA chunk workspace before shrink. 0 = auto.")
    public long workspaceMaxRetainedBytes = 0L;

    @ConfigComment("Bind imported GA chunk workspaces around controlled runtime worldgen statuses.")
    public boolean enableChunkWorkspaceRuntime = true;

    @ConfigComment("Mirror WorldGenRegion and GA decoration writes into the active chunk workspace.")
    public boolean enableDecorationWorkspaceBridge = true;

    @ConfigComment("Replay dirty workspace block writes through the commit lane during workspace close.")
    public boolean enableWorkspaceFinalRepack = true;

    @ConfigComment("Validate final workspace repack and repair sections that would otherwise look air-only after workspace-only writes.")
    public boolean enableWorkspaceFinalRepackValidation = true;

    @ConfigComment("Use a full raw section copy for dense workspace final repack sections instead of replaying many individual dirty runs.")
    public boolean enableWorkspaceDenseFinalSectionCopy = true;

    @ConfigComment("Dirty blocks per section required before dense final repack switches to full raw section copy.")
    public int workspaceDenseFinalSectionCopyThreshold = 1024;

    @ConfigComment("For NOISE terrain workspace-only chunks whose sections are still air, initialize the workspace as air instead of importing all raw block ids.")
    public boolean enableWorkspaceTerrainAirImport = true;

    @ConfigComment("For terrain air-imported workspaces, clear air sections lazily on first write instead of filling the whole chunk buffer upfront.")
    public boolean enableWorkspaceTerrainLazyAirImport = true;

    @ConfigComment("Track terrain workspace-only dirtiness at section granularity so final terrain publication can use full raw section copies without per-block dirty bits.")
    public boolean enableWorkspaceTerrainSectionOnlyDirtyTracking = true;

    @ConfigComment("Publish terrain-only workspace final repack on the owning generation thread instead of queueing every section through the commit lane.")
    public boolean enableWorkspaceLocalTerrainFinalRepack = true;

    @ConfigComment("Disable workspace-only writes for the rest of the session if final repack cannot be repaired safely.")
    public boolean enableWorkspaceOnlyCircuitBreaker = true;

    @ConfigComment("Enable workspace-only block writes for hot worldgen paths. Experimental; disabled by default because terrain mirroring/final repack can regress chunk throughput.")
    public boolean enableWorkspaceOnlyBlockWrites = false;

    @ConfigComment("Route safe known decoration kernels through compact workspace diff journals instead of immediate section writes. Experimental; direct raw writes are faster for sparse ores in current runtime.")
    public boolean enableKnownDecorationJournalWrites = false;

    @ConfigComment("Compute non-conflicting known decoration journal kernels in parallel against detached read snapshots, then merge their journals deterministically. Experimental; disabled by default because snapshot/fallback overhead currently regresses chunk generation.")
    public boolean enableDecorationConflictScheduler = false;

    @ConfigComment("Minimum same-family known decoration kernels in a row before the conflict scheduler parallelizes them.")
    public int decorationConflictSchedulerMinBatch = 4;

    @ConfigComment("Detached chunk radius copied for parallel decoration reads. 0 = center chunk only; boundary misses fall back to the safe sequential path.")
    public int decorationConflictSchedulerSnapshotRadius = 0;

    @ConfigComment("Route workspace-only neighbor chunk writes through a deterministic owner mailbox.")
    public boolean enableCrossChunkMailboxRuntime = true;

    @ConfigComment("Maximum queued cross-chunk mailbox block writes. 0 = unlimited.")
    public int crossChunkMailboxMaxQueuedCommands = 262144;

    @ConfigComment("Enable runtime dispatch for explicit transaction-sandbox worldgen units.")
    public boolean enableTransactionSandboxRuntime = true;
}
