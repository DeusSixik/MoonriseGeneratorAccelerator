package dev.sixik.generator_accelerator.common.surface;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.lang.ref.WeakReference;

public final class FastSurfaceSystemCarvingCacheEntry {
    public WeakReference<ChunkAccess> chunk = new WeakReference<>(null);
    public WeakReference<SurfaceRules.Context> context = new WeakReference<>(null);
    public WeakReference<SurfaceRules.SurfaceRule> rule = new WeakReference<>(null);
}
