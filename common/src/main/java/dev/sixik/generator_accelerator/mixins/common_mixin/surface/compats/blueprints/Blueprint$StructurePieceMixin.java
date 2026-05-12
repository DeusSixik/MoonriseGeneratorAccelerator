package dev.sixik.generator_accelerator.mixins.common_mixin.surface.compats.blueprints;

import com.bawnorton.mixinsquared.TargetHandler;
import com.teamabnormals.blueprint.common.world.modification.structure.StructureRepalleterManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = StructurePiece.class, priority = 1500)
public class Blueprint$StructurePieceMixin {

    private static BlockState apply(WorldGenLevel level, BlockState state) {
        return StructureRepalleterManager.getBlockState(level, state);
    }

    @ModifyVariable(
            method = "placeBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
                    shift = At.Shift.BEFORE
            ),
            ordinal = 1
    )
    private BlockState zeta$replaceFastPath(BlockState state, WorldGenLevel worldGenLevel, BlockState in_blockState, int x, int y, int z, BoundingBox boundingBox) {
        return apply(worldGenLevel, state);
    }

    @ModifyVariable(
            method = "placeBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
                    shift = At.Shift.BEFORE
            ),
            ordinal = 1
    )
    private BlockState zeta$replaceSlowPath(BlockState state, WorldGenLevel worldGenLevel, BlockState in_blockState, int x, int y, int z, BoundingBox boundingBox) {
        return apply(worldGenLevel, state);
    }
}