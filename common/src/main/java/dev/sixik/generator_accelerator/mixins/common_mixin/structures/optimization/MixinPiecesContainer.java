package dev.sixik.generator_accelerator.mixins.common_mixin.structures.optimization;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(PiecesContainer.class)
public class MixinPiecesContainer {

    @Unique
    private Long2ObjectMap<List<StructurePiece>> bts$chunkGrid;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$initGrid(List<StructurePiece> pieces, CallbackInfo ci) {
        this.bts$chunkGrid = new Long2ObjectOpenHashMap<>();

        for (int i = 0; i < pieces.size(); i++) {
            final StructurePiece piece = pieces.get(i);
            int minX = piece.getBoundingBox().minX() >> 4;
            int maxX = piece.getBoundingBox().maxX() >> 4;
            int minZ = piece.getBoundingBox().minZ() >> 4;
            int maxZ = piece.getBoundingBox().maxZ() >> 4;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    long chunkPos = ChunkPos.asLong(x, z);
                    this.bts$chunkGrid.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(piece);
                }
            }
        }
    }

    /**
     * @author Sixik
     * @reason O(1) search instead of O(N). Instant verification.
     */
    @Overwrite
    public boolean isInsidePiece(BlockPos pos) {
        long chunkPos = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        List<StructurePiece> localPieces = this.bts$chunkGrid.get(chunkPos);

        if (localPieces == null) return false;

        for (int i = 0; i < localPieces.size(); i++) {
            if (localPieces.get(i).getBoundingBox().isInside(pos)) return true;
        }
        return false;
    }
}
