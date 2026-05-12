package dev.sixik.generator_accelerator.common.features.mixin.compats.biomeswevegone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.potionstudios.biomeswevegone.world.level.levelgen.biome.BWGBiomes;
import net.potionstudios.biomeswevegone.world.level.levelgen.customterrain.CragGardenExtension;
import net.potionstudios.biomeswevegone.world.level.levelgen.util.BlendUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Function;

@Mixin(value = CragGardenExtension.class, remap = false)
public abstract class BiomesWeveGone$CragGardenExtension$fast {
    @Unique
    private static final ResourceLocation GA$OVERGROWN_STONE =
            ResourceLocation.fromNamespaceAndPath("biomeswevegone", "overgrown_stone");
    @Unique
    private static final ResourceLocation GA$ROCKY_STONE =
            ResourceLocation.fromNamespaceAndPath("biomeswevegone", "rocky_stone");

    /**
     * @author Sixik
     * @reason Keep BWG's terrain math but remove per-chunk WeightedStateProvider/list
     * construction and avoid one random provider dispatch for every non-top block.
     */
    @Overwrite(remap = false)
    public static void runCragGardenExtension(
            Function<BlockPos, Holder<Biome>> biomeGetter,
            ChunkAccess chunk,
            long worldSeed,
            NormalNoise.NoiseParameters noiseParameters,
            NormalNoise.NoiseParameters cliffSpacingParams
    ) {
        ChunkPos pos = chunk.getPos();
        XoroshiroRandomSource randomSource = new XoroshiroRandomSource(worldSeed);
        XoroshiroRandomSource chunkRandom = new XoroshiroRandomSource(pos.toLong() + worldSeed);
        NormalNoise normalNoise = NormalNoise.create(randomSource, noiseParameters);
        NormalNoise cliffJumpNoise = NormalNoise.create(randomSource, cliffSpacingParams);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        BlockState overgrownStone = BuiltInRegistries.BLOCK.get(GA$OVERGROWN_STONE).defaultBlockState();
        BlockState moss = Blocks.MOSS_BLOCK.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState andesite = Blocks.ANDESITE.defaultBlockState();
        BlockState rockyStone = BuiltInRegistries.BLOCK.get(GA$ROCKY_STONE).defaultBlockState();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        int minBuildHeight = chunk.getMinBuildHeight();

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
                if (!currentBiome.is(BWGBiomes.CRAG_GARDENS)) {
                    continue;
                }

                double normalizedCliffNoise = cliffJumpNoise.getValue((double) worldX * 0.05D, 0.0D, (double) worldZ * 0.05D) + 0.5D;
                int cliffJumpNoiseOffset = Mth.floor(normalizedCliffNoise * 10.0D);
                double blendRadius = BlendUtil.blendBiomeEdge(currentBiome, biomeGetter, mutable, 16, 1);
                int currentSurfaceHeight = (int) ((double) getSurfaceHeight(normalNoise, worldX, worldZ, Math.max(10, cliffJumpNoiseOffset * 2)) * blendRadius);

                for (int y = -5; y <= currentSurfaceHeight; ++y) {
                    int blockY = y + landHeight;
                    mutable.set(worldX, blockY, worldZ);
                    BlockState state = nextStone(chunkRandom, stone, andesite, rockyStone);
                    if (y == currentSurfaceHeight) {
                        mutable.setY(blockY + 1);
                        if (chunk.getBlockState(mutable).getFluidState().isEmpty()) {
                            state = nextTop(chunkRandom, overgrownStone, moss);
                        }
                        mutable.setY(blockY);
                    }
                    chunk.setBlockState(mutable, state, false);
                }
            }
        }
    }

    @Unique
    private static BlockState nextStone(
            RandomSource random,
            BlockState stone,
            BlockState andesite,
            BlockState rockyStone
    ) {
        return switch (random.nextInt(3)) {
            case 0 -> stone;
            case 1 -> andesite;
            default -> rockyStone;
        };
    }

    @Unique
    private static BlockState nextTop(RandomSource random, BlockState overgrownStone, BlockState moss) {
        return random.nextInt(4) == 3 ? moss : overgrownStone;
    }

    @Unique
    private static int getSurfaceHeight(NormalNoise normalNoise, int worldX, int worldZ, int spacing) {
        double normalizedNoise = (normalNoise.getValue((double) worldX * 0.005D, 0.0D, (double) worldZ * 0.005D) + 1.0D) * 0.5D;
        return Mth.floor(normalizedNoise * 50.0D) / spacing * spacing;
    }
}
