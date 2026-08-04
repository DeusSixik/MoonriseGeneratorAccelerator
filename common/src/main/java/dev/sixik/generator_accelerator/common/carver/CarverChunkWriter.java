package dev.sixik.generator_accelerator.common.carver;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.EnumSet;

public final class CarverChunkWriter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();

    private static final int AIR_ID = GA$BlockStateExtension.get(AIR).bts$getFastId();

    private final Heightmap[] heightmaps = new Heightmap[Heightmap.Types.values().length];
    private final Heightmap.Types[] heightmapTypes = new Heightmap.Types[Heightmap.Types.values().length];
    private final int[][] rawSections = new int[32][];
    private ChunkAccess chunk;
    private ProtoChunk protoChunk;
    private LevelChunkSection[] sections;
    private ChunkStatus chunkStatus;
    private boolean fastPath;
    private boolean active;
    private int minY;
    private int maxY;
    private int minSection;
    private int heightmapCount;
    private int cachedSectionIndex = Integer.MIN_VALUE;
    private LevelChunkSection cachedSection;

    public void begin(ChunkAccess chunk) {
        this.active = true;
        this.prepareChunk(chunk);
    }

    public void end() {
        if (!this.active && this.chunk == null && this.sections == null) {
            return;
        }
        this.active = false;
        this.chunk = null;
        this.protoChunk = null;
        this.sections = null;
        this.chunkStatus = null;
        this.fastPath = false;
        this.minY = 0;
        this.maxY = 0;
        this.minSection = 0;
        this.heightmapCount = 0;
        this.cachedSectionIndex = Integer.MIN_VALUE;
        this.cachedSection = null;
        Arrays.fill(this.heightmaps, null);
        Arrays.fill(this.heightmapTypes, null);
        Arrays.fill(this.rawSections, null);
    }

    public boolean isActive() {
        return this.active;
    }

    public BlockState getBlockState(BlockPos blockPos) {
        int stateId = this.getStateId(blockPos);
        if (stateId >= 0) {
            return FastBlockStateCache.getBlockState(stateId);
        }

        if (!this.fastPath) {
            return this.chunk.getBlockState(blockPos);
        }

        return VOID_AIR;
    }

    public int getStateId(BlockPos blockPos) {
        Integer workspaceStateId = GAWorkspaceWriteBridge.readBlockIdCurrent(
                blockPos.getX(),
                blockPos.getY(),
                blockPos.getZ()
        );
        if (workspaceStateId != null) {
            return workspaceStateId;
        }

        if (!this.fastPath) {
            return GA$BlockStateExtension.get(this.chunk.getBlockState(blockPos)).bts$getFastId();
        }

        int y = blockPos.getY();
        if (y < this.minY || y >= this.maxY) {
            return -1;
        }

        int sectionIndex = this.sectionIndex(y);
        int[] raw = this.rawSections[sectionIndex];
        int localIndex = ((y & 15) << 8) | ((blockPos.getZ() & 15) << 4) | (blockPos.getX() & 15);
        if (raw != null) {
            return raw[localIndex];
        }

        LevelChunkSection section = this.getSectionByIndex(sectionIndex);
        if (section.hasOnlyAir()) {
            return airStateId();
        }

        return GA$BlockStateExtension.get(section.getBlockState(blockPos.getX() & 15, y & 15, blockPos.getZ() & 15)).bts$getFastId();
    }

    public void setBlockState(BlockPos blockPos, int blockState) {
        this.setStateId(blockPos, blockState);
    }

    public void setStateId(BlockPos blockPos, int stateId) {
        if (!this.fastPath) {
            if (GAWorkspaceWriteBridge.writeCurrentWorkspaceOnly(this.chunk, blockPos, stateId)) {
                return;
            }
            BlockState state = FastBlockStateCache.getBlockState(stateId);
            this.chunk.setBlockState(blockPos, state, false);
            GAWorkspaceWriteBridge.mirrorCurrent(this.chunk, blockPos, stateId);
            return;
        }

        int y = blockPos.getY();
        if (y < this.minY || y >= this.maxY) {
            return;
        }

        LevelChunkSection section = this.getSection(y);
        if (section.hasOnlyAir() && stateId == airStateId()) {
            return;
        }
        BlockState state = FastBlockStateCache.getBlockState(stateId);
        int localX = blockPos.getX() & 15;
        int localY = y & 15;
        int localZ = blockPos.getZ() & 15;
        if (GAWorkspaceWriteBridge.writeCurrentWorkspaceOnly(this.chunk, blockPos, stateId)) {
            GAChunkWorkspace workspace = GAChunkWorkspaceContext.current();
            if (workspace != null) {
                for (int i = 0; i < this.heightmapCount; i++) {
                    if (shouldRecordHeightmapUpdate(this.heightmaps[i], this.heightmapTypes[i], localX, y, localZ, state)) {
                        workspace.recordHeightmapUpdate(this.heightmapTypes[i], localX, y, localZ, stateId);
                    }
                }
            }
            return;
        }
        section.setBlockState(localX, localY, localZ, state, false);
        GAWorkspaceWriteBridge.mirrorCurrent(this.chunk, blockPos, stateId);
        for (int i = 0; i < this.heightmapCount; i++) {
            this.heightmaps[i].update(localX, y, localZ, state);
        }
    }

    public void markPosForPostprocessing(BlockPos blockPos) {
        if (GAWorkspaceWriteBridge.workspaceOnlyWritesEnabled()
                && GAChunkWorkspaceContext.current() != null
                && GAWorkspaceWriteBridge.canWriteCurrentWorkspaceOnly(
                this.chunk,
                blockPos.getX(),
                blockPos.getY(),
                blockPos.getZ()
        )) {
            GAChunkWorkspaceContext.current().recordPostprocessMark(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            return;
        }
        if (!this.fastPath) {
            this.chunk.markPosForPostprocessing(blockPos);
            return;
        }

        int y = blockPos.getY();
        if (y < this.minY || y >= this.maxY) {
            return;
        }

        ChunkAccess.getOrCreateOffsetList(this.chunk.getPostProcessing(), this.sectionIndex(y))
                .add(ProtoChunk.packOffsetCoordinates(blockPos));
    }

    private static boolean shouldRecordHeightmapUpdate(Heightmap heightmap, Heightmap.Types type, int localX, int y, int localZ, BlockState state) {
        int firstAvailable = heightmap.getFirstAvailable(localX, localZ);
        return type.isOpaque().test(state) ? y >= firstAvailable : y == firstAvailable - 1;
    }

    private void prepareChunk(ChunkAccess chunk) {
        ChunkStatus status = chunk.getPersistedStatus();
        if (this.chunk == chunk && this.chunkStatus == status) {
            return;
        }

        this.chunk = chunk;
        this.chunkStatus = status;
        this.protoChunk = chunk instanceof ProtoChunk proto ? proto : null;
        this.sections = chunk.getSections();
        this.minY = chunk.getMinBuildHeight();
        this.maxY = chunk.getMaxBuildHeight();
        this.minSection = chunk.getMinSection();
        this.cachedSectionIndex = Integer.MIN_VALUE;
        this.cachedSection = null;
        this.fastPath = this.protoChunk != null && !status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT);
        this.heightmapCount = 0;
        for (int i = 0; i < this.rawSections.length; i++) {
            this.rawSections[i] = null;
        }

        if (!this.fastPath) {
            return;
        }

        EnumSet<Heightmap.Types> heightmapTypes = status.heightmapsAfter();
        EnumSet<Heightmap.Types> missing = null;
        for (Heightmap.Types type : heightmapTypes) {
            if (!chunk.hasPrimedHeightmap(type)) {
                if (missing == null) {
                    missing = EnumSet.noneOf(Heightmap.Types.class);
                }
                missing.add(type);
            }
        }

        if (missing != null) {
            Heightmap.primeHeightmaps(chunk, missing);
        }

        for (Heightmap.Types type : heightmapTypes) {
            this.heightmapTypes[this.heightmapCount] = type;
            this.heightmaps[this.heightmapCount++] = chunk.getOrCreateHeightmapUnprimed(type);
        }

        for (int i = 0; i < this.sections.length && i < this.rawSections.length; i++) {
            LevelChunkSection section = this.sections[i];
            if (section instanceof LevelChunkSection$FlatBlockArray flatBlockArray) {
                this.rawSections[i] = flatBlockArray.bts$getRawBlockData();
            }
        }
    }

    private LevelChunkSection getSection(int y) {
        int sectionIndex = this.sectionIndex(y);
        return this.getSectionByIndex(sectionIndex);
    }

    private LevelChunkSection getSectionByIndex(int sectionIndex) {
        if (sectionIndex != this.cachedSectionIndex) {
            this.cachedSectionIndex = sectionIndex;
            this.cachedSection = this.sections[sectionIndex];
        }

        return this.cachedSection;
    }

    private int sectionIndex(int y) {
        return (y >> 4) - this.minSection;
    }

    private static int airStateId() {
        return AIR_ID;
    }
}
