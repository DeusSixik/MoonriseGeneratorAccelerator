package dev.sixik.generator_accelerator.common.worldgen.region;

import dev.sixik.generator_accelerator.common.aquifer.region.GARegionalAquiferAtlas;
import dev.sixik.generator_accelerator.common.aquifer.region.GARegionalAquiferAtlasOwner;
import dev.sixik.generator_accelerator.common.beardifier.region.GARegionalBeardifierAtlas;
import dev.sixik.generator_accelerator.common.beardifier.region.GARegionalBeardifierAtlasOwner;
import dev.sixik.generator_accelerator.common.biome.region.GARegionalClimateQuartRaster;
import dev.sixik.generator_accelerator.common.biome.region.GARegionalClimateQuartRasterOwner;
import dev.sixik.generator_accelerator.common.noise.region.GARegionalDensityLatticeView;
import dev.sixik.generator_accelerator.common.noise.region.GARegionalNoiseBrickCache;
import dev.sixik.generator_accelerator.common.noise.region.GARegionalDensitySliceCacheOwner;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Climate;
import dev.sixik.generator_accelerator.common.surface.region.GARegionalSurfacePacket;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceSystem;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Chunk-local facade over exact 4x4 regional worldgen subsystems.
 */
public final class GAUnifiedRegionPacket {
    private final GARegionalSurfacePacket surfacePacket = new GARegionalSurfacePacket();

    private int chunkMinX = Integer.MIN_VALUE;
    private int chunkMinZ = Integer.MIN_VALUE;
    private int regionX = Integer.MIN_VALUE;
    private int regionZ = Integer.MIN_VALUE;

    private GARegionalDensitySliceCacheOwner densityOwner;
    private GARegionalDensityLatticeView densityView;

    private GARegionalAquiferAtlasOwner aquiferOwner;
    private GARegionalAquiferAtlas.View aquiferView;

    private GARegionalBeardifierAtlasOwner beardifierOwner;
    private GARegionalBeardifierAtlas.View beardifierView;

    private GARegionalClimateQuartRasterOwner climateOwner;
    private GARegionalClimateQuartRaster.View climateView;
    private GARegionalNoiseBrickCache.View noiseBrickView;

    private SurfaceSystem surfaceSystem;
    private RandomState randomState;

    public void reset() {
        this.chunkMinX = Integer.MIN_VALUE;
        this.chunkMinZ = Integer.MIN_VALUE;
        this.regionX = Integer.MIN_VALUE;
        this.regionZ = Integer.MIN_VALUE;
        this.densityOwner = null;
        this.densityView = null;
        this.aquiferOwner = null;
        this.aquiferView = null;
        this.beardifierOwner = null;
        this.beardifierView = null;
        this.climateOwner = null;
        this.climateView = null;
        this.noiseBrickView = null;
        this.surfaceSystem = null;
        this.randomState = null;
        this.surfacePacket.reset();
    }

    public void bindTerrain(
            int chunkMinX,
            int chunkMinZ,
            GARegionalDensitySliceCacheOwner densityOwner,
            GARegionalAquiferAtlasOwner aquiferOwner
    ) {
        boolean regionChanged = this.chunkMinX != chunkMinX || this.chunkMinZ != chunkMinZ;
        this.chunkMinX = chunkMinX;
        this.chunkMinZ = chunkMinZ;
        this.regionX = chunkMinX >> GARegionalAquiferAtlas.REGION_BLOCK_SHIFT;
        this.regionZ = chunkMinZ >> GARegionalAquiferAtlas.REGION_BLOCK_SHIFT;
        this.noiseBrickView = GARegionalNoiseBrickCache.view(chunkMinX, chunkMinZ);
        if (regionChanged && this.densityOwner != null && densityOwner == null) {
            this.densityView = new GARegionalDensityLatticeView(this.densityOwner, this.regionX, this.regionZ);
        }
        if (regionChanged && this.aquiferOwner != null && aquiferOwner == null) {
            this.aquiferView = GARegionalAquiferAtlas.view(this.aquiferOwner, chunkMinX, chunkMinZ);
        }
        if (densityOwner != null && (regionChanged || this.densityOwner != densityOwner)) {
            this.densityOwner = densityOwner;
            this.densityView = new GARegionalDensityLatticeView(densityOwner, this.regionX, this.regionZ);
        }
        if (aquiferOwner != null && (regionChanged || this.aquiferOwner != aquiferOwner)) {
            this.aquiferOwner = aquiferOwner;
            this.aquiferView = GARegionalAquiferAtlas.view(aquiferOwner, chunkMinX, chunkMinZ);
        }
    }

