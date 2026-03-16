package dev.sixik.generator_accelerator.fabric;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import net.fabricmc.api.ModInitializer;

public final class GeneratorAcceleratorFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        GeneratorAccelerator.init();
    }
}
