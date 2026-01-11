package dev.sixik.moonrisegeneratoraccelerator.fabric;

import dev.sixik.moonrisegeneratoraccelerator.MoonriseGeneratorAccelerator;
import net.fabricmc.api.ModInitializer;

public final class MoonriseGeneratorAcceleratorFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MoonriseGeneratorAccelerator.init();
    }
}
