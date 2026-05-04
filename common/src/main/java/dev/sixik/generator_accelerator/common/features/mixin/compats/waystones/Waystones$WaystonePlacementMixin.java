package dev.sixik.generator_accelerator.common.features.mixin.compats.waystones;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.blay09.mods.waystones.config.WaystonesConfig;
import net.blay09.mods.waystones.config.WaystonesConfigData;
import net.blay09.mods.waystones.worldgen.WaystonePlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;

@Mixin(WaystonePlacement.class)
public abstract class Waystones$WaystonePlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Shadow
    @Final
    private Heightmap.Types heightmap;

    @Unique private int bts$chunkDistance = -1;
    @Unique private int bts$maxDeviation;
    @Unique
    private boolean bts$isDimensionAllowed;
    @Unique private boolean bts$configInitialized = false;

    @Unique
    private void bts$initConfigs(PlacementContext context) {
        if (bts$configInitialized) return;

        WaystonesConfigData config = WaystonesConfig.getActive();
        this.bts$chunkDistance = config.worldGen.chunksBetweenWildWaystones;

        if (this.bts$chunkDistance > 0) {
            this.bts$maxDeviation = (int) Math.ceil((double) ((float) this.bts$chunkDistance / 2.0F));

            ResourceLocation dimension = context.getLevel().getLevel().dimension().location();
            Set<ResourceLocation> allowList = config.worldGen.wildWaystonesDimensionAllowList;
            Set<ResourceLocation> denyList = config.worldGen.wildWaystonesDimensionDenyList;

            if (!allowList.isEmpty() && !allowList.contains(dimension)) {
                this.bts$isDimensionAllowed = false;
            } else if (!denyList.isEmpty() && denyList.contains(dimension)) {
                this.bts$isDimensionAllowed = false;
            } else {
                this.bts$isDimensionAllowed = true;
            }
        }
        bts$configInitialized = true;
    }

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        bts$initConfigs(context);

        if (this.bts$chunkDistance == 0 || !this.bts$isDimensionAllowed) {
            return;
        }

        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        int devGridX = chunkX * this.bts$maxDeviation;
        int devGridZ = chunkZ * this.bts$maxDeviation;
        long seed = context.getLevel().getSeed();

        int chunkOffsetX = bts$fastLegacyNextInt((seed * devGridX * devGridZ), this.bts$maxDeviation);
        int chunkOffsetZ = bts$fastLegacyNextInt((seed * devGridX * devGridZ) * 31L, this.bts$maxDeviation);

        if ((chunkX + chunkOffsetX) % this.bts$chunkDistance != 0 || (chunkZ + chunkOffsetZ) % this.bts$chunkDistance != 0) {
            return;
        }

        boolean isNether = context.getLevel().getLevel().dimension() == net.minecraft.world.level.Level.NETHER;

        if (!isNether) {
            int y = context.getHeight(this.heightmap, x, z);
            if (y > context.getMinBuildHeight()) {
                output.add(BlockPos.asLong(x, y, z));
            }
        } else {
            int topMostY = context.getHeight(this.heightmap, x, z);
            int minBuildHeight = context.getMinBuildHeight();
            if (topMostY <= minBuildHeight) return;

            ChunkAccess chunk = context.getLevel().getChunk(chunkX, chunkZ);
            LevelChunkSection[] sections = chunk.getSections();

            int localX = x & 15;
            int localZ = z & 15;

            int sectionIndex = chunk.getSectionIndex(topMostY);
            if (sectionIndex < 0 || sectionIndex >= sections.length) return;

            LevelChunkSection currentSection = sections[sectionIndex];
            int localY = topMostY & 15;

            BlockState stateAbove = currentSection != null && !currentSection.hasOnlyAir() ?
                    currentSection.getBlockState(localX, localY, localZ) : Blocks.AIR.defaultBlockState();

            for (int currentGlobalY = topMostY - 1; currentGlobalY >= minBuildHeight + 1; --currentGlobalY) {
                int currentSectionIndex = chunk.getSectionIndex(currentGlobalY);
                int currentLocalY = currentGlobalY & 15;

                if (currentSectionIndex != sectionIndex) {
                    sectionIndex = currentSectionIndex;
                    if (sectionIndex >= 0 && sectionIndex < sections.length) {
                        currentSection = sections[sectionIndex];
                    } else {
                        currentSection = null;
                    }
                }

                BlockState state;
                if (currentSection != null && !currentSection.hasOnlyAir()) {
                    state = currentSection.getBlockState(localX, currentLocalY, localZ);
                } else {
                    state = Blocks.AIR.defaultBlockState();
                }

                if (!state.isAir() && state.getFluidState().isEmpty() && stateAbove.isAir() && !state.is(Blocks.BEDROCK)) {
                    output.add(BlockPos.asLong(x, currentGlobalY + 1, z));
                    break;
                }
                stateAbove = state;
            }
        }
    }

    /**
     * Эмулятор java.util.Random.nextInt(bound) без аллокаций.
     */
    @Unique
    private int bts$fastLegacyNextInt(long seed, int bound) {
        if (bound <= 0) return 0;
        long currentSeed = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
        currentSeed = (currentSeed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);

        if ((bound & -bound) == bound) {  // i.e., bound is a power of 2
            return (int)((bound * (currentSeed >>> 17)) >> 31);
        }

        int bits, val;
        do {
            currentSeed = (currentSeed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            bits = (int)(currentSeed >>> 17);
            val = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }
}
