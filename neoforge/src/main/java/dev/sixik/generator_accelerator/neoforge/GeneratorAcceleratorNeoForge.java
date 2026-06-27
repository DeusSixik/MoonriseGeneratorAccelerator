package dev.sixik.generator_accelerator.neoforge;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.neoforge.client.GeneratorAcceleratorNeoForgeClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

@Mod(GeneratorAccelerator.MOD_ID)
public final class GeneratorAcceleratorNeoForge {
    public GeneratorAcceleratorNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        GeneratorAccelerator.init(GeneratorAccelerator.Platform.NEOFORGE, !FMLEnvironment.production, FMLPaths.GAMEDIR.get());
        GeneratorAcceleratorNeoForgeClient.init(modEventBus);
    }
}
