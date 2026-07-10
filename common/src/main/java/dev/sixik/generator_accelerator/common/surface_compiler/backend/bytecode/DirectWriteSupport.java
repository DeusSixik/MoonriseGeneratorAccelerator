package dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import dev.sixik.generator_accelerator.common.surface_compiler.runtime.SurfaceExecutionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceSystem;

import java.lang.reflect.Field;

/** Stable runtime helper used by generated direct Tier 0 kernels. */
public final class DirectWriteSupport {
    private static final Field DEFAULT_BLOCK = defaultBlockField();

    private DirectWriteSupport() {
    }

    public static boolean fillChunkDirect(SurfaceExecutionContext context, BlockState state) {
        if (context == null || context.chunk() == null || context.surfaceSystem() == null || state == null || DEFAULT_BLOCK == null) {
            return false;
        }
        BlockState defaultBlock = defaultBlock(context.surfaceSystem());
        if (defaultBlock == null) {
            return false;
        }
        ChunkAccess chunk = context.chunk();
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return false;
        }

        int stateId = Block.getId(state);
        int defaultStateId = Block.getId(defaultBlock);
        int minBuildY = chunk.getMinBuildHeight();
        ChunkPos chunkPos = chunk.getPos();
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMinZ = chunkPos.getMinBlockZ();
        int[] topYByColumn = topYByColumn(chunk);

        if (!canWriteAllSections(sections)) {
            return false;
        }

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section instanceof LevelChunkSection$FlatBlockArray flatBlockArray) {
                writeRawSection(flatBlockArray, chunk, state, stateId, defaultStateId, minBuildY, chunkMinX, chunkMinZ, sectionIndex, topYByColumn);
            } else {
                writeVanillaSection(section, chunk, defaultBlock, state, minBuildY, chunkMinX, chunkMinZ, sectionIndex, topYByColumn);
            }
        }
        return true;
    }

    private static boolean canWriteAllSections(LevelChunkSection[] sections) {
        for (LevelChunkSection section : sections) {
            if (section == null) {
                return false;
            }
            if (section instanceof LevelChunkSection$FlatBlockArray flatBlockArray && flatBlockArray.bts$getRawBlockData() == null) {
                return false;
            }
        }
        return true;
    }

    private static void writeRawSection(LevelChunkSection$FlatBlockArray section, ChunkAccess chunk, BlockState state, int stateId, int defaultStateId,
                                        int minBuildY, int chunkMinX, int chunkMinZ, int sectionIndex, int[] topYByColumn) {
        int[] raw = section.bts$getRawBlockData();
        int[] copy = raw.clone();
        boolean[] changedMask = new boolean[4096];
        boolean changed = false;
        for (int localY = 0; localY < 16; localY++) {
            int y = minBuildY + (sectionIndex << 4) + localY;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    if (y > topYByColumn[(localZ << 4) | localX]) {
                        continue;
                    }
                    int index = localIndex(localX, localY, localZ);
                    if (raw[index] == defaultStateId) {
                        copy[index] = stateId;
                        changedMask[index] = true;
                        changed = true;
                    }
                }
            }
        }
        if (!changed) {
            return;
        }
        if (!section.bts$copyRawBlockDataForGeneration(copy)) {
            return;
        }
        for (int localY = 0; localY < 16; localY++) {
            int y = minBuildY + (sectionIndex << 4) + localY;
            for (int localZ = 0; localZ < 16; localZ++) {
                int z = chunkMinZ + localZ;
                for (int localX = 0; localX < 16; localX++) {
                    if (changedMask[localIndex(localX, localY, localZ)]) {
                        publishWrite(chunk, new BlockPos(chunkMinX + localX, y, z), state);
                    }
                }
            }
        }
    }

    private static void writeVanillaSection(LevelChunkSection section, ChunkAccess chunk, BlockState defaultBlock, BlockState state,
                                            int minBuildY, int chunkMinX, int chunkMinZ, int sectionIndex, int[] topYByColumn) {
        for (int localY = 0; localY < 16; localY++) {
            int y = minBuildY + (sectionIndex << 4) + localY;
            for (int localZ = 0; localZ < 16; localZ++) {
                int z = chunkMinZ + localZ;
                for (int localX = 0; localX < 16; localX++) {
                    if (y > topYByColumn[(localZ << 4) | localX]) {
                        continue;
                    }
                    BlockState current = section.getBlockState(localX, localY, localZ);
                    if (matchesDefault(defaultBlock, current)) {
                        section.setBlockState(localX, localY, localZ, state, false);
                        publishWrite(chunk, new BlockPos(chunkMinX + localX, y, z), state);
                    }
                }
            }
        }
    }

    private static void publishWrite(ChunkAccess chunk, BlockPos pos, BlockState state) {
        for (Heightmap.Types type : Heightmap.Types.values()) {
            chunk.getOrCreateHeightmapUnprimed(type).update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, state);
        }
        if (!state.getFluidState().isEmpty()) {
            chunk.markPosForPostprocessing(pos);
        }
        GAWorkspaceWriteBridge.mirrorCurrent(chunk, pos, state);
    }

    private static int[] topYByColumn(ChunkAccess chunk) {
        int[] out = new int[256];
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                out[(localZ << 4) | localX] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ) + 1;
            }
        }
        return out;
    }

    private static int localIndex(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    private static boolean matchesDefault(BlockState defaultBlock, BlockState current) {
        return current == defaultBlock || current.equals(defaultBlock);
    }

    private static BlockState defaultBlock(SurfaceSystem surfaceSystem) {
        try {
            return (BlockState) DEFAULT_BLOCK.get(surfaceSystem);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Field defaultBlockField() {
        try {
            Field field = SurfaceSystem.class.getDeclaredField("defaultBlock");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
