package dev.sixik.generator_accelerator.common.surface;

import dev.sixik.generator_accelerator.common.biome.region.GARegionalClimateQuartRaster;
import dev.sixik.generator_accelerator.common.worldgen.region.GAUnifiedRegionPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Arrays;
import java.util.function.Function;

public class GASurfaceChunkBiomeLookup implements Function<BlockPos, Holder<Biome>> {
    @SuppressWarnings("unchecked")
    private Holder<Biome>[] biomes = new Holder[0];
    private double[] biasX = new double[0], biasY = new double[0], biasZ = new double[0];
    private boolean[] uniform = new boolean[0];

    private int qMinX, qMinY, qMinZ;
    private int sizeX, sizeY, sizeZ;
    private BiomeManager fallbackManager;
    private GARegionalClimateQuartRaster.View climateView;

    @SuppressWarnings("unchecked")
    public void prepare(
            BiomeManager.NoiseBiomeSource source,
            long biomeZoomSeed,
            ChunkAccess chunk,
            BiomeManager fallback,
            int minQueryY,
            int maxQueryY,
            net.minecraft.world.level.levelgen.RandomState randomState,
            GAUnifiedRegionPacket regionalPacket) {
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();

        int biomeOffset = 2;
        int minBlockX = chunkMinX - biomeOffset;
        int maxBlockX = chunkMinX + 15 - biomeOffset;
        int minBlockZ = chunkMinZ - biomeOffset;
        int maxBlockZ = chunkMinZ + 15 - biomeOffset;
        int minBlockY = minQueryY - biomeOffset;
        int maxBlockY = maxQueryY - biomeOffset;

        this.qMinX = QuartPos.fromBlock(minBlockX);
        int qMaxX = QuartPos.fromBlock(maxBlockX) + 1;
        this.qMinZ = QuartPos.fromBlock(minBlockZ);
        int qMaxZ = QuartPos.fromBlock(maxBlockZ) + 1;
        this.qMinY = QuartPos.fromBlock(minBlockY);
        int qMaxY = QuartPos.fromBlock(maxBlockY) + 1;

        this.sizeX = qMaxX - this.qMinX + 1;
        this.sizeY = qMaxY - this.qMinY + 1;
        this.sizeZ = qMaxZ - this.qMinZ + 1;
        this.climateView = null;

        int totalCells = this.sizeX * this.sizeY * this.sizeZ;

        if (this.biomes.length < totalCells) {
            this.biomes = new Holder[totalCells];
            this.biasX = new double[totalCells];
            this.biasY = new double[totalCells];
            this.biasZ = new double[totalCells];
            this.uniform = new boolean[totalCells];
        }

        if (regionalPacket != null && randomState != null && GARegionalClimateQuartRaster.surfaceEnabled()) {
            regionalPacket.bindClimate(
                    source,
                    source,
                    null,
                    null,
                    chunkMinX,
                    chunkMinZ,
                    this.qMinY,
                    this.sizeY
            );
            this.climateView = regionalPacket.climateView();
        }

        boolean seenMultiple = this.fetchBiomes(source);

        if (!seenMultiple) {
            Arrays.fill(this.uniform, 0, totalCells, true);
        } else {
            this.computeUniformity();
            this.computeBiases(biomeZoomSeed);
        }

        this.fallbackManager = fallback;
    }

    public void dispose() {
        this.fallbackManager = null;
        this.climateView = null;
    }

    @Override
    public Holder<Biome> apply(BlockPos pos) {
        return this.getBiomeAt(pos);
    }

    public Holder<Biome> getBiomeAt(BlockPos pos) {
        if (this.fallbackManager == null) {
            throw new IllegalStateException("prepare() not called on GASurfaceChunkBiomeLookup");
        }
        int i = pos.getX() - 2;
        int j = pos.getY() - 2;
        int k = pos.getZ() - 2;

        int rx = QuartPos.fromBlock(i) - this.qMinX;
        int ry = QuartPos.fromBlock(j) - this.qMinY;
        int rz = QuartPos.fromBlock(k) - this.qMinZ;

        if (rx < 0 || rx >= this.sizeX - 1
                || ry < 0 || ry >= this.sizeY - 1
                || rz < 0 || rz >= this.sizeZ - 1) {
            return this.fallbackManager.getBiome(pos);
        }

        int baseIdx = this.index(rx, ry, rz);
        if (this.uniform[baseIdx]) {
            return this.biomes[baseIdx];
        }
        return this.getBiomeWithVoronoi(i, j, k, rx, ry, rz);
    }

