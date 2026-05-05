package dev.sixik.generator_accelerator.neoforge;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.common.density.compiler.DensityFunctionCompiler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

@Mod(GeneratorAccelerator.MOD_ID)
public final class GeneratorAcceleratorNeoForge {
    public GeneratorAcceleratorNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        GeneratorAccelerator.init(GeneratorAccelerator.Platform.NEOFORGE);
        DensityFunctionCompiler.init();
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(this::onServerStarting);
        bus.addListener(this::onServerStarted);
        bus.addListener(this::onDatapackSync);
        bus.addListener(this::onRegisterCommands);

//        modEventBus.addListener(this::commonSetup);
    }

    private void onServerStarting(ServerStartingEvent event) {
        DensityFunctionCompiler.onServerStarting(event.getServer());
    }

    private void onServerStarted(ServerStartedEvent event) {
        DensityFunctionCompiler.onServerStarted(event.getServer());
    }

    private void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            return;
        }
        DensityFunctionCompiler.onDatapackReload(event.getPlayerList().getServer());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        DensityFunctionCompiler.registerCommands(event.getDispatcher());
    }

    private void commonSetup(FMLLoadCompleteEvent event) {
//        FastBlockStateCache.init(GeneratorAccelerator.Platform.NEOFORGE);
    }
}
