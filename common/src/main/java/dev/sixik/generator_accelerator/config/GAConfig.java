package dev.sixik.generator_accelerator.config;


import net.shadowking21.shadowconfig.annotation.ConfigComment;

public class GAConfig {

    @ConfigComment("Enable Aquifer Patch")
    public boolean enableAquiferPatch = false;

    @ConfigComment("Enable Beardifier Patch")
    public boolean enableBeardifierPatch = false;

    @ConfigComment("Enable Biome Patch")
    public boolean enableBiomePatch = false;

    @ConfigComment("Enable Blender Patch")
    public boolean enableBlenderPatch = false;

    public boolean enableDensityPatch = false;

    public boolean enableFeaturesPatch = false;

    public boolean enableFlatBlockStructurePatch = true;

    public boolean enableHeightmapPatch = false;

    public boolean enableNoisePatch = false;

    public boolean enableNoiseNativePatch = false;

    public boolean enableStructuresPatch = false;

    public boolean enableSurfacePatch = false;
}
