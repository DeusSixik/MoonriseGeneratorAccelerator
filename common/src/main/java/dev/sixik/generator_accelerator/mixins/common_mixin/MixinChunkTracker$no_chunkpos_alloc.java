package dev.sixik.generator_accelerator.mixins.common_mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.server.level.ChunkTracker")
public abstract class MixinChunkTracker$no_chunkpos_alloc extends DynamicGraphMinFixedPoint {
    @Shadow
    protected abstract int computeLevelFromNeighbor(long startPos, long endPos, int startLevel);

    private MixinChunkTracker$no_chunkpos_alloc(int levelCount, int expectedLevelSize, int expectedTotalSize) {
        super(levelCount, expectedLevelSize, expectedTotalSize);
    }

    /**
     * @author Sixik
     * @reason Avoid one ChunkPos allocation on every distance-graph propagation check.
     */
    @Overwrite
    protected int getComputedLevel(long pos, long excludedSourcePos, int level) {
        int bestLevel = level;
        int chunkX = ChunkPos.getX(pos);
        int chunkZ = ChunkPos.getZ(pos);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long neighbor = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                if (neighbor == pos) {
                    neighbor = ChunkPos.INVALID_CHUNK_POS;
                }
                if (neighbor == excludedSourcePos) {
                    continue;
                }
                int neighborLevel = this.computeLevelFromNeighbor(neighbor, pos, this.getLevel(neighbor));
                if (bestLevel > neighborLevel) {
                    bestLevel = neighborLevel;
                }
                if (bestLevel == 0) {
                    return bestLevel;
                }
            }
        }
        return bestLevel;
    }
}
