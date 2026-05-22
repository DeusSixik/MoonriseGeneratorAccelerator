package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.noise.GAFusedTerrainNoiseChunkAccess;
import dev.sixik.generator_accelerator.common.noise.GANoiseFillMetrics;
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

import java.util.Arrays;

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
        final boolean metricsEnabled = GANoiseFillMetrics.ENABLED;
        final long doFillStart = GANoiseFillMetrics.startTimer();
        long metricDirectAttempts = 0L;
        long metricDirectSolidHits = 0L;
        long metricDirectAirHits = 0L;
        long metricDirectFallbackUnavailable = 0L;
        long metricDirectFallbackOre = 0L;
        long metricDirectFallbackNonSolid = 0L;
        long metricDirectFallbackOob = 0L;
        long metricDirectFallbackOther = 0L;
        long metricSlowSamples = 0L;
        long metricFusedFallbacksToVanilla = 0L;
        long metricUpdateYCalls = 0L;
        long metricUpdateXCalls = 0L;
        long metricUpdateZCalls = 0L;
        long metricSkippedY = 0L;
        long metricSkippedX = 0L;
        long metricSkippedZ = 0L;
        long metricSelectCellCalls = 0L;
        long metricSelectCellNanos = 0L;
        long metricPositiveDensityOreFastSamples = 0L;
        long metricDirectSolidCellFastCells = 0L;
        long metricDirectSolidCellFastBlocks = 0L;
        long metricNegativeGlobalFluidFastSamples = 0L;
        long metricHighAirCellFastCells = 0L;
        long metricHighAirCellFastBlocks = 0L;
        long metricGlobalLavaCellFastCells = 0L;
        long metricGlobalLavaCellFastBlocks = 0L;
        long metricSolidCellBulkWrites = 0L;
        long metricDensityClassifierHits = 0L;
        long metricDensityClassifierScanFallbacks = 0L;

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
        int cellWidthSquared = cellWidth * cellWidth;
        int wholeCellBlocks = cellHeight * cellWidthSquared;
        double invCellWidth = 1.0D / (double) cellWidth;
        double invCellHeight = 1.0D / (double) cellHeight;
        int cellCountX = 16 / cellWidth;
        int cellCountZ = 16 / cellWidth;
        int topSectionIndex = chunkAccess.getSectionsCount() - 1;
        NoiseGeneratorSettings generatorSettings = this.settings.value();
        BlockState defaultBlock = generatorSettings.defaultBlock();
        int defaultBlockId = GA$BlockStateExtension.get(defaultBlock).bts$getFastId();
        BlockState air = Blocks.AIR.defaultBlockState();
        int airBlockId = GA$BlockStateExtension.get(air).bts$getFastId();
        GAFusedTerrainNoiseChunkAccess fusedTerrain = noiseChunk instanceof GAFusedTerrainNoiseChunkAccess access
                && access.ga$fusedTerrainAvailable() ? access : null;
        if (metricsEnabled) {
            if (fusedTerrain != null) {
                GANoiseFillMetrics.increment(GANoiseFillMetrics.FUSED_TERRAIN_CHUNKS);
            }
        }
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
        boolean[] blockMotionStates = FastBlockStateCache.IS_BLOCK_MOTION_STATES;
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
                    int cellMinBlockY = (minCellY + cellY) * cellHeight;
                    long selectStart = metricsEnabled ? System.nanoTime() : 0L;
                    noiseChunk.selectCellYZ(cellY, cellZ);
                    if (metricsEnabled) {
                        metricSelectCellCalls++;
                        metricSelectCellNanos += System.nanoTime() - selectStart;
                    }
                    for (int localCellY = cellHeight - 1; localCellY >= 0; --localCellY) {
                        int blockY = cellMinBlockY + localCellY;
                        int localY = blockY & 15;
                        int cellValueYBase = (cellHeight - 1 - localCellY) * cellWidthSquared;
                        boolean updatedY = false;
                        int newSectionIndex = chunkAccess.getSectionIndex(blockY);
                        if (sectionIndex != newSectionIndex) {
                            sectionIndex = newSectionIndex;
                            if (!workspaceTerrainWrites) {
                                section = chunkAccess.getSection(sectionIndex);
                                flatSection = LevelChunkSection$FlatBlockArray.get(section);
                                loadedSectionIndex = sectionIndex;
                            }
                        }

                        for (int localCellX = 0; localCellX < cellWidth; ++localCellX) {
                            int blockX = baseBlockX + localCellX;
                            int localX = blockX & 15;
                            int cellValueXBase = cellValueYBase + localCellX * cellWidth;
                            boolean updatedX = false;

                            for (int localCellZ = 0; localCellZ < cellWidth; ++localCellZ) {
                                int blockZ = baseBlockZ + localCellZ;
                                int localZ = blockZ & 15;
                                int cellValueIndex = cellValueXBase + localCellZ;

                                int stateId;
                                boolean scheduleFluidUpdate = false;
                                if (!updatedY) {
                                    noiseChunk.updateForY(blockY, (double) localCellY * invCellHeight);
                                    updatedY = true;
                                    if (metricsEnabled) {
                                        metricUpdateYCalls++;
                                    }
                                }
                                if (!updatedX) {
                                    noiseChunk.updateForX(blockX, (double) localCellX * invCellWidth);
                                    updatedX = true;
                                    if (metricsEnabled) {
                                        metricUpdateXCalls++;
                                    }
                                }
                                noiseChunk.updateForZ(blockZ, (double) localCellZ * invCellWidth);
                                if (metricsEnabled) {
                                    metricUpdateZCalls++;
                                    metricSlowSamples++;
                                }

                                if (fusedTerrain != null) {
                                    long packedState = fusedTerrain.ga$sampleFusedTerrainPackedBlockId(
                                            defaultBlockId,
                                            blockX,
                                            blockY,
                                            blockZ
                                    );
                                    if (GAFusedTerrainNoiseChunkAccess.ga$packedFallback(packedState)) {
                                        fusedTerrain = null;
                                        if (metricsEnabled) {
                                            metricFusedFallbacksToVanilla++;
                                        }
                                        stateId = ga$sampleInterpolatedStateId(noiseChunk, defaultBlockId);
                                        scheduleFluidUpdate = aquifer.shouldScheduleFluidUpdate();
                                    } else {
                                        stateId = GAFusedTerrainNoiseChunkAccess.ga$packedBlockId(packedState);
                                        scheduleFluidUpdate = GAFusedTerrainNoiseChunkAccess.ga$packedScheduleFluidUpdate(packedState);
                                    }
                                } else {
                                    stateId = ga$sampleInterpolatedStateId(noiseChunk, defaultBlockId);
                                    scheduleFluidUpdate = aquifer.shouldScheduleFluidUpdate();
                                }
                                if (stateId == airBlockId || debugVoidTerrain) {
                                    continue;
                                }

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
                                        BlockState blockState = stateId == defaultBlockId
                                                ? defaultBlock
                                                : FastBlockStateCache.getBlockState(stateId);
                                        section.setBlockState(localX, localY, localZ, blockState, false);
                                    }
                                }

                                if (!heightmapsDone) {
                                    long columnBit = 1L << (columnIndex & 63);
                                    if (columnIndex < 64) {
                                        if ((worldSurfaceDone0 & columnBit) == 0L) {
                                            worldSurface.setHeight(localX, localZ, blockY + 1);
                                            worldSurfaceDone0 |= columnBit;
                                        }
                                        if ((oceanFloorDone0 & columnBit) == 0L && ga$isBlockMotionState(stateId, blockMotionStates)) {
                                            oceanFloor.setHeight(localX, localZ, blockY + 1);
                                            oceanFloorDone0 |= columnBit;
                                        }
                                    } else if (columnIndex < 128) {
                                        if ((worldSurfaceDone1 & columnBit) == 0L) {
                                            worldSurface.setHeight(localX, localZ, blockY + 1);
                                            worldSurfaceDone1 |= columnBit;
                                        }
                                        if ((oceanFloorDone1 & columnBit) == 0L && ga$isBlockMotionState(stateId, blockMotionStates)) {
                                            oceanFloor.setHeight(localX, localZ, blockY + 1);
                                            oceanFloorDone1 |= columnBit;
                                        }
                                    } else if (columnIndex < 192) {
                                        if ((worldSurfaceDone2 & columnBit) == 0L) {
                                            worldSurface.setHeight(localX, localZ, blockY + 1);
                                            worldSurfaceDone2 |= columnBit;
                                        }
                                        if ((oceanFloorDone2 & columnBit) == 0L && ga$isBlockMotionState(stateId, blockMotionStates)) {
                                            oceanFloor.setHeight(localX, localZ, blockY + 1);
                                            oceanFloorDone2 |= columnBit;
                                        }
                                    } else {
                                        if ((worldSurfaceDone3 & columnBit) == 0L) {
                                            worldSurface.setHeight(localX, localZ, blockY + 1);
                                            worldSurfaceDone3 |= columnBit;
                                        }
                                        if ((oceanFloorDone3 & columnBit) == 0L && ga$isBlockMotionState(stateId, blockMotionStates)) {
                                            oceanFloor.setHeight(localX, localZ, blockY + 1);
                                            oceanFloorDone3 |= columnBit;
                                        }
                                    }
                                    heightmapsDone = (worldSurfaceDone0 & worldSurfaceDone1 & worldSurfaceDone2 & worldSurfaceDone3
                                            & oceanFloorDone0 & oceanFloorDone1 & oceanFloorDone2 & oceanFloorDone3) == -1L;
                                }

                                if (scheduleFluidUpdate && !FastBlockStateCache.isFluidEmpty(stateId)) {
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
        if (metricsEnabled) {
            GANoiseFillMetrics.increment(GANoiseFillMetrics.DO_FILL_CHUNKS);
            GANoiseFillMetrics.addElapsed(GANoiseFillMetrics.DO_FILL_NANOS, doFillStart);
            if (fusedTerrain != null || metricDirectAttempts > 0L) {
                GANoiseFillMetrics.increment(GANoiseFillMetrics.DIRECT_ELIGIBLE_CHUNKS);
            }
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_ATTEMPTS, metricDirectAttempts);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_SOLID_HITS, metricDirectSolidHits);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_AIR_HITS, metricDirectAirHits);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_FALLBACK_UNAVAILABLE, metricDirectFallbackUnavailable);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_FALLBACK_ORE_VEIN_RANGE, metricDirectFallbackOre);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_FALLBACK_NON_SOLID, metricDirectFallbackNonSolid);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_FALLBACK_OOB, metricDirectFallbackOob);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_FALLBACK_OTHER, metricDirectFallbackOther);
            GANoiseFillMetrics.add(GANoiseFillMetrics.SLOW_SAMPLES, metricSlowSamples);
            GANoiseFillMetrics.add(GANoiseFillMetrics.FUSED_FALLBACKS_TO_VANILLA, metricFusedFallbacksToVanilla);
            GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_Y_CALLS, metricUpdateYCalls);
            GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_X_CALLS, metricUpdateXCalls);
            GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_Z_CALLS, metricUpdateZCalls);
            GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_Y_SKIPPED_BY_DIRECT, metricSkippedY);
            GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_X_SKIPPED_BY_DIRECT, metricSkippedX);
            GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_Z_SKIPPED_BY_DIRECT, metricSkippedZ);
            GANoiseFillMetrics.add(GANoiseFillMetrics.SELECT_CELL_CALLS, metricSelectCellCalls);
            GANoiseFillMetrics.add(GANoiseFillMetrics.SELECT_CELL_NANOS, metricSelectCellNanos);
            GANoiseFillMetrics.add(
                    GANoiseFillMetrics.POSITIVE_DENSITY_ORE_FAST_SAMPLES,
                    metricPositiveDensityOreFastSamples
            );
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_SOLID_CELL_FAST_CELLS, metricDirectSolidCellFastCells);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_SOLID_CELL_FAST_BLOCKS, metricDirectSolidCellFastBlocks);
            GANoiseFillMetrics.add(
                    GANoiseFillMetrics.DIRECT_NEGATIVE_GLOBAL_FLUID_FAST_SAMPLES,
                    metricNegativeGlobalFluidFastSamples
            );
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_HIGH_AIR_CELL_FAST_CELLS, metricHighAirCellFastCells);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_HIGH_AIR_CELL_FAST_BLOCKS, metricHighAirCellFastBlocks);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_GLOBAL_LAVA_CELL_FAST_CELLS, metricGlobalLavaCellFastCells);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_GLOBAL_LAVA_CELL_FAST_BLOCKS, metricGlobalLavaCellFastBlocks);
            GANoiseFillMetrics.add(GANoiseFillMetrics.DIRECT_SOLID_CELL_BULK_WRITES, metricSolidCellBulkWrites);
            GANoiseFillMetrics.add(GANoiseFillMetrics.CELL_DENSITY_CLASSIFIER_HITS, metricDensityClassifierHits);
            GANoiseFillMetrics.add(
                    GANoiseFillMetrics.CELL_DENSITY_CLASSIFIER_SCAN_FALLBACKS,
                    metricDensityClassifierScanFallbacks
            );
        }
        return chunkAccess;
    }

    @Unique
    private static int ga$sampleInterpolatedStateId(NoiseChunk noiseChunk, int defaultBlockId) {
        BlockState blockState = noiseChunk.getInterpolatedState();
        return blockState == null ? defaultBlockId : GA$BlockStateExtension.get(blockState).bts$getFastId();
    }

    @Unique
    private static boolean ga$tryFillRawCell(
            ChunkAccess chunkAccess,
            int baseBlockX,
            int cellMinBlockY,
            int baseBlockZ,
            int cellWidth,
            int cellHeight,
            int stateId
    ) {
        int localMinX = baseBlockX & 15;
        int localMaxX = localMinX + cellWidth;
        int localMinZ = baseBlockZ & 15;
        int localMaxZ = localMinZ + cellWidth;
        int cellMaxBlockY = cellMinBlockY + cellHeight;

        for (int y = cellMinBlockY; y < cellMaxBlockY; ) {
            int sectionIndex = chunkAccess.getSectionIndex(y);
            int sectionTopY = (y & ~15) + 16;
            int toY = Math.min(cellMaxBlockY, sectionTopY);
            LevelChunkSection section = chunkAccess.getSection(sectionIndex);
            if (LevelChunkSection$FlatBlockArray.rawData(section) == null) {
                return false;
            }
            y = toY;
        }

        for (int y = cellMinBlockY; y < cellMaxBlockY; ) {
            int sectionIndex = chunkAccess.getSectionIndex(y);
            int sectionTopY = (y & ~15) + 16;
            int toY = Math.min(cellMaxBlockY, sectionTopY);
            LevelChunkSection section = chunkAccess.getSection(sectionIndex);
            LevelChunkSection$FlatBlockArray flatSection = LevelChunkSection$FlatBlockArray.get(section);
            if (!flatSection.bts$fillRawBlockStateBoxForGeneration(
                    localMinX,
                    localMaxX,
                    y & 15,
                    (toY - 1 & 15) + 1,
                    localMinZ,
                    localMaxZ,
                    stateId
            )) {
                return false;
            }
            y = toY;
        }
        return true;
    }

    @Unique
    private static int ga$tryFillWorkspaceCell(
            ChunkAccess chunkAccess,
            GAChunkWorkspace workspace,
            int[] workspaceBlockIds,
            int[] writesBySection,
            int[] nonEmptyBySection,
            int[] tickingBlocksBySection,
            int[] tickingFluidsBySection,
            int[] lightBySection,
            boolean[] emptyStates,
            boolean[] tickingBlockStates,
            boolean[] fluidEmptyStates,
            boolean[] tickingFluidStates,
            boolean[] lightStates,
            boolean directStateCachesReady,
            int baseBlockX,
            int cellMinBlockY,
            int baseBlockZ,
            int cellWidth,
            int cellHeight,
            int stateId
    ) {
        if (workspace == null || workspaceBlockIds == null || writesBySection == null) {
            return -1;
        }
        int cellMaxBlockY = cellMinBlockY + cellHeight;
        for (int y = cellMinBlockY; y < cellMaxBlockY; ) {
            int sectionIndex = chunkAccess.getSectionIndex(y);
            if (sectionIndex < 0 || sectionIndex >= writesBySection.length
                    || !workspace.prepareTerrainBlockIdWorkspaceOnlySection(sectionIndex)) {
                return -1;
            }
            int sectionTopY = (y & ~15) + 16;
            y = Math.min(cellMaxBlockY, sectionTopY);
        }

        int localMinX = baseBlockX & 15;
        int localMaxX = localMinX + cellWidth;
        int localMinZ = baseBlockZ & 15;
        int localMaxZ = localMinZ + cellWidth;
        int written = 0;
        boolean nonEmpty;
        boolean tickingBlock;
        boolean fluidEmpty;
        boolean tickingFluid;
        boolean light;
        if (directStateCachesReady && stateId >= 0 && stateId < emptyStates.length) {
            nonEmpty = !emptyStates[stateId];
            tickingBlock = tickingBlockStates[stateId];
            fluidEmpty = fluidEmptyStates[stateId];
            tickingFluid = tickingFluidStates[stateId];
            light = lightStates[stateId];
        } else {
            nonEmpty = !FastBlockStateCache.isEmpty(stateId);
            tickingBlock = FastBlockStateCache.isRandomlyTickingBlock(stateId);
            fluidEmpty = FastBlockStateCache.isFluidEmpty(stateId);
            tickingFluid = FastBlockStateCache.isRandomlyTickingFluid(stateId);
            light = FastBlockStateCache.hasLightEmission(stateId);
        }

        for (int y = cellMinBlockY; y < cellMaxBlockY; y++) {
            int sectionIndex = chunkAccess.getSectionIndex(y);
            int rowBase = (y - workspace.minBuildHeight()) << 8;
            if (rowBase < 0 || rowBase >= workspaceBlockIds.length) {
                return -1;
            }
            int rowWritten = 0;
            for (int localZ = localMinZ; localZ < localMaxZ; localZ++) {
                int from = rowBase | (localZ << 4) | localMinX;
                int to = from + (localMaxX - localMinX);
                if (from < 0 || to > workspaceBlockIds.length) {
                    return -1;
                }
                Arrays.fill(workspaceBlockIds, from, to, stateId);
                rowWritten += to - from;
            }
            writesBySection[sectionIndex] += rowWritten;
            if (nonEmpty) {
                nonEmptyBySection[sectionIndex] += rowWritten;
                if (tickingBlock) {
                    tickingBlocksBySection[sectionIndex] += rowWritten;
                }
            }
            if (!fluidEmpty && tickingFluid) {
                tickingFluidsBySection[sectionIndex] += rowWritten;
            }
            if (light) {
                lightBySection[sectionIndex] += rowWritten;
            }
            written += rowWritten;
        }
        return written;
    }

    @Unique
    private static boolean ga$isBlockMotionState(int stateId, boolean[] blockMotionStates) {
        return stateId >= 0 && blockMotionStates != null && stateId < blockMotionStates.length
                ? blockMotionStates[stateId]
                : FastBlockStateCache.isBlockMotion(stateId);
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