    public void bindSurface(SurfaceSystem surfaceSystem, RandomState randomState, int chunkMinX, int chunkMinZ) {
        this.surfaceSystem = surfaceSystem;
        this.randomState = randomState;
        this.surfacePacket.bindChunk(surfaceSystem, randomState, chunkMinX, chunkMinZ);
    }

    public void bindClimate(
            Object biomeIdentity,
            BiomeManager.NoiseBiomeSource biomeSource,
            Object climateIdentity,
            Climate.Sampler climateSampler,
            int chunkMinX,
            int chunkMinZ,
            int minQuartY,
            int quartHeight
    ) {
        if (biomeSource == null || quartHeight <= 0) {
            return;
        }
        boolean regionChanged = this.chunkMinX != chunkMinX || this.chunkMinZ != chunkMinZ;
        this.chunkMinX = chunkMinX;
        this.chunkMinZ = chunkMinZ;
        this.regionX = (chunkMinX >> 4) >> GARegionalClimateQuartRaster.REGION_CHUNK_SHIFT;
        this.regionZ = (chunkMinZ >> 4) >> GARegionalClimateQuartRaster.REGION_CHUNK_SHIFT;
        this.noiseBrickView = GARegionalNoiseBrickCache.view(chunkMinX, chunkMinZ);
        GARegionalClimateQuartRasterOwner nextOwner = new GARegionalClimateQuartRasterOwner(
                biomeIdentity,
                biomeSource,
                climateIdentity,
                climateSampler,
                minQuartY,
                quartHeight
        );
        if (regionChanged || this.climateOwner == null || !this.climateOwner.equals(nextOwner)) {
            this.climateOwner = nextOwner;
            this.climateView = GARegionalClimateQuartRaster.view(nextOwner, chunkMinX, chunkMinZ);
        } else if (this.climateView == null) {
            this.climateView = GARegionalClimateQuartRaster.view(nextOwner, chunkMinX, chunkMinZ);
        }
    }

    public void bindClimate(
            BiomeManager.NoiseBiomeSource biomeSource,
            Climate.Sampler climateSampler,
            int chunkMinX,
            int chunkMinZ,
            int minQuartY,
            int quartHeight
    ) {
        bindClimate(
                biomeSource,
                biomeSource,
                climateSampler,
                climateSampler,
                chunkMinX,
                chunkMinZ,
                minQuartY,
                quartHeight
        );
    }

    public void bindBeardifier(GARegionalBeardifierAtlasOwner beardifierOwner, int blockX, int blockZ) {
        if (this.beardifierOwner == beardifierOwner && this.beardifierView != null) {
            return;
        }
        this.beardifierOwner = beardifierOwner;
        this.beardifierView = beardifierOwner == null ? null : GARegionalBeardifierAtlas.view(beardifierOwner, blockX, blockZ);
    }

    public GARegionalDensityLatticeView densityView() {
        return this.densityView;
    }

    public GARegionalAquiferAtlas.View aquiferView() {
        return this.aquiferView;
    }

    public void attachAquiferOwner(GARegionalAquiferAtlasOwner aquiferOwner) {
        if (aquiferOwner == null) {
            return;
        }
        this.aquiferOwner = aquiferOwner;
        if (this.chunkMinX != Integer.MIN_VALUE && this.chunkMinZ != Integer.MIN_VALUE) {
            this.aquiferView = GARegionalAquiferAtlas.view(aquiferOwner, this.chunkMinX, this.chunkMinZ);
        }
    }

    public GARegionalBeardifierAtlas.View beardifierView() {
        return this.beardifierView;
    }

    public GARegionalSurfacePacket surfaceView() {
        return this.surfacePacket;
    }

    public GARegionalClimateQuartRaster.View climateView() {
        return this.climateView;
    }

    public GARegionalNoiseBrickCache.View noiseBrickView() {
        return this.noiseBrickView;
    }

    public int chunkMinX() {
        return this.chunkMinX;
    }

    public int chunkMinZ() {
        return this.chunkMinZ;
    }

