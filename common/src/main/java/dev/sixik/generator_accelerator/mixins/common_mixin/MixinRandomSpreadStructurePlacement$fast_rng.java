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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RandomSpreadStructurePlacement.class)
public abstract class MixinRandomSpreadStructurePlacement$fast_rng {
    @Unique
    private static final long GA$LEGACY_MULTIPLIER = 25214903917L;
    @Unique
    private static final long GA$LEGACY_INCREMENT = 11L;
    @Unique
    private static final long GA$LEGACY_MASK = (1L << 48) - 1L;

    @Shadow
    public abstract int spacing();

    @Shadow
    public abstract int separation();

    @Shadow
    @Final
    private RandomSpreadType spreadType;

    /**
     * @author Sixik
     * @reason Avoid allocating WorldgenRandom + LegacyRandomSource while preserving the
     * exact legacy LCG sequence used by RandomSpreadType. Inject instead of overwriting so
     * compatibility mixins can still match vanilla spacing/separation field reads.
     */
    @Inject(method = "getPotentialStructureChunk", at = @At("HEAD"), cancellable = true)
    private void ga$getPotentialStructureChunk(long seed, int chunkX, int chunkZ, CallbackInfoReturnable<ChunkPos> cir) {
        int spacing = this.spacing();
        int separation = this.separation();
        int regionX = Math.floorDiv(chunkX, spacing);
        int regionZ = Math.floorDiv(chunkZ, spacing);
        long randomSeed = ga$largeFeatureSeed(seed, regionX, regionZ, ga$salt());
        int bound = spacing - separation;

        long packed = ga$evaluate(this.spreadType, randomSeed, bound);
        randomSeed = packed >>> 16;
        int offsetX = (int) packed & 0xFFFF;

        packed = ga$evaluate(this.spreadType, randomSeed, bound);
        int offsetZ = (int) packed & 0xFFFF;

        cir.setReturnValue(new ChunkPos(regionX * spacing + offsetX, regionZ * spacing + offsetZ));
    }

    /**
     * @author Sixik
     * @reason Hot structure-start checks only need equality; avoid the ChunkPos return object too.
     */
    @Overwrite
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        int spacing = this.spacing();
        int separation = this.separation();
        int regionX = Math.floorDiv(chunkX, spacing);
        int regionZ = Math.floorDiv(chunkZ, spacing);
        long randomSeed = ga$largeFeatureSeed(state.getLevelSeed(), regionX, regionZ, ga$salt());
        int bound = spacing - separation;

        long packed = ga$evaluate(this.spreadType, randomSeed, bound);
        randomSeed = packed >>> 16;
        int offsetX = (int) packed & 0xFFFF;
        if (regionX * spacing + offsetX != chunkX) {
            return false;
        }

        packed = ga$evaluate(this.spreadType, randomSeed, bound);
        int offsetZ = (int) packed & 0xFFFF;
        return regionZ * spacing + offsetZ == chunkZ;
    }

    @Unique
    private int ga$salt() {
        return ((MixinStructurePlacementAccessor) this).ga$invokeSalt();
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
