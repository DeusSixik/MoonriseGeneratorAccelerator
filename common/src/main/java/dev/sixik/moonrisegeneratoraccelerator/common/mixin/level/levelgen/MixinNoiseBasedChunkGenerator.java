package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import com.google.common.collect.Sets;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseBasedChunkGenerator extends ChunkGenerator {

    @Shadow
    protected abstract NoiseChunk createNoiseChunk(ChunkAccess chunkAccess, StructureManager structureManager, Blender blender, RandomState randomState);

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    protected abstract BlockState debugPreliminarySurfaceLevel(NoiseChunk noiseChunk, int i, int j, int k, BlockState blockState);

    @Shadow
    @Final
    private static BlockState AIR;

    private MixinNoiseBasedChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    private ChunkAccess doFill(Blender blender, StructureManager structureManager, RandomState randomState, ChunkAccess chunk, int minCellY, int cellCountY) {
        final NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk((c) -> this.createNoiseChunk(c, structureManager, blender, randomState));

        final Heightmap oceanFloorHeightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        final Heightmap surfaceHeightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        final ChunkPos chunkPos = chunk.getPos();
        final int minChunkX = chunkPos.getMinBlockX();
        final int minChunkZ = chunkPos.getMinBlockZ();
        final Aquifer aquifer = noiseChunk.aquifer();

        noiseChunk.initializeForFirstCellX();
        final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        final int cellWidth = noiseChunk.cellWidth();
        final int cellHeight = noiseChunk.cellHeight();
        final int cellsX = 16 / cellWidth;
        final int cellsZ = 16 / cellWidth;

        final int[] oceanFloorCache = new int[256];
        final int[] worldSurfaceCache = new int[256];
        Arrays.fill(oceanFloorCache, Integer.MIN_VALUE);
        Arrays.fill(worldSurfaceCache, Integer.MIN_VALUE);

        final Predicate<BlockState> isOceanOpaque = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        final Predicate<BlockState> isSurfaceOpaque = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();

        final BlockState AIR = Blocks.AIR.defaultBlockState();
        final boolean debugVoid = SharedConstants.debugVoidTerrain(chunkPos);
        final int MIN = Integer.MIN_VALUE;

        for (int cellX = 0; cellX < cellsX; ++cellX) {
            noiseChunk.advanceCellX(cellX);

            for (int cellZ = 0; cellZ < cellsZ; ++cellZ) {
                int currentSectionIndex = chunk.getSectionsCount() - 1;
                LevelChunkSection section = chunk.getSection(currentSectionIndex);

                for (int cellY = cellCountY - 1; cellY >= 0; --cellY) {
                    noiseChunk.selectCellYZ(cellY, cellZ);

                    for (int localYInCell = cellHeight - 1; localYInCell >= 0; --localYInCell) {
                        final int absoluteY = (minCellY + cellY) * cellHeight + localYInCell;
                        final int localYInSection = absoluteY & 15;

                        final int sectionIndex = chunk.getSectionIndex(absoluteY);
                        if (currentSectionIndex != sectionIndex) {
                            currentSectionIndex = sectionIndex;
                            section = chunk.getSection(sectionIndex);
                        }

                        final double deltaY = (double) localYInCell / (double) cellHeight;
                        noiseChunk.updateForY(absoluteY, deltaY);

                        for (int localXInCell = 0; localXInCell < cellWidth; ++localXInCell) {
                            final int absoluteX = minChunkX + cellX * cellWidth + localXInCell;
                            final int localX = absoluteX & 15;
                            final double deltaX = (double) localXInCell / (double) cellWidth;
                            noiseChunk.updateForX(absoluteX, deltaX);

                            for (int localZInCell = 0; localZInCell < cellWidth; ++localZInCell) {
                                final int absoluteZ = minChunkZ + cellZ * cellWidth + localZInCell;
                                final int localZ = absoluteZ & 15;
                                final double deltaZ = (double) localZInCell / (double) cellWidth;

                                noiseChunk.updateForZ(absoluteZ, deltaZ);

                                BlockState state = noiseChunk.getInterpolatedState();
                                if (state == null) {
                                    state = this.settings.value().defaultBlock();
                                }

                                state = this.debugPreliminarySurfaceLevel(noiseChunk, absoluteX, absoluteY, absoluteZ, state);

                                if (state != AIR && !debugVoid) {
                                    bts$optimizedBlockSetOp(section, localX, localYInSection, localZ, state, false);

                                    /*
                                        Maybe its better ?

                                    int colIndex = localX | (localZ << 4);

                                    if (worldSurfaceCache[colIndex] == MIN) {
                                        if (isSurfaceOpaque.test(state)) {
                                            worldSurfaceCache[colIndex] = absoluteY;
                                        }
                                    }

                                    if (oceanFloorCache[colIndex] == MIN) {
                                        if (isOceanOpaque.test(state)) {
                                            oceanFloorCache[colIndex] = absoluteY;
                                        }
                                    }
                                    */

                                    final int idx = localX | (localZ << 4);
                                    if (worldSurfaceCache[idx] == MIN || oceanFloorCache[idx] == MIN) {
                                        if (worldSurfaceCache[idx] == MIN && isSurfaceOpaque.test(state))
                                            worldSurfaceCache[idx] = absoluteY;
                                        if (oceanFloorCache[idx] == MIN && isOceanOpaque.test(state))
                                            oceanFloorCache[idx] = absoluteY;
                                    }

                                    if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                                        mutablePos.set(absoluteX, absoluteY, absoluteZ);
                                        chunk.markPosForPostprocessing(mutablePos);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        }

        noiseChunk.stopInterpolation();

        final BlockState opaqueState = Blocks.STONE.defaultBlockState();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int idx = x | (z << 4);

                final int surfaceY = worldSurfaceCache[idx];
                if (surfaceY > Integer.MIN_VALUE) {
                    surfaceHeightmap.update(x, surfaceY, z, opaqueState);
                }

                final int oceanY = oceanFloorCache[idx];
                if (oceanY > Integer.MIN_VALUE) {
                    oceanFloorHeightmap.update(x, oceanY, z, opaqueState);
                }
            }
        }

        return chunk;
    }

    /**
     * @author Sixik
     * @reason Use optimized version
     */
    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    public void fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        final NoiseSettings noiseSettings = this.settings.value().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        final int minY = noiseSettings.minY();

        final int minYDiv = Mth.floorDiv(minY, noiseSettings.getCellHeight());
        final int cellHeightDiv = Mth.floorDiv(noiseSettings.height(), noiseSettings.getCellHeight());
        if (cellHeightDiv <= 0) {
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
            return;
        }
        try {
            final int startIndex = chunk.getSectionIndex(cellHeightDiv * noiseSettings.getCellHeight() - 1 + minY);
            final int minYIndex = chunk.getSectionIndex(minY);
            final LevelChunkSection[] sections = chunk.getSections();
            for (int i = startIndex; i >= minYIndex; --i) {
                sections[i].acquire();
            }
            final ChunkAccess access = this.doFill(blender, structureManager, randomState, chunk, minYDiv, cellHeightDiv);
            for (int i = startIndex; i >= minYIndex; --i) {
                sections[i].release();
            }

            cir.setReturnValue(CompletableFuture.completedFuture(access));
        } catch (Throwable throwable) {
            throw new RuntimeException("unexpected error when running wgen/fill-noise", throwable);
        }
    }

    @Unique
    private void bts$optimizedBlockSetOp(@NotNull LevelChunkSection chunkSection, int chunkSectionBlockPosX, int chunkSectionBlockPosY, int chunkSectionBlockPosZ, @NotNull BlockState blockState, boolean lock) {
        chunkSection.nonEmptyBlockCount += 1;
        if (!blockState.getFluidState().isEmpty()) {
            chunkSection.tickingFluidCount += 1;
        }
        if (blockState.isRandomlyTicking()) {
            chunkSection.tickingBlockCount += 1;
        }

        final int blockStateId = chunkSection.states.data.palette.idFor(blockState);
        chunkSection.states.data.storage().set(
                chunkSection.states.strategy.getIndex(
                        chunkSectionBlockPosX, chunkSectionBlockPosY,
                        chunkSectionBlockPosZ
                ),
                blockStateId
        );
    }

    /**
     * @author Sixik
     * @reason Micro optimization
     */
    @WrapMethod(method = "createFluidPicker")
    private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings, Operation<Aquifer.FluidPicker> original) {
        final Aquifer.FluidStatus fluidLevel = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        final int seaLevel = settings.seaLevel();
        final Aquifer.FluidStatus fluidLevel2 = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        final int min = Math.min(-54, seaLevel);
        return (x, y, z) -> y < min ? fluidLevel : fluidLevel2;
    }
}
