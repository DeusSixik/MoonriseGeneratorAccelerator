package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.DfcOpenClConfig;
import dev.sixik.generator_accelerator.common.density.compiler.opencl.chunk.DfcOpenClChunkRuntime;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceRuntime;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import dev.sixik.generator_accelerator.mixins.common_mixin.accessor.MixinChunkAccessAccessor;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseBasedChunkGenerator$fast_do_fill {
    @Unique
    private static final int GA$POST_PROCESS_INITIAL_CAPACITY = 512;
    @Unique
    private static final GAConfig GA$CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    @Unique
    private static final boolean GA$TERRAIN_SECTION_ONLY_DIRTY = Boolean.parseBoolean(System.getProperty(
            "ga.chunkWorkspace.terrain.sectionOnlyDirty.enabled",
            Boolean.toString(GA$CONFIG.enableWorkspaceTerrainSectionOnlyDirtyTracking)
    ));
    @Unique
    private static final boolean GA$MIRROR_DIRECT_TERRAIN_WRITES = Boolean.parseBoolean(System.getProperty(
            "ga.chunkWorkspace.terrain.mirrorDirectWrites.enabled",
            "false"
    ));

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    private NoiseChunk createNoiseChunk(
            ChunkAccess chunkAccess,
            StructureManager structureManager,
            Blender blender,
            RandomState randomState
    ) {
        throw new AssertionError();
    }

    /**
     * @author Sixik
     * @reason Keep vanilla terrain fill semantics but remove per-block invariant work
     * (settings lookup, debug-void terrain check, preliminary debug bridge and divisions).
     */
    @Overwrite
    public ChunkAccess doFill(
            Blender blender,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess chunkAccess,
            int minCellY,
            int cellCountY
    ) {
        if (DfcOpenClConfig.chunkNoiseEnabled()
                && DfcOpenClChunkRuntime.global().tryFillSingleChunk(
                        blender, structureManager, randomState, chunkAccess, minCellY, cellCountY)) {
            return chunkAccess;
        }
        NoiseChunk noiseChunk = chunkAccess.getOrCreateNoiseChunk(
                chunk -> this.createNoiseChunk(chunk, structureManager, blender, randomState)
        );
        Heightmap oceanFloor = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunkAccess.getPos();
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        boolean debugVoidTerrain = SharedConstants.debugVoidTerrain(chunkPos);
        Aquifer aquifer = noiseChunk.aquifer();
        noiseChunk.initializeForFirstCellX();

        final ShortList[] postProcessingLists = ((MixinChunkAccessAccessor) chunkAccess).ga$getPostProcessing();
        int cellWidth = noiseChunk.cellWidth();
        int cellHeight = noiseChunk.cellHeight();
        double invCellWidth = 1.0D / (double) cellWidth;
        double invCellHeight = 1.0D / (double) cellHeight;
        int cellCountX = 16 / cellWidth;
        int cellCountZ = 16 / cellWidth;
        int topSectionIndex = chunkAccess.getSectionsCount() - 1;
        BlockState defaultBlock = this.settings.value().defaultBlock();
        BlockState air = Blocks.AIR.defaultBlockState();
        long worldSurfaceDone0 = 0L;
        long worldSurfaceDone1 = 0L;
        long worldSurfaceDone2 = 0L;
        long worldSurfaceDone3 = 0L;
        long oceanFloorDone0 = 0L;
        long oceanFloorDone1 = 0L;
        long oceanFloorDone2 = 0L;
        long oceanFloorDone3 = 0L;
        boolean heightmapsDone = false;
        GAChunkWorkspace workspace = GAChunkWorkspaceContext.current();
        boolean workspaceTerrainWrites = workspace != null
                && workspace.blockBufferEnabled()
                && workspace.chunkX() == chunkPos.x
                && workspace.chunkZ() == chunkPos.z
                && GAWorkspaceWriteBridge.workspaceOnlyWritesEnabled()
                && GAChunkWorkspaceRuntime.finalRepackEnabled();
        boolean directLazyAirTerrainWrites = workspaceTerrainWrites
                && GA$TERRAIN_SECTION_ONLY_DIRTY
                && workspace.lazyAirBlockBuffer()
                && workspace.sectionCount() <= Long.SIZE
                && workspace.blockIds() != null
                && workspace.blockCapacity() >= workspace.blockCount();
        int[] workspaceBlockIds = directLazyAirTerrainWrites ? workspace.blockIds() : null;
        int directTerrainSectionCount = directLazyAirTerrainWrites ? workspace.sectionCount() : 0;
        int[] directTerrainWritesBySection = directLazyAirTerrainWrites ? new int[directTerrainSectionCount] : null;
        int[] directTerrainNonEmptyBySection = directLazyAirTerrainWrites ? new int[directTerrainSectionCount] : null;
        int[] directTerrainTickingBlocksBySection = directLazyAirTerrainWrites ? new int[directTerrainSectionCount] : null;
        int[] directTerrainTickingFluidsBySection = directLazyAirTerrainWrites ? new int[directTerrainSectionCount] : null;
        int[] directTerrainLightBySection = directLazyAirTerrainWrites ? new int[directTerrainSectionCount] : null;
        boolean[] directEmptyStates = directLazyAirTerrainWrites ? FastBlockStateCache.EMPTY_STATES : null;
        boolean[] directTickingBlockStates = directLazyAirTerrainWrites ? FastBlockStateCache.RANDOM_TICKING_BLOCK_STATES : null;
        boolean[] directFluidEmptyStates = directLazyAirTerrainWrites ? FastBlockStateCache.FLUID_EMPTY_STATES : null;
        boolean[] directTickingFluidStates = directLazyAirTerrainWrites ? FastBlockStateCache.RANDOM_TICKING_FLUID_STATES : null;
        boolean[] directLightStates = directLazyAirTerrainWrites ? FastBlockStateCache.LIGHT_EMITTING_STATES : null;
        boolean directStateCachesReady = directEmptyStates != null
                && directTickingBlockStates != null
                && directFluidEmptyStates != null
                && directTickingFluidStates != null
                && directLightStates != null;
        long workspaceTerrainWriteCount = 0L;
        long workspacePreparedTerrainSections = 0L;

        for (int cellX = 0; cellX < cellCountX; ++cellX) {
            noiseChunk.advanceCellX(cellX);
            int baseBlockX = minBlockX + cellX * cellWidth;

            for (int cellZ = 0; cellZ < cellCountZ; ++cellZ) {
                int sectionIndex = topSectionIndex;
                LevelChunkSection section = workspaceTerrainWrites ? null : chunkAccess.getSection(sectionIndex);
                LevelChunkSection$FlatBlockArray flatSection = section == null ? null : LevelChunkSection$FlatBlockArray.get(section);
                int loadedSectionIndex = section == null ? Integer.MIN_VALUE : sectionIndex;
                int baseBlockZ = minBlockZ + cellZ * cellWidth;

                for (int cellY = cellCountY - 1; cellY >= 0; --cellY) {
                    noiseChunk.selectCellYZ(cellY, cellZ);

                    for (int localCellY = cellHeight - 1; localCellY >= 0; --localCellY) {
                        int blockY = (minCellY + cellY) * cellHeight + localCellY;
                        int localY = blockY & 15;
                        int newSectionIndex = chunkAccess.getSectionIndex(blockY);
                        if (sectionIndex != newSectionIndex) {
                            sectionIndex = newSectionIndex;
                            if (!workspaceTerrainWrites) {
                                section = chunkAccess.getSection(sectionIndex);
                                flatSection = LevelChunkSection$FlatBlockArray.get(section);
                                loadedSectionIndex = sectionIndex;
                            }
                        }
                        noiseChunk.updateForY(blockY, (double) localCellY * invCellHeight);

                        for (int localCellX = 0; localCellX < cellWidth; ++localCellX) {
                            int blockX = baseBlockX + localCellX;
                            int localX = blockX & 15;
                            noiseChunk.updateForX(blockX, (double) localCellX * invCellWidth);

                            for (int localCellZ = 0; localCellZ < cellWidth; ++localCellZ) {
                                int blockZ = baseBlockZ + localCellZ;
                                int localZ = blockZ & 15;
                                noiseChunk.updateForZ(blockZ, (double) localCellZ * invCellWidth);

                                BlockState blockState = noiseChunk.getInterpolatedState();
                                if (blockState == null) {
                                    blockState = defaultBlock;
                                }
                                if (blockState == air || debugVoidTerrain) {
                                    continue;
                                }

                                int stateId = GA$BlockStateExtension.get(blockState).bts$getFastId();
                                int columnIndex = (localZ << 4) | localX;
                                boolean wroteWorkspaceOnly = false;
                                if (workspaceTerrainWrites) {
                                    int workspaceIndex = ((blockY - workspace.minBuildHeight()) << 8) | columnIndex;
                                    if (directLazyAirTerrainWrites) {
                                        boolean prepared = sectionIndex >= 0 && sectionIndex < directTerrainSectionCount;
                                        if (prepared) {
                                            long sectionBit = 1L << sectionIndex;
                                            prepared = (workspacePreparedTerrainSections & sectionBit) != 0L;
                                            if (!prepared) {
                                                prepared = workspace.prepareTerrainBlockIdWorkspaceOnlySection(sectionIndex);
                                                if (prepared) {
                                                    workspacePreparedTerrainSections |= sectionBit;
                                                }
                                            }
                                        }
                                        if (prepared && workspaceIndex >= 0 && workspaceIndex < workspaceBlockIds.length) {
                                            workspaceBlockIds[workspaceIndex] = stateId;
                                            directTerrainWritesBySection[sectionIndex]++;
                                            if (directStateCachesReady && stateId >= 0 && stateId < directEmptyStates.length) {
                                                if (!directEmptyStates[stateId]) {
                                                    directTerrainNonEmptyBySection[sectionIndex]++;
                                                    if (directTickingBlockStates[stateId]) {
                                                        directTerrainTickingBlocksBySection[sectionIndex]++;
                                                    }
                                                }
                                                if (!directFluidEmptyStates[stateId] && directTickingFluidStates[stateId]) {
                                                    directTerrainTickingFluidsBySection[sectionIndex]++;
                                                }
                                                if (directLightStates[stateId]) {
                                                    directTerrainLightBySection[sectionIndex]++;
                                                }
                                            } else {
                                                if (!FastBlockStateCache.isEmpty(stateId)) {
                                                    directTerrainNonEmptyBySection[sectionIndex]++;
                                                    if (FastBlockStateCache.isRandomlyTickingBlock(stateId)) {
                                                        directTerrainTickingBlocksBySection[sectionIndex]++;
                                                    }
                                                }
                                                if (!FastBlockStateCache.isFluidEmpty(stateId)
                                                        && FastBlockStateCache.isRandomlyTickingFluid(stateId)) {
                                                    directTerrainTickingFluidsBySection[sectionIndex]++;
                                                }
                                                if (FastBlockStateCache.hasLightEmission(stateId)) {
                                                    directTerrainLightBySection[sectionIndex]++;
                                                }
                                            }
                                            wroteWorkspaceOnly = true;
                                        }
                                    } else if (GA$TERRAIN_SECTION_ONLY_DIRTY) {
                                        boolean prepared;
                                        if (sectionIndex >= 0 && sectionIndex < Long.SIZE) {
                                            long sectionBit = 1L << sectionIndex;
                                            prepared = (workspacePreparedTerrainSections & sectionBit) != 0L;
                                            if (!prepared) {
                                                prepared = workspace.prepareTerrainBlockIdWorkspaceOnlySection(sectionIndex);
                                                if (prepared) {
                                                    workspacePreparedTerrainSections |= sectionBit;
                                                }
                                            }
                                        } else {
                                            prepared = workspace.prepareTerrainBlockIdWorkspaceOnlySection(sectionIndex);
                                        }
                                        wroteWorkspaceOnly = prepared
                                                && workspace.writePreparedTerrainBlockIdWorkspaceOnlySectionDirty(
                                                        workspaceIndex,
                                                        sectionIndex,
                                                        stateId
                                                );
                                    } else {
                                        wroteWorkspaceOnly = workspace.writeTerrainBlockIdWorkspaceOnly(
                                                workspaceIndex,
                                                sectionIndex,
                                                columnIndex,
                                                blockY,
                                                stateId
                                        );
                                    }
                                }
                                if (wroteWorkspaceOnly) {
                                    workspaceTerrainWriteCount++;
                                } else {
                                    if (section == null || loadedSectionIndex != sectionIndex) {
                                        section = chunkAccess.getSection(sectionIndex);
                                        flatSection = LevelChunkSection$FlatBlockArray.get(section);
                                        loadedSectionIndex = sectionIndex;
                                    }
                                    int localIndex = (localY << 8) | (localZ << 4) | localX;
                                    if (!flatSection.bts$setRawBlockStateForGeneration(localIndex, stateId)) {
                                        section.setBlockState(localX, localY, localZ, blockState, false);
                                    }
                                    if (GA$MIRROR_DIRECT_TERRAIN_WRITES) {
                                        GAWorkspaceWriteBridge.mirrorCurrent(chunkAccess, blockX, blockY, blockZ, stateId);
                                    }
                                }

                                if (!heightmapsDone) {
                                    long columnBit = 1L << (columnIndex & 63);
                                    if (columnIndex < 64) {
                                        if ((worldSurfaceDone0 & columnBit) == 0L) {
                                            worldSurface.setHeight(localX, localZ, blockY + 1);
                                            worldSurfaceDone0 |= columnBit;
                                        }
                                        if ((oceanFloorDone0 & columnBit) == 0L && blockState.blocksMotion()) {
                                            oceanFloor.setHeight(localX, localZ, blockY + 1);
                                            oceanFloorDone0 |= columnBit;
                                        }
                                    } else if (columnIndex < 128) {
                                        if ((worldSurfaceDone1 & columnBit) == 0L) {
                                            worldSurface.setHeight(localX, localZ, blockY + 1);
                                            worldSurfaceDone1 |= columnBit;
                                        }
                                        if ((oceanFloorDone1 & columnBit) == 0L && blockState.blocksMotion()) {
                                            oceanFloor.setHeight(localX, localZ, blockY + 1);
                                            oceanFloorDone1 |= columnBit;
                                        }
                                    } else if (columnIndex < 192) {
                                        if ((worldSurfaceDone2 & columnBit) == 0L) {
                                            worldSurface.setHeight(localX, localZ, blockY + 1);
                                            worldSurfaceDone2 |= columnBit;
                                        }
                                        if ((oceanFloorDone2 & columnBit) == 0L && blockState.blocksMotion()) {
                                            oceanFloor.setHeight(localX, localZ, blockY + 1);
                                            oceanFloorDone2 |= columnBit;
                                        }
                                    } else {
                                        if ((worldSurfaceDone3 & columnBit) == 0L) {
                                            worldSurface.setHeight(localX, localZ, blockY + 1);
                                            worldSurfaceDone3 |= columnBit;
                                        }
                                        if ((oceanFloorDone3 & columnBit) == 0L && blockState.blocksMotion()) {
                                            oceanFloor.setHeight(localX, localZ, blockY + 1);
                                            oceanFloorDone3 |= columnBit;
                                        }
                                    }
                                    heightmapsDone = (worldSurfaceDone0 & worldSurfaceDone1 & worldSurfaceDone2 & worldSurfaceDone3
                                            & oceanFloorDone0 & oceanFloorDone1 & oceanFloorDone2 & oceanFloorDone3) == -1L;
                                }

                                if (aquifer.shouldScheduleFluidUpdate() && !FastBlockStateCache.isFluidEmpty(stateId)) {
                                    ga$addPackedPostProcessing(postProcessingLists, sectionIndex, localX, localY, localZ);
                                }
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        }

        noiseChunk.stopInterpolation();
        if (directLazyAirTerrainWrites && workspaceTerrainWriteCount > 0L) {
            for (int sectionIndex = 0; sectionIndex < directTerrainSectionCount; sectionIndex++) {
                int writtenBlocks = directTerrainWritesBySection[sectionIndex];
                if (writtenBlocks <= 0) {
                    continue;
                }
                workspace.commitPreparedTerrainSectionOnlyWrites(
                        sectionIndex,
                        directTerrainNonEmptyBySection[sectionIndex],
                        directTerrainTickingBlocksBySection[sectionIndex],
                        directTerrainTickingFluidsBySection[sectionIndex],
                        directTerrainLightBySection[sectionIndex],
                        writtenBlocks
                );
            }
        }
        if (workspaceTerrainWriteCount > 0L) {
            workspace.metrics().addTerrainBlockWrites(workspaceTerrainWriteCount);
            workspace.markTerrainFinalized();
        }
        return chunkAccess;
    }

    @Unique
    private static void ga$addPackedPostProcessing(
            ShortList[] postProcessingLists,
            int sectionIndex,
            int localX,
            int localY,
            int localZ
    ) {
        ShortList list = postProcessingLists[sectionIndex];
        if (list == null) {
            list = new ShortArrayList(GA$POST_PROCESS_INITIAL_CAPACITY);
            postProcessingLists[sectionIndex] = list;
        }
        list.add((short) (localX | (localY << 4) | (localZ << 8)));
    }
}
