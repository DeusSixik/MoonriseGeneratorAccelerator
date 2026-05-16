package dev.sixik.generator_accelerator.common.surface;

import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

public class FastBlockColumn implements BlockColumn {

    private final ChunkAccess chunk;
    private final BlockPos.MutableBlockPos pos;

    private final int minY;
    private final int maxY;

    private final Heightmap[] heightmaps;
    private final LevelChunkSection[] sections;

    private static final Heightmap.Types[] DEFAULT_MAPS =
            Heightmap.Types.values();

    public FastBlockColumn(
            ChunkAccess chunk, BlockPos.MutableBlockPos pos
    ) {
        this.chunk = chunk;
        this.pos = pos;
        this.minY = this.chunk.getMinBuildHeight();
        this.maxY = this.chunk.getMaxBuildHeight();

        this.heightmaps = new Heightmap[DEFAULT_MAPS.length];

        for (int i1 = 0; i1 < DEFAULT_MAPS.length; i1++) {
            this.heightmaps[i1] = chunk.getOrCreateHeightmapUnprimed(DEFAULT_MAPS[i1]);
        }

        this.sections = chunk.getSections();
    }

    public void lock(LevelChunkSection section) {
        section.acquire();
    }

    public void unlock(LevelChunkSection section) {
        section.release();
    }

    @Override
    public BlockState getBlock(int y) {
        if (y < minY || y >= maxY) {
            return Blocks.VOID_AIR.defaultBlockState();
        }

        final int sectionIndex = (y - minY) >> 4;
        final LevelChunkSection section = sections[sectionIndex];

        final int pX = pos.getX() & 15;
        final int pY = y & 15;
        final int pZ = pos.getZ() & 15;

        return section.getBlockState(pX, pY, pZ);
    }

    @Override
    public void setBlock(int y, BlockState blockState) {
        if (y < minY || y >= maxY) return;
        pos.setY(y);

        final int sectionIndex = (y - minY) >> 4;
        final LevelChunkSection section = sections[sectionIndex];

        final int pX = pos.getX() & 15;
        final int pY = y & 15;
        final int pZ = pos.getZ() & 15;

        section.setBlockState(pX, pY, pZ, blockState, false);
        GAWorkspaceWriteBridge.mirrorCurrent(this.chunk, this.pos, blockState);

        final Heightmap[] heightmaps1 = this.heightmaps;
        for (int i = 0; i < heightmaps1.length; i++) {
            heightmaps1[i].update(pX, y, pZ, blockState);
        }

        if (!blockState.getFluidState().isEmpty()) {
            this.chunk.markPosForPostprocessing(this.pos);
        }
    }
}
