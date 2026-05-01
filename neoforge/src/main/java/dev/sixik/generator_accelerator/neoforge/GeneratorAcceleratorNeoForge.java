package dev.sixik.generator_accelerator.neoforge;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

@Mod(GeneratorAccelerator.MOD_ID)
public final class GeneratorAcceleratorNeoForge {
    public GeneratorAcceleratorNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        GeneratorAccelerator.init(GeneratorAccelerator.Platform.NEOFORGE);

        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLLoadCompleteEvent event) {
        FastBlockStateCache.init(GeneratorAccelerator.Platform.NEOFORGE);
    }
}
