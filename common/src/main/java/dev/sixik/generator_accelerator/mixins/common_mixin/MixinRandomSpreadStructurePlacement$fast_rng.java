package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinStructurePlacementAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RandomSpreadStructurePlacement.class)
public abstract class MixinRandomSpreadStructurePlacement$fast_rng {
    @Unique
    private static final long GA$LEGACY_MULTIPLIER = 25214903917L;
    @Unique
    private static final long GA$LEGACY_INCREMENT = 11L;
    @Unique
    private static final long GA$LEGACY_MASK = (1L << 48) - 1L;

    @Shadow
    @Final
    private int spacing;
    @Shadow
    @Final
    private int separation;
    @Shadow
    @Final
    private RandomSpreadType spreadType;

    /**
     * @author Sixik
     * @reason Avoid allocating WorldgenRandom + LegacyRandomSource while preserving the
     * exact legacy LCG sequence used by RandomSpreadType.
     */
    @Overwrite
    public ChunkPos getPotentialStructureChunk(long seed, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, this.spacing);
        int regionZ = Math.floorDiv(chunkZ, this.spacing);
        long randomSeed = ga$largeFeatureSeed(seed, regionX, regionZ, ga$salt());
        int bound = this.spacing - this.separation;

        long packed = ga$evaluate(this.spreadType, randomSeed, bound);
        randomSeed = packed >>> 16;
        int offsetX = (int) packed & 0xFFFF;

        packed = ga$evaluate(this.spreadType, randomSeed, bound);
        int offsetZ = (int) packed & 0xFFFF;

        return new ChunkPos(regionX * this.spacing + offsetX, regionZ * this.spacing + offsetZ);
    }

    /**
     * @author Sixik
     * @reason Hot structure-start checks only need equality; avoid the ChunkPos return object too.
     */
    @Overwrite
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, this.spacing);
        int regionZ = Math.floorDiv(chunkZ, this.spacing);
        long randomSeed = ga$largeFeatureSeed(state.getLevelSeed(), regionX, regionZ, ga$salt());
        int bound = this.spacing - this.separation;

        long packed = ga$evaluate(this.spreadType, randomSeed, bound);
        randomSeed = packed >>> 16;
        int offsetX = (int) packed & 0xFFFF;
        if (regionX * this.spacing + offsetX != chunkX) {
            return false;
        }

        packed = ga$evaluate(this.spreadType, randomSeed, bound);
        int offsetZ = (int) packed & 0xFFFF;
        return regionZ * this.spacing + offsetZ == chunkZ;
    }

    @Unique
    private int ga$salt() {
        return ((MixinStructurePlacementAccessor) this).ga$getSalt();
    }

    @Unique
    private static long ga$largeFeatureSeed(long levelSeed, int regionX, int regionZ, int salt) {
        long seed = (long) regionX * 341873128712L + (long) regionZ * 132897987541L + levelSeed + (long) salt;
        return (seed ^ GA$LEGACY_MULTIPLIER) & GA$LEGACY_MASK;
    }

    @Unique
    private static long ga$evaluate(RandomSpreadType spreadType, long seed, int bound) {
        if (spreadType == RandomSpreadType.LINEAR) {
            return ga$nextInt(seed, bound);
        }

        long first = ga$nextInt(seed, bound);
        long second = ga$nextInt(first >>> 16, bound);
        int value = (((int) first & 0xFFFF) + ((int) second & 0xFFFF)) >> 1;
        return (second & 0xFFFFFFFFFFFF0000L) | (value & 0xFFFFL);
    }

    @Unique
    private static long ga$nextInt(long seed, int bound) {
        long nextSeed = ga$nextSeed(seed);
        int bits = (int) (nextSeed >>> 17);
        int value;
        if ((bound & -bound) == bound) {
            value = (int) ((long) bound * (long) bits >> 31);
        } else {
            value = bits % bound;
            while (bits - value + (bound - 1) < 0) {
                nextSeed = ga$nextSeed(nextSeed);
                bits = (int) (nextSeed >>> 17);
                value = bits % bound;
            }
        }
        return (nextSeed << 16) | (value & 0xFFFFL);
    }

    @Unique
    private static long ga$nextSeed(long seed) {
        return (seed * GA$LEGACY_MULTIPLIER + GA$LEGACY_INCREMENT) & GA$LEGACY_MASK;
    }
}
