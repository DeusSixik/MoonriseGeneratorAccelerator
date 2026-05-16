package dev.sixik.generator_accelerator.common.features.compat.biomeswevegone;

import dev.sixik.generator_accelerator.common.biome.GARawBiomeResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.Arrays;
import java.util.function.Function;

public final class GABWGBiomeCache implements Function<BlockPos, Holder<Biome>> {
    private static final int CAPACITY = 8192;
    private static final int MASK = CAPACITY - 1;
    private static final int MAX_FILL = CAPACITY * 3 / 4;

    private final long[] keys = new long[CAPACITY];
    @SuppressWarnings("unchecked")
    private final Holder<Biome>[] values = (Holder<Biome>[]) new Holder[CAPACITY];
    private final int[] stamps = new int[CAPACITY];
    private final long[] rawTarget = new long[6];

    private static final long LCG_MUL = 6364136223846793005L;
    private static final long LCG_ADD = 1442695040888963407L;
    private static final boolean RAW_BIOME_LOOKUP =
            Boolean.parseBoolean(System.getProperty("ga.bwg.rawBiomeLookup", "true"));

    private BiomeSource biomeSource;
    private GARawBiomeResolver rawResolverCandidate;
    private GARawBiomeResolver rawResolver;
    private Climate.Sampler sampler;
    private long biomeZoomSeed;
    private int stamp = 1;
    private int size;

    public GABWGBiomeCache reset(ChunkGenerator generator, Climate.Sampler sampler, long worldSeed) {
        BiomeSource source = generator.getBiomeSource();
        this.biomeSource = source;
        this.sampler = sampler;
        this.rawResolverCandidate = RAW_BIOME_LOOKUP && source instanceof GARawBiomeResolver resolver ? resolver : null;
        this.rawResolver = this.rawResolverCandidate != null && this.rawResolverCandidate.ga$hasRawBiomeLookup(sampler)
                ? this.rawResolverCandidate
                : null;
        this.biomeZoomSeed = net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(worldSeed);
        this.size = 0;
        this.stamp++;
        if (this.stamp == 0) {
            Arrays.fill(this.stamps, 0);
            this.stamp = 1;
        }
        return this;
    }

    @Override
    public Holder<Biome> apply(BlockPos pos) {
        long key = pack(pos.getX(), pos.getZ());
        int index = mix(key) & MASK;
        int localStamp = this.stamp;

        while (this.stamps[index] == localStamp) {
            if (this.keys[index] == key) {
                return this.values[index];
            }
            index = (index + 1) & MASK;
        }

        Holder<Biome> value = this.getBiome(pos);
        if (this.size < MAX_FILL) {
            this.stamps[index] = localStamp;
            this.keys[index] = key;
            this.values[index] = value;
            this.size++;
        }
        return value;
    }

    private Holder<Biome> getBiome(BlockPos pos) {
        int x = pos.getX() - 2;
        int y = pos.getY() - 2;
        int z = pos.getZ() - 2;

        int quartX = x >> 2;
        int quartY = y >> 2;
        int quartZ = z >> 2;

        double fracX = (double) (x & 3) * 0.25D;
        double fracY = (double) (y & 3) * 0.25D;
        double fracZ = (double) (z & 3) * 0.25D;

        int bestCornerIndex = 0;
        double minDistance = Double.POSITIVE_INFINITY;
        long seed = this.biomeZoomSeed;

        for (int corner = 0; corner < 8; ++corner) {
            boolean useMinX = (corner & 4) == 0;
            boolean useMinY = (corner & 2) == 0;
            boolean useMinZ = (corner & 1) == 0;

            int candidateX = useMinX ? quartX : quartX + 1;
            int candidateY = useMinY ? quartY : quartY + 1;
            int candidateZ = useMinZ ? quartZ : quartZ + 1;

            double offsetX = useMinX ? fracX : fracX - 1.0D;
            double offsetY = useMinY ? fracY : fracY - 1.0D;
            double offsetZ = useMinZ ? fracZ : fracZ - 1.0D;

            long mixed = lcgNext(seed, candidateX);
            mixed = lcgNext(mixed, candidateY);
            mixed = lcgNext(mixed, candidateZ);
            mixed = lcgNext(mixed, candidateX);
            mixed = lcgNext(mixed, candidateY);
            mixed = lcgNext(mixed, candidateZ);

            double fiddleX = fiddle(mixed);
            mixed = lcgNext(mixed, seed);
            double fiddleY = fiddle(mixed);
            mixed = lcgNext(mixed, seed);
            double fiddleZ = fiddle(mixed);

            double distance = square(offsetZ + fiddleZ) + square(offsetY + fiddleY) + square(offsetX + fiddleX);
            if (distance < minDistance) {
                bestCornerIndex = corner;
                minDistance = distance;
            }
        }

        int finalX = (bestCornerIndex & 4) == 0 ? quartX : quartX + 1;
        int finalY = (bestCornerIndex & 2) == 0 ? quartY : quartY + 1;
        int finalZ = (bestCornerIndex & 1) == 0 ? quartZ : quartZ + 1;
        GARawBiomeResolver resolver = this.rawResolver;
        if (resolver == null) {
            GARawBiomeResolver candidate = this.rawResolverCandidate;
            if (candidate != null && candidate.ga$hasRawBiomeLookup(this.sampler)) {
                this.rawResolver = resolver = candidate;
            }
        }
        return resolver == null
                ? this.biomeSource.getNoiseBiome(finalX, finalY, finalZ, this.sampler)
                : resolver.ga$getRawNoiseBiome(finalX, finalY, finalZ, this.sampler, this.rawTarget);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) value;
    }

    private static long lcgNext(long value, long salt) {
        return value * (value * LCG_MUL + LCG_ADD) + salt;
    }

    private static double fiddle(long value) {
        double normalized = (double) ((int) (value >>> 24) & 1023) * (1.0D / 1024.0D);
        return (normalized - 0.5D) * 0.9D;
    }

    private static double square(double value) {
        return value * value;
    }
}