    private boolean fetchBiomes(BiomeManager.NoiseBiomeSource source) {
        var biomes = this.biomes;
        GARegionalClimateQuartRaster.View climateView = this.climateView;
        Holder<Biome> first = null;
        boolean multiple = false;
        for (int rx = 0; rx < this.sizeX; rx++) {
            int wx = this.qMinX + rx;
            for (int rz = 0; rz < this.sizeZ; rz++) {
                int wz = this.qMinZ + rz;
                for (int ry = 0; ry < this.sizeY; ry++) {
                    int wy = this.qMinY + ry;
                    Holder<Biome> b = climateView == null
                            ? source.getNoiseBiome(wx, wy, wz)
                            : climateView.sampleNoiseBiome(wx, wy, wz);
                    if (b == null) {
                        b = source.getNoiseBiome(wx, wy, wz);
                    }
                    biomes[this.index(rx, ry, rz)] = b;
                    if (first == null) {
                        first = b;
                    } else if (b != first) {
                        multiple = true;
                    }
                }
            }
        }
        return multiple;
    }

    private void computeUniformity() {
        int sizeX = this.sizeX, sizeY = this.sizeY, sizeZ = this.sizeZ;
        for (int rx = 0; rx < sizeX - 1; rx++) {
            for (int rz = 0; rz < sizeZ - 1; rz++) {
                for (int ry = 0; ry < sizeY - 1; ry++) {
                    this.uniform[this.index(rx, ry, rz)] = this.cubeIsUniform(rx, ry, rz);
                }
            }
        }
    }

    private void computeBiases(long biomeZoomSeed) {
        int sizeX = this.sizeX, sizeY = this.sizeY, sizeZ = this.sizeZ;
        for (int rx = 0; rx < sizeX; rx++) {
            int wx = this.qMinX + rx;
            for (int rz = 0; rz < sizeZ; rz++) {
                int wz = this.qMinZ + rz;
                for (int ry = 0; ry < sizeY; ry++) {
                    this.computeBias(this.index(rx, ry, rz), biomeZoomSeed, wx, this.qMinY + ry, wz);
                }
            }
        }
    }

    private void computeBias(int idx, long seed, int x, int y, int z) {
        long s = LinearCongruentialGenerator.next(seed, x);
        s = LinearCongruentialGenerator.next(s, y);
        s = LinearCongruentialGenerator.next(s, z);
        s = LinearCongruentialGenerator.next(s, x);
        s = LinearCongruentialGenerator.next(s, y);
        s = LinearCongruentialGenerator.next(s, z);
        this.biasX[idx] = this.fiddle(s);
        s = LinearCongruentialGenerator.next(s, seed);
        this.biasY[idx] = this.fiddle(s);
        s = LinearCongruentialGenerator.next(s, seed);
        this.biasZ[idx] = this.fiddle(s);
    }

    private static double fiddle(long l) {
        double f = (double) Math.floorMod(l >> 24, 1024) / 1024.0D;
        return (f - 0.5D) * 0.9D;
    }

    private boolean cubeIsUniform(int rx, int ry, int rz) {
        var biomes = this.biomes;
        Holder<Biome> r = biomes[this.index(rx, ry, rz)];
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    if (biomes[this.index(rx + dx, ry + dy, rz + dz)] != r) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private int index(int rx, int ry, int rz) {
        return (rx * this.sizeY + ry) * this.sizeZ + rz;
    }

    private Holder<Biome> getBiomeWithVoronoi(int i, int j, int k, int rx, int ry, int rz) {
        var biasX = this.biasX;
        var biasY = this.biasY;
        var biasZ = this.biasZ;

        double d0 = (double) QuartPos.quartLocal(i) / 4.0D;
        double d1 = (double) QuartPos.quartLocal(j) / 4.0D;
        double d2 = (double) QuartPos.quartLocal(k) / 4.0D;

        int closest = 0;
        double minDist = Double.POSITIVE_INFINITY;
        for (int c = 0; c < 8; c++) {
            boolean fx = (c & 4) == 0;
            boolean fy = (c & 2) == 0;
            boolean fz = (c & 1) == 0;

            int idx = this.index(
                    rx + (fx ? 0 : 1),
                    ry + (fy ? 0 : 1),
                    rz + (fz ? 0 : 1)
            );
            double dx = (fx ? d0 : d0 - 1.0D) + biasX[idx];
            double dy = (fy ? d1 : d1 - 1.0D) + biasY[idx];
            double dz = (fz ? d2 : d2 - 1.0D) + biasZ[idx];
            double dist = Mth.square(dx) + Mth.square(dy) + Mth.square(dz);
            if (dist < minDist) {
                minDist = dist;
                closest = idx;
            }
        }
        return this.biomes[closest];
    }
}
