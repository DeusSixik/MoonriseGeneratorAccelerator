package dev.sixik.moonrisegeneratoraccelerator.common.mixin.tests;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sixik.density_interpreter.tests.NoiseChunkInterface;
import dev.sixik.density_interpreter.tests.SimpleContext;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunk.class)
public class NoiseChunkMixinTest implements NoiseChunkInterface {

    private double[] noiseGrid;

    @Shadow @Final public int cellWidth;
    @Shadow @Final public int cellHeight;
    @Shadow @Final private DensityFunction initialDensityNoJaggedness;

    // ВАЖНО: Эти поля в Minecraft хранятся в Квартах (Quarts), а не в блоках!
    @Shadow @Final public int firstNoiseX;
    @Shadow @Final public int firstNoiseZ;

    @Shadow
    @Final
    private NoiseChunk.BlockStateFiller blockStateRule;
    @Shadow
    @Final
    private Aquifer aquifer;
    @Shadow
    public boolean interpolating;
    @Shadow
    public int cellStartBlockZ;
    @Shadow
    public int cellStartBlockY;
    @Shadow
    public int cellStartBlockX;
    private int gridCountX;
    private int gridCountY;
    private int gridCountZ;
    private int minNoiseY;

    // Кэшируем стартовые координаты чанка В БЛОКАХ, чтобы не считать каждый раз
    private int startBlockX;
    private int startBlockZ;

    // Кэшируем страйды для скорости
    private int strideX;
    private int strideZ;
    private int strideY;

