package dev.sixik.generator_accelerator.neoforge;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import net.neoforged.fml.common.Mod;

@Mod(GeneratorAccelerator.MOD_ID)
public final class GeneratorAcceleratorNeoForge {
    public GeneratorAcceleratorNeoForge() {
        // Run our common setup.
        GeneratorAccelerator.init();
    }
}
