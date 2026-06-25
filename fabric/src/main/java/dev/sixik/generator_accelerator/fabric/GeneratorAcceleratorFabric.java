package dev.sixik.generator_accelerator.fabric;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class GeneratorAcceleratorFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        GeneratorAccelerator.init(GeneratorAccelerator.Platform.FABRIC, FabricLoader.getInstance().isDevelopmentEnvironment(), FabricLoader.getInstance().getGameDir());
    }
}
