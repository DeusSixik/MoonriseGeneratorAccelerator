package dev.sixik.generator_accelerator.common.density.compiler.compat;

import net.minecraft.world.level.biome.Climate;

import java.lang.reflect.Method;

/**
 * Propagates Fabric biome API seed from the vanilla wired sampler to a DFC-rebuilt instance
 * without linking common code against Fabric API internals.
 */
public final class FabricBiomeApiClimateRebind {

    private FabricBiomeApiClimateRebind() {}

    public static void propagateToCompiledSampler(Climate.Sampler from, Climate.Sampler to, long levelSeed) {
        if (to == from) {
            return;
        }
        Class<?> hooks;
        try {
            hooks = Class.forName("net.fabricmc.fabric.impl.biome.MultiNoiseSamplerHooks");
        } catch (ClassNotFoundException ignored) {
            return;
        }
        long seed = levelSeed;
        if (hooks.isInstance(from)) {
            try {
                Method getSeed = hooks.getMethod("fabric_getSeed");
                seed = (long) getSeed.invoke(from);
            } catch (Throwable ignored) {
            }
        }
        if (hooks.isInstance(to)) {
            try {
                Method setSeed = hooks.getMethod("fabric_setSeed", long.class);
                setSeed.invoke(to, seed);
            } catch (Throwable ignored) {
            }
        }
    }
}
