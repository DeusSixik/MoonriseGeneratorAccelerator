package dev.sixik.generator_accelerator.config;

public class GAConfig {

    public boolean enableAquiferPatch = true;

    public boolean enableBeardifierPatch = true;

    public boolean enableBiomePatch = true;

    public boolean enableBlenderPatch = true;

    public boolean enableDensityPatch = true;

    public boolean enableFeaturesPatch = true;

    public boolean enableFlatBlockStructurePatch = true;

    public boolean enableHeightmapPatch = true;

    public boolean enableNoisePatch = true;

//    @ConfigComment("Enable Noise Native Patch\nATTENTION!\nWhen enabled, the generation will be radically different from the vanilla system. For example, where there used to be a plain, there's now a desert, etc.")
    public boolean enableNoiseNativePatch = false;

    public boolean enablePalettedContainerPatch = true;

    public boolean enableStructuresPatch = true;

    public boolean enableSurfacePatch = true;
}