    @Override
    public NoiseChunk.BlockStateFiller bts$getRules() {
        // ... (твой код) ...
        return null; // (заглушка для компиляции примера)
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableList$Builder;add(Ljava/lang/Object;)Lcom/google/common/collect/ImmutableList$Builder;", ordinal = 0))
    public <E> ImmutableList.Builder<E> bts$init$redirect_fill(ImmutableList.Builder instance, E element) {
        return null;
    }


    @Unique private int currentBlockX;
    @Unique private int currentBlockY;
    @Unique
    private int currentBlockZ;

    /**
     * @author
     * @reason
     */
    @Overwrite
    public int blockX() { return this.cellStartBlockX + this.currentBlockX; }
    /**
     * @author
     * @reason
     */
    @Overwrite
    public int blockY() { return this.cellStartBlockY + this.currentBlockY; }
    /**
     * @author
     * @reason
     */
    @Overwrite
    public int blockZ() {
        return this.cellStartBlockZ + this.currentBlockZ;
    }

    @Override
    public void updateCtxData(int x, int y, int z) {
        this.currentBlockX = x;
        this.currentBlockY = y;
        this.currentBlockZ = z;
    }

    private DensityFunction rootFunction;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;oreVeinsEnabled()Z"))
    public boolean bts$redirect(NoiseGeneratorSettings instance) {
        return false;
    }

    @Inject(method = "<init>", at =
    @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableList$Builder;add(Ljava/lang/Object;)Lcom/google/common/collect/ImmutableList$Builder;", ordinal = 0))
    public void bts$init$redirect_fill_inject(
            int i, RandomState randomState,
            int j, int k, NoiseSettings noiseSettings,
            DensityFunctions.BeardifierOrMarker beardifierOrMarker,
            NoiseGeneratorSettings noiseGeneratorSettings, Aquifer.FluidPicker fluidPicker,
            Blender blender, CallbackInfo ci, @Local ImmutableList.Builder<NoiseChunk.BlockStateFiller> builder, @Local DensityFunction densityFunction) {
        builder.add((functionContext -> this.aquifer.computeSubstance(functionContext, getFinalDensity(functionContext.blockX(), functionContext.blockY(), functionContext.blockZ()))));
        rootFunction = densityFunction;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(int i, RandomState randomState, int j, int k, NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifierOrMarker, NoiseGeneratorSettings noiseGeneratorSettings, Aquifer.FluidPicker fluidPicker, Blender blender, CallbackInfo ci) {
        this.gridCountX = Math.floorDiv(16, cellWidth) + 1;
        this.gridCountZ = Math.floorDiv(16, cellWidth) + 1;
        this.gridCountY = Math.floorDiv(noiseSettings.height(), cellHeight) + 1;

        this.minNoiseY = noiseSettings.minY();

        // Преобразуем кварты обратно в блоки для корректных расчетов
        this.startBlockX = this.firstNoiseX * this.cellWidth;
        this.startBlockZ = this.firstNoiseZ * this.cellWidth;

        // Предрасчитываем страйды (Y - внутренний цикл, Z - средний, X - внешний)
        // Это соответствует циклу в fillNoiseGrid: for X { for Z { for Y } }
        this.strideY = 1;
        this.strideZ = gridCountY;
        this.strideX = gridCountY * gridCountZ;

        this.noiseGrid = new double[gridCountX * gridCountY * gridCountZ];
    }

    private final SimpleContext ctx = new SimpleContext();

    @Override
    public void fillNoiseGrid(int chunkMinBlockX, int chunkMinBlockZ) {
        // ВНИМАНИЕ: Сюда должны приходить координаты БЛОКОВ (например 160, -320), а не координаты чанка (10, -20)

        int idx = 0;
        for (int gx = 0; gx < gridCountX; gx++) {
            for (int gz = 0; gz < gridCountZ; gz++) {
                for (int gy = 0; gy < gridCountY; gy++) {
                    // Координаты для NoiseRouter (абсолютные)
                    // Используем переданные аргументы или this.startBlockX
                    int x = this.startBlockX + gx * cellWidth;
                    int y = minNoiseY + gy * cellHeight;
                    int z = this.startBlockZ + gz * cellWidth;

                    ctx.update(x, y, z);
                    noiseGrid[idx++] = rootFunction.compute(ctx);
                }
            }
        }
    }

    @Override
    public double getFinalDensity(int blockX, int blockY, int blockZ) {
        // 1. Вычисляем локальные координаты
        int localX = blockX - this.startBlockX;
        int localY = blockY - this.minNoiseY;
        int localZ = blockZ - this.startBlockZ;

        // === ЗАЩИТА ОТ ВЫХОДА ЗА ГРАНИЦЫ (FALLBACK) ===
        // Если нас просят данные за пределами чанка (это делают структуры или Аквифер),
        // мы не можем использовать наш grid. Считаем честно.
        if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) {
            // Создаем временный контекст, чтобы не ломать состояние текущего
            SimpleContext fallbackCtx = new SimpleContext();
            fallbackCtx.update(blockX, blockY, blockZ);
            return this.rootFunction.compute(fallbackCtx);
        }
        // ===============================================

        // 2. Индекс ячейки (Grid Cell)
        int gx = localX / cellWidth;
        int gy = localY / cellHeight;
        int gz = localZ / cellWidth;

        // 3. Смещение внутри ячейки (0.0 ... 1.0)
        double deltaX = (double) (localX % cellWidth) / cellWidth;
        double deltaY = (double) (localY % cellHeight) / cellHeight;
        double deltaZ = (double) (localZ % cellWidth) / cellWidth;

        // 4. Достаем значения
        int idx = gx * strideX + gz * strideZ + gy;

        // Важно: так как мы проверили границы (0..16), а массив имеет размер (16/4 + 1) = 5 точек,
        // то gx будет максимум 3 (для x=15, cell=4), и nextX будет указывать на 4-ю точку.
        // Это безопасно.

        double d000 = noiseGrid[idx];
        double d010 = noiseGrid[idx + strideY];
        double d001 = noiseGrid[idx + strideZ];
        double d011 = noiseGrid[idx + strideZ + strideY];

        int nextX = idx + strideX;
        double d100 = noiseGrid[nextX];
        double d110 = noiseGrid[nextX + strideY];
        double d101 = noiseGrid[nextX + strideZ];
        double d111 = noiseGrid[nextX + strideZ + strideY];

        return Mth.lerp3(deltaX, deltaY, deltaZ, d000, d100, d010, d110, d001, d101, d011, d111);
    }

//    /**
//     * @author Sixik
//     * @reason
//     */
//    @Overwrite
//    public BlockState getInterpolatedState() {
//        return this.blockStateRule.calculate(ctx);
//    }

}
