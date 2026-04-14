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

    public boolean enableDensityPatch = true;

    public boolean enableFeaturesPatch = true;

    public boolean enableFlatBlockStructurePatch = true;

    public boolean enableHeightmapPatch = true;

    public boolean enableNoisePatch = true;

    @ConfigComment("Enable Noise Native Patch\nATTENTION!\nWhen enabled, the generation will be radically different from the vanilla system. For example, where there used to be a plain, there's now a desert, etc.")
    public boolean enableNoiseNativePatch = false;

    public boolean enableStructuresPatch = true;

    public boolean enableSurfacePatch = true;
}
