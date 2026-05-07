package dev.sixik.generator_accelerator.common.carver;

import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinLegacyRandomSourceAccessor;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinWorldgenRandomAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

import java.util.function.Function;

public final class CarverChunkPlan {

    private static final long LEGACY_RANDOM_XOR = 0x5DEECE66DL;

    public static final CarverChunkPlan EMPTY = new CarverChunkPlan(
            new ConfiguredWorldCarver<?>[0],
            new long[0],
            new ConfiguredWorldCarver<?>[0],
            new int[0]
    );

    private final ConfiguredWorldCarver<?>[] replayCarvers;
    private final long[] replaySeeds;
    private final ConfiguredWorldCarver<?>[] fallbackCarvers;
    private final int[] fallbackIndexes;

    private CarverChunkPlan(
            ConfiguredWorldCarver<?>[] replayCarvers,
            long[] replaySeeds,
            ConfiguredWorldCarver<?>[] fallbackCarvers,
            int[] fallbackIndexes
    ) {
        this.replayCarvers = replayCarvers;
        this.replaySeeds = replaySeeds;
        this.fallbackCarvers = fallbackCarvers;
        this.fallbackIndexes = fallbackIndexes;
    }

    public static CarverChunkPlan build(
            NoiseBasedChunkGenerator generator,
            BiomeSource biomeSource,
            RandomState randomState,
            GenerationStep.Carving step,
            ChunkAccess sourceChunk,
            long worldSeed,
            WorldgenRandom scratchRandom
    ) {
        ChunkPos sourcePos = sourceChunk.getPos();
        int quartX = QuartPos.fromBlock(sourcePos.getMinBlockX());
        int quartZ = QuartPos.fromBlock(sourcePos.getMinBlockZ());
        BiomeGenerationSettings generationSettings = sourceChunk.carverBiome(
                () -> generator.getBiomeGenerationSettings(biomeSource.getNoiseBiome(quartX, 0, quartZ, randomState.sampler()))
        );

        RandomSource randomSource = ((MixinWorldgenRandomAccessor) scratchRandom).ga$getRandomSource();
        boolean canReplaySeeds = randomSource instanceof MixinLegacyRandomSourceAccessor;
        ConfiguredWorldCarver<?>[] replayCarvers = new ConfiguredWorldCarver[4];
        long[] replaySeeds = new long[4];
        int replayCount = 0;
        ConfiguredWorldCarver<?>[] fallbackCarvers = new ConfiguredWorldCarver[2];
        int[] fallbackIndexes = new int[2];
        int fallbackCount = 0;
        int index = 0;

        for (Holder<ConfiguredWorldCarver<?>> holder : generationSettings.getCarvers(step)) {
            ConfiguredWorldCarver<?> configuredWorldCarver = holder.value();
            if (canReplaySeeds && canReplayStartChunk(configuredWorldCarver)) {
                scratchRandom.setLargeFeatureSeed(worldSeed + index, sourcePos.x, sourcePos.z);
                if (configuredWorldCarver.isStartChunk(scratchRandom)) {
                    if (replayCount == replayCarvers.length) {
                        replayCarvers = growCarvers(replayCarvers);
                        replaySeeds = growSeeds(replaySeeds);
                    }
                    replayCarvers[replayCount] = configuredWorldCarver;
                    replaySeeds[replayCount] = ((MixinLegacyRandomSourceAccessor) randomSource).ga$getSeed().get();
                    replayCount++;
                }
            } else {
                if (fallbackCount == fallbackCarvers.length) {
                    fallbackCarvers = growCarvers(fallbackCarvers);
                    fallbackIndexes = growIndexes(fallbackIndexes);
                }
                fallbackCarvers[fallbackCount] = configuredWorldCarver;
                fallbackIndexes[fallbackCount] = index;
                fallbackCount++;
            }
            index++;
        }

        if (replayCount == 0 && fallbackCount == 0) {
            return EMPTY;
        }

        return new CarverChunkPlan(
                trimCarvers(replayCarvers, replayCount),
                trimSeeds(replaySeeds, replayCount),
                trimCarvers(fallbackCarvers, fallbackCount),
                trimIndexes(fallbackIndexes, fallbackCount)
        );
    }

    public boolean carve(
            CarvingContext carvingContext,
            ChunkAccess targetChunk,
            Function<BlockPos, Holder<Biome>> biomeGetter,
            WorldgenRandom random,
            Aquifer aquifer,
            ChunkPos sourcePos,
            CarvingMask carvingMask,
            long worldSeed
    ) {
        boolean carvedAny = false;

        for (int i = 0; i < this.replayCarvers.length; i++) {
            random.setSeed(this.replaySeeds[i] ^ LEGACY_RANDOM_XOR);
            carvedAny |= this.replayCarvers[i].carve(
                    carvingContext,
                    targetChunk,
                    biomeGetter,
                    random,
                    aquifer,
                    sourcePos,
                    carvingMask
            );
        }

        for (int i = 0; i < this.fallbackCarvers.length; i++) {
            int index = this.fallbackIndexes[i];
            random.setLargeFeatureSeed(worldSeed + index, sourcePos.x, sourcePos.z);
            ConfiguredWorldCarver<?> configuredWorldCarver = this.fallbackCarvers[i];
            if (configuredWorldCarver.isStartChunk(random)) {
                carvedAny |= configuredWorldCarver.carve(
                        carvingContext,
                        targetChunk,
                        biomeGetter,
                        random,
                        aquifer,
                        sourcePos,
                        carvingMask
                );
            }
        }

        return carvedAny;
    }

    private static boolean canReplayStartChunk(ConfiguredWorldCarver<?> configuredWorldCarver) {
        WorldCarver<?> worldCarver = configuredWorldCarver.worldCarver();
        return worldCarver == WorldCarver.CAVE
                || worldCarver == WorldCarver.CANYON
                || worldCarver == WorldCarver.NETHER_CAVE;
    }

    private static ConfiguredWorldCarver<?>[] growCarvers(ConfiguredWorldCarver<?>[] values) {
        ConfiguredWorldCarver<?>[] grown = new ConfiguredWorldCarver[values.length << 1];
        System.arraycopy(values, 0, grown, 0, values.length);
        return grown;
    }

    private static long[] growSeeds(long[] values) {
        long[] grown = new long[values.length << 1];
        System.arraycopy(values, 0, grown, 0, values.length);
        return grown;
    }

    private static int[] growIndexes(int[] values) {
        int[] grown = new int[values.length << 1];
        System.arraycopy(values, 0, grown, 0, values.length);
        return grown;
    }

    private static ConfiguredWorldCarver<?>[] trimCarvers(ConfiguredWorldCarver<?>[] values, int size) {
        if (size == values.length) {
            return values;
        }

        ConfiguredWorldCarver<?>[] trimmed = new ConfiguredWorldCarver[size];
        System.arraycopy(values, 0, trimmed, 0, size);
        return trimmed;
    }

    private static long[] trimSeeds(long[] values, int size) {
        if (size == values.length) {
            return values;
        }

        long[] trimmed = new long[size];
        System.arraycopy(values, 0, trimmed, 0, size);
        return trimmed;
    }

    private static int[] trimIndexes(int[] values, int size) {
        if (size == values.length) {
            return values;
        }

        int[] trimmed = new int[size];
        System.arraycopy(values, 0, trimmed, 0, size);
        return trimmed;
    }
}
