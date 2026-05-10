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
    private static final int REPLAY_ENTRY = -1;

    public static final CarverChunkPlan EMPTY = new CarverChunkPlan(
            new ConfiguredWorldCarver<?>[0],
            new long[0],
            new int[0]
    );

    private final ConfiguredWorldCarver<?>[] carvers;
    private final long[] replaySeeds;
    private final int[] fallbackIndexes;

    private CarverChunkPlan(
            ConfiguredWorldCarver<?>[] carvers,
            long[] replaySeeds,
            int[] fallbackIndexes
    ) {
        this.carvers = carvers;
        this.replaySeeds = replaySeeds;
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
        // Keep replay and fallback entries interleaved; the carving mask makes first-writer order observable.
        ConfiguredWorldCarver<?>[] carvers = new ConfiguredWorldCarver[4];
        long[] replaySeeds = new long[4];
        int[] fallbackIndexes = new int[4];
        int count = 0;
        int index = 0;

        for (Holder<ConfiguredWorldCarver<?>> holder : generationSettings.getCarvers(step)) {
            ConfiguredWorldCarver<?> configuredWorldCarver = holder.value();
            if (canReplaySeeds && canReplayStartChunk(configuredWorldCarver)) {
                scratchRandom.setLargeFeatureSeed(worldSeed + index, sourcePos.x, sourcePos.z);
                if (configuredWorldCarver.isStartChunk(scratchRandom)) {
                    if (count == carvers.length) {
                        carvers = growCarvers(carvers);
                        replaySeeds = growSeeds(replaySeeds);
                        fallbackIndexes = growIndexes(fallbackIndexes);
                    }
                    carvers[count] = configuredWorldCarver;
                    replaySeeds[count] = ((MixinLegacyRandomSourceAccessor) randomSource).ga$getSeed().get();
                    fallbackIndexes[count] = REPLAY_ENTRY;
                    count++;
                }
            } else {
                if (count == carvers.length) {
                    carvers = growCarvers(carvers);
                    replaySeeds = growSeeds(replaySeeds);
                    fallbackIndexes = growIndexes(fallbackIndexes);
                }
                carvers[count] = configuredWorldCarver;
                fallbackIndexes[count] = index;
                count++;
            }
            index++;
        }

        if (count == 0) {
            return EMPTY;
        }

        return new CarverChunkPlan(
                trimCarvers(carvers, count),
                trimSeeds(replaySeeds, count),
                trimIndexes(fallbackIndexes, count)
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

        for (int i = 0; i < this.carvers.length; i++) {
            ConfiguredWorldCarver<?> configuredWorldCarver = this.carvers[i];
            int fallbackIndex = this.fallbackIndexes[i];
            if (fallbackIndex == REPLAY_ENTRY) {
                random.setSeed(this.replaySeeds[i] ^ LEGACY_RANDOM_XOR);
                carvedAny |= configuredWorldCarver.carve(
                        carvingContext,
                        targetChunk,
                        biomeGetter,
                        random,
                        aquifer,
                        sourcePos,
                        carvingMask
                );
            } else {
                random.setLargeFeatureSeed(worldSeed + fallbackIndex, sourcePos.x, sourcePos.z);
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
