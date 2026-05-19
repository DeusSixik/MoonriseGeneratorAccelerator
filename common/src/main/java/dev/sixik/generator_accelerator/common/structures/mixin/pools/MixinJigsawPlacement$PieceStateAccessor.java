package dev.sixik.generator_accelerator.common.structures.mixin.pools;

import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$PieceState")
public interface MixinJigsawPlacement$PieceStateAccessor {
    @Invoker("<init>")
    static Object ga$create(PoolElementStructurePiece piece, MutableObject<VoxelShape> free, int depth) {
        throw new AssertionError();
    }
}
