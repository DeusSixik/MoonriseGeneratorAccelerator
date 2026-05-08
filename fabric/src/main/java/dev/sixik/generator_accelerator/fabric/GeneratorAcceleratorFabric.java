package dev.sixik.generator_accelerator.fabric;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class GeneratorAcceleratorFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        GeneratorAccelerator.init(GeneratorAccelerator.Platform.FABRIC, FabricLoader.getInstance().isDevelopmentEnvironment());
        DensityFunctionCompiler.init();
        ServerLifecycleEvents.SERVER_STARTING.register(DensityFunctionCompiler::onServerStarting);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> DensityFunctionCompiler.registerCommands(dispatcher));
    }
}
