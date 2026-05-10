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

    @ConfigComment("GA scheduler commit/finalize lane workers. Keep 1 for deterministic chunk writes.")
    public int schedulerCommitWorkers = 1;

    @ConfigComment("Soft max queued tasks per lane. 0 = unlimited.")
    public int schedulerMaxQueuedTasks = 0;

    @ConfigComment("Adaptive worldgen governor CPU target. Reserved for staged rollout.")
    public double schedulerCpuTarget = 0.85D;

    @ConfigComment("Max GA chunk workspaces allowed in-flight. 0 = auto.")
    public int maxInFlightWorkspaces = 0;

    @ConfigComment("Max retained bytes per GA chunk workspace before shrink. 0 = auto.")
    public long workspaceMaxRetainedBytes = 0L;
}
