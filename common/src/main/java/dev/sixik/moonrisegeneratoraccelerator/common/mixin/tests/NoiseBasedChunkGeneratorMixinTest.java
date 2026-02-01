package dev.sixik.moonrisegeneratoraccelerator.common.mixin.tests;

import dev.sixik.density_interpreter.tests.NoiseChunkInterface;
import dev.sixik.density_interpreter.tests.SimpleContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
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
        if(newDoFill(blender, structureManager, randomState, chunk, minCellY, cellCountY))
            return chunk;

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

    private boolean newDoFill(
            Blender blender, StructureManager structureManager,
            RandomState randomState, ChunkAccess chunk,
            int minCellY, int cellCountY
    ) {
        // 1. Инициализация (как у тебя)
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c -> this.createNoiseChunk(c, structureManager, blender, randomState));
        NoiseChunkInterface noiseChunkInterface = (NoiseChunkInterface) noiseChunk;

        // Получаем координаты начала чанка в мире (например, 160, -64, 320)
        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getMinBlockX(); // Абсолютный X начала чанка
        int startZ = chunkPos.getMinBlockZ(); // Абсолютный Z начала чанка

        // Заполняем сетку (Macro pass)
        final var pos = chunk.getPos();
        noiseChunkInterface.fillNoiseGrid(pos.getMinBlockX(), pos.getMinBlockZ());

        NoiseSettings noiseSettings = this.settings.value().noiseSettings();
        int minY = noiseSettings.minY(); // -64
        int height = noiseSettings.height(); // 384 (размер, а не координата!)
        int maxY = minY + height; // 320 (верхняя граница)

        // Для обновления карт высот (важно, чтобы мобы спавнились правильно)
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        BlockState defaultBlock = this.settings.value().defaultBlock(); // Камень

        // Оптимизация: кэшируем последнюю секцию, чтобы не искать её каждый раз
        LevelChunkSection currentSection = null;
        int lastSectionIndex = -999;

        // === ПЛОСКИЕ ЦИКЛЫ ===
        // Порядок X -> Z -> Y обычно удобен, но для записи в секции лучше Y был бы внутренним.
        // Оставим твой порядок, но добавим оптимизацию доступа к секции.

        for (int x = 0; x < 16; x++) {
            int absoluteX = startX + x; // Глобальный X

            for (int z = 0; z < 16; z++) {
                int absoluteZ = startZ + z; // Глобальный Z

                for (int y = minY; y < maxY; y++) {

                    // 1. Получаем плотность по АБСОЛЮТНЫМ координатам
                    noiseChunkInterface.updateCtxData(absoluteX, y, absoluteZ);
                    BlockState s = noiseChunk.getInterpolatedState();
                    if(s == null) {
                        s = defaultBlock;
                    }

                    // --- УСТАНОВКА БЛОКА (FAST PATH) ---

                    // Вместо chunk.setBlockState(new BlockPos(...)) который создает объекты и тормозит,
                    // мы пишем напрямую в секцию (LevelChunkSection).

                    // Получаем индекс секции (0, 1, 2...) по высоте Y
                    int sectionIndex = chunk.getSectionIndex(y);

                    // Если мы перешли границу секции (каждые 16 блоков высоты), берем новую
                    if (lastSectionIndex != sectionIndex) {
                        lastSectionIndex = sectionIndex;
                        currentSection = chunk.getSection(sectionIndex);
                    }

                    // Вычисляем локальный Y внутри секции (0..15)
                    int sectionLocalY = y & 15;

                    // Пишем блок (самый быстрый способ в игре)
                    if (s != Blocks.AIR.defaultBlockState()) {
                        currentSection.setBlockState(x, sectionLocalY, z, s, false);

                        // Обновляем карты высот (иначе солнце будет светить сквозь землю)
                        oceanFloor.update(x, y, z, s);
                        worldSurface.update(x, y, z, s);
                    }
                }
            }
        }

        return true;
    }
}