    public int regionX() {
        return this.regionX;
    }

    public int regionZ() {
        return this.regionZ;
    }

    public void requestTerrainPrewarm(Runnable densityWarm, Runnable aquiferWarm, Runnable beardifierWarm) {
        requestNoisePrewarm(densityWarm, aquiferWarm, beardifierWarm, null, null);
    }

    public void requestNoisePrewarm(
            Runnable densityWarm,
            Runnable aquiferWarm,
            Runnable beardifierWarm,
            Runnable climateWarm,
            Runnable noiseBrickWarm
    ) {
        if (!GARegionalPrewarmManager.enabled()) {
            runIfPresent(densityWarm);
            runIfPresent(aquiferWarm);
            runIfPresent(beardifierWarm);
            runIfPresent(climateWarm);
            runIfPresent(noiseBrickWarm);
            return;
        }
        Object key = new NoisePrewarmKey(
                this.regionX,
                this.regionZ,
                this.densityOwner,
                this.aquiferOwner,
                this.beardifierOwner,
                this.climateOwner
        );
        GARegionalPrewarmManager.request(
                GARegionalPrewarmManager.RequestType.TERRAIN,
                key,
                () -> {
                    runIfPresent(densityWarm);
                    runIfPresent(aquiferWarm);
                    runIfPresent(beardifierWarm);
                    runIfPresent(climateWarm);
                    runIfPresent(noiseBrickWarm);
                }
        );
    }

    public void ensureTerrainReady(Runnable densityWarm, Runnable aquiferWarm, Runnable beardifierWarm) {
        ensureNoiseReady(densityWarm, aquiferWarm, beardifierWarm, null, null);
    }

    public void ensureNoiseReady(
            Runnable densityWarm,
            Runnable aquiferWarm,
            Runnable beardifierWarm,
            Runnable climateWarm,
            Runnable noiseBrickWarm
    ) {
        Object key = new NoisePrewarmKey(
                this.regionX,
                this.regionZ,
                this.densityOwner,
                this.aquiferOwner,
                this.beardifierOwner,
                this.climateOwner
        );
        GARegionalPrewarmManager.ensureInline(
                GARegionalPrewarmManager.RequestType.TERRAIN,
                key,
                () -> {
                    runIfPresent(densityWarm);
                    runIfPresent(aquiferWarm);
                    runIfPresent(beardifierWarm);
                    runIfPresent(climateWarm);
                    runIfPresent(noiseBrickWarm);
                }
        );
    }

    public void requestSurfacePrewarm(Runnable surfaceWarm) {
        if (!GARegionalPrewarmManager.enabled()) {
            runIfPresent(surfaceWarm);
            return;
        }
        Object key = new SurfacePrewarmKey(this.regionX, this.regionZ, this.surfaceSystem, this.randomState);
        GARegionalPrewarmManager.request(
                GARegionalPrewarmManager.RequestType.SURFACE,
                key,
                () -> runIfPresent(surfaceWarm)
        );
    }

    public void requestClimatePrewarm(Runnable climateWarm) {
        if (!GARegionalPrewarmManager.enabled()) {
            runIfPresent(climateWarm);
            return;
        }
        Object key = new ClimatePrewarmKey(this.regionX, this.regionZ, this.climateOwner);
        GARegionalPrewarmManager.request(
                GARegionalPrewarmManager.RequestType.CLIMATE,
                key,
                () -> runIfPresent(climateWarm)
        );
    }

    private static void runIfPresent(Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    private record NoisePrewarmKey(
            int regionX,
            int regionZ,
            GARegionalDensitySliceCacheOwner densityOwner,
            GARegionalAquiferAtlasOwner aquiferOwner,
            GARegionalBeardifierAtlasOwner beardifierOwner,
            GARegionalClimateQuartRasterOwner climateOwner
    ) {
    }

    private record SurfacePrewarmKey(
            int regionX,
            int regionZ,
            SurfaceSystem surfaceSystem,
            RandomState randomState
    ) {
        private SurfacePrewarmKey {
            Objects.requireNonNull(surfaceSystem, "surfaceSystem");
            Objects.requireNonNull(randomState, "randomState");
        }
    }

    private record ClimatePrewarmKey(
            int regionX,
            int regionZ,
            GARegionalClimateQuartRasterOwner climateOwner
    ) {
    }
}
