package dev.sixik.moonrisegeneratoraccelerator.common.mixin.tests;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixinTest extends ChunkGenerator {

    public NoiseBasedChunkGeneratorMixinTest(BiomeSource biomeSource) {
        super(biomeSource);
    }

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

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public final ChunkAccess doFill(
            Blender blender, StructureManager structureManager,
            RandomState randomState, ChunkAccess chunk,
            int minCellY, int cellCountY
    ) {
        // Создаем или получаем NoiseChunk (обработчик шума для чанка)
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c -> this.createNoiseChunk(c, structureManager, blender, randomState));

        Heightmap oceanFloorHeightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurfaceHeightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        ChunkPos chunkPos = chunk.getPos();
        int chunkMinBlockX = chunkPos.getMinBlockX();
        int chunkMinBlockZ = chunkPos.getMinBlockZ();

        Aquifer aquifer = noiseChunk.aquifer();

        // Начало интерполяции (X=0)
        noiseChunk.initializeForFirstCellX();

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        // Размеры ячейки шума (обычно 4x8x4 или 4x4x4)
        int cellWidth = noiseChunk.cellWidth();
        int cellHeight = noiseChunk.cellHeight();

        // Количество ячеек в чанке по горизонтали (16 / 4 = 4)
        int cellsPerChunkX = 16 / cellWidth;
        int cellsPerChunkZ = 16 / cellWidth;

        // === ЦИКЛ ПО ЯЧЕЙКАМ (CELLS) ===

        for (int cellX = 0; cellX < cellsPerChunkX; ++cellX) {
            noiseChunk.advanceCellX(cellX); // Сдвигаем "окно" интерполяции по X

            for (int cellZ = 0; cellZ < cellsPerChunkZ; ++cellZ) {

                // Кэшируем последнюю использованную секцию для оптимизации getSection()
                int cachedSectionIndex = chunk.getSectionsCount() - 1;
                LevelChunkSection currentSection = chunk.getSection(cachedSectionIndex);

                // Идем по вертикальным ячейкам (сверху вниз)
                for (int cellY = cellCountY - 1; cellY >= 0; --cellY) {
                    noiseChunk.selectCellYZ(cellY, cellZ); // Выбираем текущую ячейку для интерполяции

                    // === ЦИКЛ ПО БЛОКАМ ВНУТРИ ЯЧЕЙКИ ===

                    // Итерируемся внутри ячейки по высоте (Y)
                    for (int offsetY = cellHeight - 1; offsetY >= 0; --offsetY) {
                        // Вычисляем абсолютный Y блока
                        int blockY = (minCellY + cellY) * cellHeight + offsetY;
                        int sectionLocalY = blockY & 15; // Y внутри секции (0-15)

                        // Проверяем, не перешли ли мы в другую секцию
                        int sectionIndex = chunk.getSectionIndex(blockY);
                        if (cachedSectionIndex != sectionIndex) {
                            cachedSectionIndex = sectionIndex;
                            currentSection = chunk.getSection(sectionIndex);
                        }

                        // Обновляем интерполятор для текущего Y (deltaY = 0.0 ... 1.0)
                        double deltaY = (double)offsetY / (double)cellHeight;
                        noiseChunk.updateForY(blockY, deltaY);

                        // Итерируемся по ширине (X)
                        for (int offsetX = 0; offsetX < cellWidth; ++offsetX) {
                            int blockX = chunkMinBlockX + cellX * cellWidth + offsetX;
                            int sectionLocalX = blockX & 15;

                            double deltaX = (double)offsetX / (double)cellWidth;
                            noiseChunk.updateForX(blockX, deltaX);

                            // Итерируемся по глубине (Z)
                            for (int offsetZ = 0; offsetZ < cellWidth; ++offsetZ) {
                                int blockZ = chunkMinBlockZ + cellZ * cellWidth + offsetZ;
                                int sectionLocalZ = blockZ & 15;

                                double deltaZ = (double)offsetZ / (double)cellWidth;
                                noiseChunk.updateForZ(blockZ, deltaZ);

                                // === ПОЛУЧЕНИЕ БЛОКА ===

                                // Получаем итоговый блок из шума (интерполированный)
                                BlockState state = noiseChunk.getInterpolatedState();
                                if (state == null) {
                                    state = this.settings.value().defaultBlock(); // Камень/Глубинный сланец по умолчанию
                                }



                                // Отладочная проверка или проверка на воздух
                                state = this.debugPreliminarySurfaceLevel(noiseChunk, blockX, blockY, blockZ, state);
                                if (state == AIR || SharedConstants.debugVoidTerrain(chunk.getPos())) {
                                    continue;
                                }

                                // Установка блока в секцию (быстрее чем chunk.setBlockState)
                                currentSection.setBlockState(sectionLocalX, sectionLocalY, sectionLocalZ, state, false);

                                // Обновляем карты высот
                                oceanFloorHeightmap.update(sectionLocalX, blockY, sectionLocalZ, state);
                                worldSurfaceHeightmap.update(sectionLocalX, blockY, sectionLocalZ, state);

                                // Проверка Аквифера (нужно ли заливать жидкости)
                                if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                                    mutablePos.set(blockX, blockY, blockZ);
                                    chunk.markPosForPostprocessing(mutablePos);
                                }
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices(); // Меняем интерполяционные слайсы для следующего шага X
        }
        noiseChunk.stopInterpolation();
        return chunk;
    }
}
