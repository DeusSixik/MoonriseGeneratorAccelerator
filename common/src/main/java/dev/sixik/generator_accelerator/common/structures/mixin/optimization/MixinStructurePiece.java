package dev.sixik.generator_accelerator.common.structures.mixin.optimization;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.*;

import java.util.Set;

@Mixin(value = StructurePiece.class)
public abstract class MixinStructurePiece {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> bts$posBuffer = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow protected abstract boolean canBeReplaced(net.minecraft.world.level.LevelReader level, int i, int j, int k, BoundingBox box);

    @Shadow
    private Mirror mirror;

    @Shadow
    private Rotation rotation;

    @Shadow
    @Final
    private static Set<Block> SHAPE_CHECK_BLOCKS;

    @Shadow
    protected abstract int getWorldY(int i);

    @Shadow
    protected abstract int getWorldZ(int i, int j);

    @Shadow
    protected abstract int getWorldX(int i, int j);

    /**
     * @author Sixik
     * @reason Direct writing to a PalettedContainer or raw array.
     */
    @Overwrite
    public void placeBlock(WorldGenLevel worldGenLevel, BlockState in_blockState, int x, int y, int z, BoundingBox boundingBox) {
        BlockPos.MutableBlockPos pos = bts$posBuffer.get();
        pos.set(this.getWorldX(x, z), this.getWorldY(y), this.getWorldZ(x, z));

        if (!boundingBox.isInside(pos) || !this.canBeReplaced(worldGenLevel, x, y, z, boundingBox)) {
            return;
        }

        BlockState blockState = in_blockState;
        if (this.mirror != Mirror.NONE) blockState = blockState.mirror(this.mirror);
        if (this.rotation != Rotation.NONE) blockState = blockState.rotate(this.rotation);

        if (worldGenLevel instanceof net.minecraft.server.level.WorldGenRegion) {

            // ==========================================
            // FAST WAY: Natural Generation (ProtoChunk)
            // ==========================================
            ChunkAccess chunk = worldGenLevel.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            int sectionIndex = chunk.getSectionIndex(pos.getY());

            if (sectionIndex >= 0 && sectionIndex < chunk.getSections().length) {
                LevelChunkSection section = chunk.getSection(sectionIndex);

                int lx = pos.getX() & 15;
                int ly = pos.getY() & 15;
                int lz = pos.getZ() & 15;

                BlockState oldState = section.setBlockState(lx, ly, lz, blockState);

                if (blockState.hasBlockEntity()) {
                    if (chunk.getPersistedStatus().getChunkType() == net.minecraft.world.level.chunk.status.ChunkType.LEVELCHUNK) {
                        net.minecraft.world.level.block.entity.BlockEntity blockEntity = ((net.minecraft.world.level.block.EntityBlock) blockState.getBlock()).newBlockEntity(pos, blockState);
                        if (blockEntity != null) {
                            chunk.setBlockEntity(blockEntity);
                        } else {
                            chunk.removeBlockEntity(pos);
                        }
                    } else {
                        net.minecraft.nbt.CompoundTag compoundTag = new net.minecraft.nbt.CompoundTag();
                        compoundTag.putInt("x", pos.getX());
                        compoundTag.putInt("y", pos.getY());
                        compoundTag.putInt("z", pos.getZ());
                        compoundTag.putString("id", "DUMMY");
                        chunk.setBlockEntityNbt(compoundTag);
                    }
                } else if (oldState != null && oldState.hasBlockEntity()) {
                    chunk.removeBlockEntity(pos);
                }

                if (oldState != blockState) {
                    chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING).update(lx, pos.getY(), lz, blockState);
                    chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).update(lx, pos.getY(), lz, blockState);

                    if (!oldState.isAir() && blockState.isAir()) section.nonEmptyBlockCount--;
                    else if (oldState.isAir() && !blockState.isAir()) section.nonEmptyBlockCount++;

                    if (!oldState.getFluidState().isEmpty() && blockState.getFluidState().isEmpty()) section.tickingFluidCount--;
                    else if (oldState.getFluidState().isEmpty() && !blockState.getFluidState().isEmpty()) section.tickingFluidCount++;
                }

                if (SHAPE_CHECK_BLOCKS.contains(blockState.getBlock())) {
                    chunk.markPosForPostprocessing(pos);
                }

                FluidState fluidState = blockState.getFluidState();
                if (!fluidState.isEmpty()) {
                    worldGenLevel.scheduleTick(pos, fluidState.getType(), 0);
                }
            }
        } else {

            // ==========================================
            // THE SLOW WAY: The /place (ServerLevel) Command
            // ==========================================
            worldGenLevel.setBlock(pos, blockState, 2);

            FluidState fluidState = worldGenLevel.getFluidState(pos);
            if (!fluidState.isEmpty()) {
                worldGenLevel.scheduleTick(pos, fluidState.getType(), 0);
            }
            if (SHAPE_CHECK_BLOCKS.contains(blockState.getBlock())) {
                worldGenLevel.getChunk(pos).markPosForPostprocessing(pos);
            }
        }
    }
}
