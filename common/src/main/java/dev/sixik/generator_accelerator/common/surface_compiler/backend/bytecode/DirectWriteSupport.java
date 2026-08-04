package dev.sixik.generator_accelerator.common.surface_compiler.backend.bytecode;

import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
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
    private static final Heightmap.Types[] HEIGHTMAP_TYPES = Heightmap.Types.values();
    private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private DirectWriteSupport() {
    }

    public static int[] rawBlockDataForSurface(LevelChunkSection$FlatBlockArray section) {
        int[] rawBlockData = section.bts$getRawBlockData();
        if (rawBlockData == null) {
            section.bts$unpackForGeneration();
            rawBlockData = section.bts$getRawBlockData();
            if (rawBlockData == null) {
                throw new IllegalStateException("Surface section raw block data unavailable after unpack");
            }
        }
        return rawBlockData;
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
        boolean fluidState = !state.getFluidState().isEmpty();
        boolean updateHeightmaps = !preservesAllHeightmaps(state);
        boolean mirrorWorkspace = GAChunkWorkspaceContext.current() != null;
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
                writeRawSection(flatBlockArray, chunk, state, stateId, defaultStateId, minBuildY, chunkMinX, chunkMinZ,
                        sectionIndex, topYByColumn, updateHeightmaps, fluidState, mirrorWorkspace, SCRATCH.get());
            } else {
                writeVanillaSection(section, chunk, defaultBlock, state, stateId, minBuildY, chunkMinX, chunkMinZ,
                        sectionIndex, topYByColumn, updateHeightmaps, fluidState, mirrorWorkspace, SCRATCH.get());
            }
        }
        return true;
    }

    private static boolean canWriteAllSections(LevelChunkSection[] sections) {
        for (LevelChunkSection section : sections) {
            if (section == null) {
                return false;
            }
            if (section instanceof LevelChunkSection$FlatBlockArray flatBlockArray) {
                rawBlockDataForSurface(flatBlockArray);
            }
        }
        return true;
    }

    private static void writeRawSection(LevelChunkSection$FlatBlockArray section, ChunkAccess chunk, BlockState state, int stateId, int defaultStateId,
                                        int minBuildY, int chunkMinX, int chunkMinZ, int sectionIndex, int[] topYByColumn,
                                        boolean updateHeightmaps, boolean markPostprocessing, boolean mirrorWorkspace, Scratch scratch) {
        int[] raw = rawBlockDataForSurface(section);
        int[] copy = scratch.copy;
        int[] changedIndices = scratch.changedIndices;
        int changedCount = 0;
        int nonEmptyBlockCount = 0;
        int tickingBlockCount = 0;
        int tickingFluidCount = 0;
        int lightEmissionCount = 0;
        int sectionY = minBuildY + (sectionIndex << 4);
        for (int index = 0; index < 4096; index++) {
            int localX = index & 15;
            int localZ = (index >>> 4) & 15;
            int localY = index >>> 8;
            int newId = raw[index];
            if (newId == defaultStateId && sectionY + localY <= topYByColumn[(localZ << 4) | localX]) {
                newId = stateId;
                changedIndices[changedCount++] = index;
            }
            copy[index] = newId;
            if (!FastBlockStateCache.isEmpty(newId)) {
                nonEmptyBlockCount++;
                if (FastBlockStateCache.isRandomlyTickingBlock(newId)) {
                    tickingBlockCount++;
                }
            }
            if (!FastBlockStateCache.isFluidEmpty(newId)) {
                if (FastBlockStateCache.isRandomlyTickingFluid(newId)) {
                    tickingFluidCount++;
                }
            }
            if (FastBlockStateCache.hasLightEmission(newId)) {
                lightEmissionCount++;
            }
        }
        if (changedCount == 0) {
            return;
        }
        if (tryWorkspaceOnlySection(chunk, state, stateId, minBuildY, chunkMinX, chunkMinZ, sectionIndex,
                changedIndices, changedCount, updateHeightmaps, markPostprocessing)) {
            return;
        }

        if (!section.bts$copyRawBlockDataForGeneration(copy, 0, nonEmptyBlockCount, tickingBlockCount, tickingFluidCount, lightEmissionCount)
                && !section.bts$copyRawBlockDataForGeneration(copy)) {
            return;
        }
        if (!updateHeightmaps && !markPostprocessing && !mirrorWorkspace) {
            return;
        }
        for (int i = 0; i < changedCount; i++) {
            int index = changedIndices[i];
            int localX = index & 15;
            int localZ = (index >>> 4) & 15;
            int localY = (index >>> 8) & 15;
            publishWrite(chunk, chunkMinX + localX, sectionY + localY, chunkMinZ + localZ, state, stateId,
                    updateHeightmaps, markPostprocessing, mirrorWorkspace);
        }
    }

    private static void writeVanillaSection(LevelChunkSection section, ChunkAccess chunk, BlockState defaultBlock, BlockState state, int stateId,
                                            int minBuildY, int chunkMinX, int chunkMinZ, int sectionIndex, int[] topYByColumn,
                                            boolean updateHeightmaps, boolean markPostprocessing, boolean mirrorWorkspace, Scratch scratch) {
        int[] changedIndices = scratch.changedIndices;
        int changedCount = 0;
        for (int localY = 0; localY < 16; localY++) {
            int y = minBuildY + (sectionIndex << 4) + localY;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    if (y <= topYByColumn[(localZ << 4) | localX]
                            && matchesDefault(defaultBlock, section.getBlockState(localX, localY, localZ))) {
                        changedIndices[changedCount++] = localIndex(localX, localY, localZ);
                    }
                }
            }
        }
        if (changedCount == 0) {
            return;
        }
        if (tryWorkspaceOnlySection(chunk, state, stateId, minBuildY, chunkMinX, chunkMinZ, sectionIndex,
                changedIndices, changedCount, updateHeightmaps, markPostprocessing)) {
            return;
        }
        for (int i = 0; i < changedCount; i++) {
            int index = changedIndices[i];
            int localX = index & 15;
            int localZ = (index >>> 4) & 15;
            int localY = (index >>> 8) & 15;
            int y = minBuildY + (sectionIndex << 4) + localY;
            int z = chunkMinZ + localZ;
            section.setBlockState(localX, localY, localZ, state, false);
            publishWrite(chunk, chunkMinX + localX, y, z, state, stateId,
                    updateHeightmaps, markPostprocessing, mirrorWorkspace);
        }
    }


    private static boolean tryWorkspaceOnlySection(
            ChunkAccess chunk,
            BlockState state,
            int stateId,
            int minBuildY,
            int chunkMinX,
            int chunkMinZ,
            int sectionIndex,
            int[] changedIndices,
            int changedCount,
            boolean updateHeightmaps,
            boolean markPostprocessing
    ) {
        if (GAChunkWorkspaceContext.current() == null) {
            return false;
        }
        for (int i = 0; i < changedCount; i++) {
            int index = changedIndices[i];
            int x = chunkMinX + (index & 15);
            int y = minBuildY + (sectionIndex << 4) + ((index >>> 8) & 15);
            int z = chunkMinZ + ((index >>> 4) & 15);
            if (!GAWorkspaceWriteBridge.canWriteCurrentWorkspaceOnly(chunk, x, y, z)) {
                return false;
            }
        }
        for (int i = 0; i < changedCount; i++) {
            int index = changedIndices[i];
            int x = chunkMinX + (index & 15);
            int y = minBuildY + (sectionIndex << 4) + ((index >>> 8) & 15);
            int z = chunkMinZ + ((index >>> 4) & 15);
            if (!writeSurfaceWorkspaceOnly(chunk, x, y, z, state, stateId, updateHeightmaps, markPostprocessing)) {
                return false;
            }
        }
        return true;
    }

    public static boolean writeSurfaceWorkspaceOnly(
            ChunkAccess chunk,
            int x,
            int y,
            int z,
            BlockState state,
            int stateId,
            boolean updateHeightmaps,
            boolean markPostprocessing
    ) {
        GAChunkWorkspace workspace = GAChunkWorkspaceContext.current();
        if (workspace == null || !GAWorkspaceWriteBridge.writeCurrentWorkspaceOnly(chunk, x, y, z, stateId)) {
            return false;
        }
        recordWorkspaceSurfaceSideEffects(chunk, workspace, x, y, z, state, stateId, updateHeightmaps, markPostprocessing);
        return true;
    }

    public static void recordCurrentWorkspaceSurfaceSideEffects(
            ChunkAccess chunk,
            int x,
            int y,
            int z,
            BlockState state,
            int stateId,
            boolean updateHeightmaps,
            boolean markPostprocessing
    ) {
        GAChunkWorkspace workspace = GAChunkWorkspaceContext.current();
        if (workspace != null) {
            recordWorkspaceSurfaceSideEffects(chunk, workspace, x, y, z, state, stateId, updateHeightmaps, markPostprocessing);
        }
    }

    public static void recordWorkspaceSurfaceSideEffects(
            ChunkAccess chunk,
            GAChunkWorkspace workspace,
            int x,
            int y,
            int z,
            BlockState state,
            int stateId,
            boolean updateHeightmaps,
            boolean markPostprocessing
    ) {
        int localX = x & 15;
        int localZ = z & 15;
        if (updateHeightmaps) {
            for (Heightmap.Types type : HEIGHTMAP_TYPES) {
                Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(type);
                int firstAvailable = heightmap.getFirstAvailable(localX, localZ);
                if (type.isOpaque().test(state) ? y >= firstAvailable : y == firstAvailable - 1) {
                    workspace.recordHeightmapUpdate(type, localX, y, localZ, stateId);
                }
            }
        }
        if (markPostprocessing && !state.getFluidState().isEmpty()) {
            workspace.recordPostprocessMark(x, y, z);
        }
    }

    private static void publishWrite(
            ChunkAccess chunk,
            int x,
            int y,
            int z,
            BlockState state,
            int stateId,
            boolean updateHeightmaps,
            boolean markPostprocessing,
            boolean mirrorWorkspace
    ) {
        int localX = x & 15;
        int localZ = z & 15;
        if (updateHeightmaps) {
            for (Heightmap.Types type : HEIGHTMAP_TYPES) {
                chunk.getOrCreateHeightmapUnprimed(type).update(localX, y, localZ, state);
            }
        }
        if (markPostprocessing) {
            BlockPos.MutableBlockPos pos = MUTABLE_POS.get().set(x, y, z);
            chunk.markPosForPostprocessing(pos);
        }
        if (mirrorWorkspace) {
            GAWorkspaceWriteBridge.mirrorCurrent(chunk, x, y, z, stateId);
        }
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

    private static boolean preservesAllHeightmaps(BlockState state) {
        for (Heightmap.Types type : HEIGHTMAP_TYPES) {
            if (!type.isOpaque().test(state)) {
                return false;
            }
        }
        return true;
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

    private static final class Scratch {
        private final int[] copy = new int[4096];
        private final int[] changedIndices = new int[4096];
    }
}
