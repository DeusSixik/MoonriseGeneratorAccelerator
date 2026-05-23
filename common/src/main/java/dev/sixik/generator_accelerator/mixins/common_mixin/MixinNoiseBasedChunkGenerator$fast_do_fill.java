package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import dev.sixik.generator_accelerator.common.noise.GANoiseFillMetrics;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspace;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceContext;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAChunkWorkspaceRuntime;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import dev.sixik.generator_accelerator.diagnostics.GAWallTimeTelemetry;
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
    @Unique
    private static final boolean GA$SECTION_LOCAL_RAW_COUNTERS = Boolean.parseBoolean(System.getProperty(
            "ga.noiseFill.sectionLocalRawCounters.enabled",
            "true"
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
        long metricSlowSamples = 0L;
        long metricUpdateYCalls = 0L;
        long metricUpdateXCalls = 0L;
        long metricUpdateZCalls = 0L;
        long metricSelectCellCalls = 0L;
        long metricSelectCellNanos = 0L;
        long metricAquiferScheduleChecks = 0L;
        long metricNonAirSamples = 0L;
        long metricSectionLocalRawCommits = 0L;
        long metricSectionLocalRawWrites = 0L;
        long metricSectionLocalRawFallbacks = 0L;
        final long wallTelemetryStart = GAWallTimeTelemetry.start(GAWallTimeTelemetry.Stage.NOISE_DO_FILL);

        try {
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
        boolean sectionLocalRawCountersEnabled = GA$SECTION_LOCAL_RAW_COUNTERS && !workspaceTerrainWrites;
        boolean[] sectionLocalEmptyStates = sectionLocalRawCountersEnabled ? FastBlockStateCache.EMPTY_STATES : null;
        boolean[] sectionLocalTickingBlockStates = sectionLocalRawCountersEnabled
                ? FastBlockStateCache.RANDOM_TICKING_BLOCK_STATES
                : null;
        boolean[] sectionLocalFluidEmptyStates = sectionLocalRawCountersEnabled ? FastBlockStateCache.FLUID_EMPTY_STATES : null;
        boolean[] sectionLocalTickingFluidStates = sectionLocalRawCountersEnabled
                ? FastBlockStateCache.RANDOM_TICKING_FLUID_STATES
                : null;
        boolean[] sectionLocalLightStates = sectionLocalRawCountersEnabled ? FastBlockStateCache.LIGHT_EMITTING_STATES : null;
        boolean sectionLocalStateCachesReady = sectionLocalEmptyStates != null
                && sectionLocalTickingBlockStates != null
                && sectionLocalFluidEmptyStates != null
                && sectionLocalTickingFluidStates != null
                && sectionLocalLightStates != null;
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
                boolean sectionLocalRawWriter = sectionLocalRawCountersEnabled
                        && flatSection != null
                        && flatSection.bts$isRawStartedOnlyAirForGeneration();
                int sectionLocalNonEmpty = 0;
                int sectionLocalTickingBlocks = 0;
                int sectionLocalTickingFluids = 0;
                int sectionLocalLight = 0;
                int sectionLocalWrites = 0;
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
                            if (sectionLocalRawWriter && sectionLocalWrites > 0) {
                                flatSection.bts$commitRawStartedOnlyAirGenerationWrites(
                                        sectionLocalNonEmpty,
                                        sectionLocalTickingBlocks,
                                        sectionLocalTickingFluids,
                                        sectionLocalLight,
                                        sectionLocalWrites
                                );
                                if (metricsEnabled) {
                                    metricSectionLocalRawCommits++;
                                    metricSectionLocalRawWrites += sectionLocalWrites;
                                }
                                sectionLocalNonEmpty = 0;
                                sectionLocalTickingBlocks = 0;
                                sectionLocalTickingFluids = 0;
                                sectionLocalLight = 0;
                                sectionLocalWrites = 0;
                            }
                            sectionIndex = newSectionIndex;
                            if (!workspaceTerrainWrites) {
                                section = chunkAccess.getSection(sectionIndex);
                                flatSection = LevelChunkSection$FlatBlockArray.get(section);
                                loadedSectionIndex = sectionIndex;
                                sectionLocalRawWriter = sectionLocalRawCountersEnabled
                                        && flatSection.bts$isRawStartedOnlyAirForGeneration();
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

                                stateId = ga$sampleInterpolatedStateId(noiseChunk, defaultBlockId);
                                scheduleFluidUpdate = aquifer.shouldScheduleFluidUpdate();
                                if (metricsEnabled) {
                                    metricAquiferScheduleChecks++;
                                }
                                if (stateId == airBlockId || debugVoidTerrain) {
                                    continue;
                                }
                                if (metricsEnabled) {
                                    metricNonAirSamples++;
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
                                        if (sectionLocalRawWriter && sectionLocalWrites > 0) {
                                            flatSection.bts$commitRawStartedOnlyAirGenerationWrites(
                                                    sectionLocalNonEmpty,
                                                    sectionLocalTickingBlocks,
                                                    sectionLocalTickingFluids,
                                                    sectionLocalLight,
                                                    sectionLocalWrites
                                            );
                                            if (metricsEnabled) {
                                                metricSectionLocalRawCommits++;
                                                metricSectionLocalRawWrites += sectionLocalWrites;
                                            }
                                            sectionLocalNonEmpty = 0;
                                            sectionLocalTickingBlocks = 0;
                                            sectionLocalTickingFluids = 0;
                                            sectionLocalLight = 0;
                                            sectionLocalWrites = 0;
                                        }
                                        section = chunkAccess.getSection(sectionIndex);
                                        flatSection = LevelChunkSection$FlatBlockArray.get(section);
                                        loadedSectionIndex = sectionIndex;
                                        sectionLocalRawWriter = sectionLocalRawCountersEnabled
                                                && flatSection.bts$isRawStartedOnlyAirForGeneration();
                                    }
                                    int localIndex = (localY << 8) | (localZ << 4) | localX;
                                    int rawWriteResult = sectionLocalRawWriter
                                            ? flatSection.bts$setRawBlockStateStartedOnlyAirNoCountersForGeneration(
                                                    localIndex,
                                                    stateId
                                            )
                                            : -1;
                                    if (rawWriteResult > 0) {
                                        sectionLocalWrites++;
                                        if (sectionLocalStateCachesReady
                                                && stateId >= 0
                                                && stateId < sectionLocalEmptyStates.length) {
                                            if (!sectionLocalEmptyStates[stateId]) {
                                                sectionLocalNonEmpty++;
                                                if (sectionLocalTickingBlockStates[stateId]) {
                                                    sectionLocalTickingBlocks++;
                                                }
                                            }
                                            if (!sectionLocalFluidEmptyStates[stateId]
                                                    && sectionLocalTickingFluidStates[stateId]) {
                                                sectionLocalTickingFluids++;
                                            }
                                            if (sectionLocalLightStates[stateId]) {
                                                sectionLocalLight++;
                                            }
                                        } else {
                                            if (!FastBlockStateCache.isEmpty(stateId)) {
                                                sectionLocalNonEmpty++;
                                                if (FastBlockStateCache.isRandomlyTickingBlock(stateId)) {
                                                    sectionLocalTickingBlocks++;
                                                }
                                            }
                                            if (!FastBlockStateCache.isFluidEmpty(stateId)
                                                    && FastBlockStateCache.isRandomlyTickingFluid(stateId)) {
                                                sectionLocalTickingFluids++;
                                            }
                                            if (FastBlockStateCache.hasLightEmission(stateId)) {
                                                sectionLocalLight++;
                                            }
                                        }
                                    } else if (rawWriteResult < 0) {
                                        if (sectionLocalRawWriter && metricsEnabled) {
                                            metricSectionLocalRawFallbacks++;
                                        }
                                        if (!flatSection.bts$setRawBlockStateForGeneration(localIndex, stateId)) {
                                            BlockState blockState = stateId == defaultBlockId
                                                    ? defaultBlock
                                                    : FastBlockStateCache.getBlockState(stateId);
                                            section.setBlockState(localX, localY, localZ, blockState, false);
                                        }
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
                if (sectionLocalRawWriter && sectionLocalWrites > 0) {
                    flatSection.bts$commitRawStartedOnlyAirGenerationWrites(
                            sectionLocalNonEmpty,
                            sectionLocalTickingBlocks,
                            sectionLocalTickingFluids,
                            sectionLocalLight,
                            sectionLocalWrites
                    );
                    if (metricsEnabled) {
                        metricSectionLocalRawCommits++;
                        metricSectionLocalRawWrites += sectionLocalWrites;
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
            GANoiseFillMetrics.add(GANoiseFillMetrics.SLOW_SAMPLES, metricSlowSamples);
            GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_Y_CALLS, metricUpdateYCalls);
            GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_X_CALLS, metricUpdateXCalls);
            GANoiseFillMetrics.add(GANoiseFillMetrics.UPDATE_Z_CALLS, metricUpdateZCalls);
            GANoiseFillMetrics.add(GANoiseFillMetrics.SELECT_CELL_CALLS, metricSelectCellCalls);
            GANoiseFillMetrics.add(GANoiseFillMetrics.SELECT_CELL_NANOS, metricSelectCellNanos);
            GANoiseFillMetrics.add(GANoiseFillMetrics.AQUIFER_SCHEDULE_CHECKS, metricAquiferScheduleChecks);
            GANoiseFillMetrics.add(GANoiseFillMetrics.NON_AIR_SAMPLES, metricNonAirSamples);
            GANoiseFillMetrics.add(GANoiseFillMetrics.SECTION_LOCAL_RAW_COMMITS, metricSectionLocalRawCommits);
            GANoiseFillMetrics.add(GANoiseFillMetrics.SECTION_LOCAL_RAW_WRITES, metricSectionLocalRawWrites);
            GANoiseFillMetrics.add(GANoiseFillMetrics.SECTION_LOCAL_RAW_FALLBACKS, metricSectionLocalRawFallbacks);
        }
        return chunkAccess;
        } finally {
            GAWallTimeTelemetry.end(GAWallTimeTelemetry.Stage.NOISE_DO_FILL, wallTelemetryStart);
        }
    }

    @Unique
    private static int ga$sampleInterpolatedStateId(NoiseChunk noiseChunk, int defaultBlockId) {
        BlockState blockState = noiseChunk.getInterpolatedState();
        return blockState == null ? defaultBlockId : GA$BlockStateExtension.get(blockState).bts$getFastId();
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
