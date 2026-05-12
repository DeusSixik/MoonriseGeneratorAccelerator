package dev.sixik.generator_accelerator.common.features.mixin.compats.biomeswevegone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.potionstudios.biomeswevegone.util.GeneratorHeightGetter;
import net.potionstudios.biomeswevegone.world.level.levelgen.biome.BWGBiomes;
import net.potionstudios.biomeswevegone.world.level.levelgen.customterrain.BasaltBarreraExtension;
import net.potionstudios.biomeswevegone.world.level.levelgen.util.BlendUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Function;

@Mixin(value = BasaltBarreraExtension.class, remap = false)
public abstract class BiomesWeveGone$BasaltBarreraExtension$fast {
    @Unique
    private static final ThreadLocal<double[]> GA$HEX_SCRATCH = ThreadLocal.withInitial(() -> new double[2]);

    /**
     * @author Sixik
     * @reason Remove JOML Vector2d/Vector4d and java.util.Random allocations in BWG's
     * basalt barrera terrain extension while preserving its hex/blend formula.
     */
    @Overwrite(remap = false)
    public static void runBasaltBarreraExtension(
            Function<BlockPos, Holder<Biome>> biomeGetter,
            ChunkAccess chunk,
            WorldGenRegion region,
            ChunkGenerator generator
    ) {
        ChunkPos pos = chunk.getPos();
        ImprovedNoise hexRadiusNoise = new ImprovedNoise(new XoroshiroRandomSource(region.getSeed()));
        ImprovedNoise hexHeightNoise = new ImprovedNoise(new XoroshiroRandomSource(region.getSeed() + 2394504L));
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        BlockState basalt = Blocks.BASALT.defaultBlockState();
        BlockState smoothBasalt = Blocks.SMOOTH_BASALT.defaultBlockState();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        int minBuildHeight = chunk.getMinBuildHeight();
        long seed = region.getSeed();

        for (int x = 0; x < 16; ++x) {
            int worldX = baseX + x;
            for (int z = 0; z < 16; ++z) {
                int worldZ = baseZ + z;
                int landHeight = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, worldX, worldZ) / 10 * 10;
                if (minBuildHeight >= landHeight) {
                    continue;
                }

                mutable.set(worldX, landHeight, worldZ);
                Holder<Biome> currentBiome = biomeGetter.apply(mutable);
                if (!currentBiome.is(BWGBiomes.BASALT_BARRERA)) {
                    continue;
                }

                double hexDelta = (hexRadiusNoise.noise((double) worldX * 0.01D, 0.0D, (double) worldZ * 0.01D) + 1.0D) * 0.5D;
                int hexRadius = (int) easeInOutCirc(hexDelta, 4.0D, 10.0D);
                double[] hex = GA$HEX_SCRATCH.get();
                calcHexInfo(worldX, worldZ, hexRadius, hex);
                double hexCenterX = (double) worldX - hex[0];
                double hexCenterZ = (double) worldZ - hex[1];
                int hexBlockX = Mth.floor(hexCenterX);
                int hexBlockZ = Mth.floor(hexCenterZ);

                mutable.set(hexBlockX, landHeight, hexBlockZ);
                Holder<Biome> hexCenterBiome = biomeGetter.apply(mutable);
                if (!hexCenterBiome.is(BWGBiomes.BASALT_BARRERA)) {
                    continue;
                }

                double biomeBlend = BlendUtil.blendBiomeEdge(currentBiome, biomeGetter, mutable, hexRadius * 2, 1);
                ChunkAccess hexCenterChunk = region.getChunk(mutable);
                if (!(hexCenterChunk instanceof GeneratorHeightGetter generatorHeightGetter)) {
                    continue;
                }

                long stateSeed = mutable.asLong() + seed;
                BlockState state = randomBoolean(stateSeed) ? basalt : smoothBasalt;
                int hexHeightOceanFloorHeight = generatorHeightGetter.getHeight(
                        generator,
                        Heightmap.Types.OCEAN_FLOOR_WG,
                        hexBlockX,
                        hexBlockZ,
                        region.getLevel().getChunkSource().randomState(),
                        true
                );
                double heightDelta = (hexHeightNoise.noise(hexCenterX * 0.1D, 0.0D, hexCenterZ * 0.1D) + 1.0D) * 0.5D;
                double addedHeight = easeInOutCirc(heightDelta, 1.0D, 4.0D);
                double topY = (double) hexHeightOceanFloorHeight + addedHeight;
                double blendedY = Mth.clampedLerp((double) landHeight, topY, biomeBlend);

                for (int worldY = landHeight - 5; (double) worldY <= blendedY; ++worldY) {
                    mutable.set(worldX, worldY, worldZ);
                    chunk.setBlockState(mutable, state, false);
                }
            }
        }
    }

    @Unique
    private static double easeInOutCirc(double factor, double min, double max) {
        double x = factor < 0.5D
                ? (1.0D - Math.sqrt(1.0D - (2.0D * factor) * (2.0D * factor))) * 0.5D
                : (Math.sqrt(1.0D - (-2.0D * factor + 2.0D) * (-2.0D * factor + 2.0D)) + 1.0D) * 0.5D;
        return min + (max - min) * x;
    }

    @Unique
    private static void calcHexInfo(double x, double z, double radius, double[] out) {
        double sx = radius;
        double sy = 1.7320508D * radius;
        double centerAX = Math.round(x / sx);
        double centerAZ = Math.round(z / sy);
        double centerBX = Math.round((x - 0.5D * radius) / sx);
        double centerBZ = Math.round((z - radius) / sy);

        double offsetAX = x - centerAX * sx;
        double offsetAZ = z - centerAZ * sy;
        double offsetBX = x - (centerBX + 0.5D) * sx;
        double offsetBZ = z - (centerBZ + 0.5D) * sy;

        double distA = offsetAX * offsetAX + offsetAZ * offsetAZ;
        double distB = offsetBX * offsetBX + offsetBZ * offsetBZ;
        if (distA <= distB) {
            out[0] = offsetAX;
            out[1] = offsetAZ;
        } else {
            out[0] = offsetBX;
            out[1] = offsetBZ;
        }
    }

    @Unique
    private static boolean randomBoolean(long seed) {
        return (nextJavaRandom(seed, 1) & 1) != 0;
    }

    @Unique
    private static int nextJavaRandom(long seed, int bits) {
        long state = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1L);
        state = (state * 25214903917L + 11L) & ((1L << 48) - 1L);
        return (int) (state >>> (48 - bits));
    }
}
