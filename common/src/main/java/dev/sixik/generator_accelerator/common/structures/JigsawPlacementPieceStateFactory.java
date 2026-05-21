package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableObject;

public final class JigsawPlacementPieceStateFactory {
    private JigsawPlacementPieceStateFactory() {
    }

    public static Object create(PoolElementStructurePiece piece, MutableObject<VoxelShape> free, int depth) {
        return new JigsawPlacement.PieceState(piece, free, depth);
    }
}
