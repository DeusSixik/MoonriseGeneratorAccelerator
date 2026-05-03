package dev.sixik.generator_accelerator.forge;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import net.minecraftforge.fml.common.Mod;

@Mod(GeneratorAccelerator.MOD_ID)
public final class GeneratorAcceleratorForge {
    public GeneratorAcceleratorForge() {
        // Run our common setup.
        GeneratorAccelerator.init(GeneratorAccelerator.Platform.NEOFORGE);
    }
}
