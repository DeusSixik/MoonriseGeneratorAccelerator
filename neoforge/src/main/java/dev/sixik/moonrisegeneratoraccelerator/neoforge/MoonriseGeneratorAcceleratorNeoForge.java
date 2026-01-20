package dev.sixik.moonrisegeneratoraccelerator.neoforge;

import dev.sixik.moonrisegeneratoraccelerator.MoonriseGeneratorAccelerator;
import net.neoforged.fml.common.Mod;

import java.util.concurrent.CompletableFuture;

@Mod(MoonriseGeneratorAccelerator.MOD_ID)
public final class MoonriseGeneratorAcceleratorNeoForge {
    public MoonriseGeneratorAcceleratorNeoForge() {
        MoonriseGeneratorAccelerator.init();


    }
}
