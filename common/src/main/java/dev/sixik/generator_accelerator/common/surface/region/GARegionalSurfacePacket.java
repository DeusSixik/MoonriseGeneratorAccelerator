package dev.sixik.generator_accelerator.common.surface.region;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Chunk-local read packet over exact regional surface caches.
 *
 * <p>The packet does not own mutable worldgen state. It only memoizes the
 * current chunk's 4x4 region views so surface code can fetch depth,
 * secondary-surface noise, and arbitrary surface-noise rasters without paying
 * repeated global cache lookups.</p>
 */
public final class GARegionalSurfacePacket {
    private static final int NOISE_SLOT_COUNT = 4;

    private SurfaceSystem surfaceSystem;
    private RandomState randomState;
    private int chunkMinX = Integer.MIN_VALUE;
    private int chunkMinZ = Integer.MIN_VALUE;
    private int surfaceRegionX = Integer.MIN_VALUE;
    private int surfaceRegionZ = Integer.MIN_VALUE;
    private int[] surfaceDepthRegionValues;
    private double[] secondarySurfaceRegionValues;

    private final Object[] noiseKeys = new Object[NOISE_SLOT_COUNT];
    private final RandomState[] noiseStates = new RandomState[NOISE_SLOT_COUNT];
    private final int[] noiseRegionX = new int[NOISE_SLOT_COUNT];
    private final int[] noiseRegionZ = new int[NOISE_SLOT_COUNT];
    private final double[][] noiseRegionValues = new double[NOISE_SLOT_COUNT][];
    private int nextNoiseSlot;

    public void reset() {
        this.surfaceSystem = null;
        this.randomState = null;
        this.chunkMinX = Integer.MIN_VALUE;
        this.chunkMinZ = Integer.MIN_VALUE;
        this.surfaceRegionX = Integer.MIN_VALUE;
        this.surfaceRegionZ = Integer.MIN_VALUE;
        this.surfaceDepthRegionValues = null;
        this.secondarySurfaceRegionValues = null;
        for (int i = 0; i < NOISE_SLOT_COUNT; i++) {
            this.noiseKeys[i] = null;
            this.noiseStates[i] = null;
            this.noiseRegionX[i] = Integer.MIN_VALUE;
            this.noiseRegionZ[i] = Integer.MIN_VALUE;
            this.noiseRegionValues[i] = null;
        }
        this.nextNoiseSlot = 0;
    }

    public void bindChunk(SurfaceSystem surfaceSystem, RandomState randomState, int chunkMinX, int chunkMinZ) {
        this.randomState = randomState;
        if (this.surfaceSystem != surfaceSystem
                || this.chunkMinX != chunkMinX
                || this.chunkMinZ != chunkMinZ) {
            this.surfaceSystem = surfaceSystem;
            this.chunkMinX = chunkMinX;
            this.chunkMinZ = chunkMinZ;
            this.surfaceRegionX = chunkMinX >> GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
            this.surfaceRegionZ = chunkMinZ >> GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
            this.surfaceDepthRegionValues = null;
            this.secondarySurfaceRegionValues = null;
        }
    }

    public void copySurfaceDepths(int[] out) {
        SurfaceSystem system = this.surfaceSystem;
        if (system == null) {
            throw new IllegalStateException("surface packet is not bound");
        }
        if (!GARegionalSurfaceColumnCache.enabled()) {
            int baseX = this.chunkMinX;
            int baseZ = this.chunkMinZ;
            for (int xz = 0; xz < 256; xz++) {
                out[xz] = system.getSurfaceDepth(baseX + (xz & 15), baseZ + (xz >> 4));
            }
            return;
        }
        if (this.surfaceDepthRegionValues == null) {
            this.surfaceDepthRegionValues = GARegionalSurfaceColumnCache.depthRegionValues(
                    system,
                    this.surfaceRegionX,
                    this.surfaceRegionZ
            );
        }
        GARegionalSurfaceColumnCache.copyRegionInts(this.surfaceDepthRegionValues, this.chunkMinX, this.chunkMinZ, out);
    }

    public void copySecondarySurfaceNoises(double[] out) {
        SurfaceSystem system = this.surfaceSystem;
        if (system == null) {
            throw new IllegalStateException("surface packet is not bound");
        }
        if (!GARegionalSurfaceColumnCache.enabled()) {
            int baseX = this.chunkMinX;
            int baseZ = this.chunkMinZ;
            for (int xz = 0; xz < 256; xz++) {
                out[xz] = system.getSurfaceSecondary(baseX + (xz & 15), baseZ + (xz >> 4));
            }
            return;
        }
        if (this.secondarySurfaceRegionValues == null) {
            this.secondarySurfaceRegionValues = GARegionalSurfaceColumnCache.secondaryRegionValues(
                    system,
                    this.surfaceRegionX,
                    this.surfaceRegionZ
            );
        }
        GARegionalSurfaceColumnCache.copyRegionDoubles(this.secondarySurfaceRegionValues, this.chunkMinX, this.chunkMinZ, out);
    }

    public double sampleNoise(ResourceKey<NormalNoise.NoiseParameters> noiseKey, int blockX, int blockZ) {
        RandomState state = this.randomState;
        if (state == null) {
            throw new IllegalStateException("surface packet is not bound");
        }
        if (!GARegionalSurfaceNoiseCache.enabled()) {
            return state.getOrCreateNoise(noiseKey).getValue(blockX, 0.0D, blockZ);
        }

        int regionX = blockX >> GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
        int regionZ = blockZ >> GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT;
        double[] values = lookupNoiseRegion(state, noiseKey, regionX, regionZ);
        return values[(blockX & GARegionalSurfaceNoiseCache.REGION_BLOCK_MASK)
                | ((blockZ & GARegionalSurfaceNoiseCache.REGION_BLOCK_MASK) << GARegionalSurfaceNoiseCache.REGION_BLOCK_SHIFT)];
    }

    private double[] lookupNoiseRegion(
            RandomState state,
            ResourceKey<NormalNoise.NoiseParameters> noiseKey,
            int regionX,
            int regionZ
    ) {
        for (int i = 0; i < NOISE_SLOT_COUNT; i++) {
            if (this.noiseStates[i] == state
                    && this.noiseRegionX[i] == regionX
                    && this.noiseRegionZ[i] == regionZ
                    && noiseKey.equals(this.noiseKeys[i])) {
                return this.noiseRegionValues[i];
            }
        }

        double[] values = GARegionalSurfaceNoiseCache.regionValues(state, noiseKey, regionX, regionZ);
        int slot = this.nextNoiseSlot;
        this.noiseKeys[slot] = noiseKey;
        this.noiseStates[slot] = state;
        this.noiseRegionX[slot] = regionX;
        this.noiseRegionZ[slot] = regionZ;
        this.noiseRegionValues[slot] = values;
        this.nextNoiseSlot = (slot + 1) & (NOISE_SLOT_COUNT - 1);
        return values;
    }
}
