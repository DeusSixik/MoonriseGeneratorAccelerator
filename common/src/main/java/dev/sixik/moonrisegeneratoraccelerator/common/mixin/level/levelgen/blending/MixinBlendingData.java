package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.blending;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(BlendingData.class)
public abstract class MixinBlendingData {

    @Shadow
    @Final
    private static int CELL_COLUMN_COUNT;
    @Shadow
    @Final
    private double[] heights;
    @Shadow
    @Final
    private List<List<Holder<Biome>>> biomes;
    @Shadow
    @Final
    private double[][] densities;
    @Shadow
    @Final
    private LevelHeightAccessor areaWithOldGeneration;

    @Shadow
    private int cellCountPerColumn() {
        return 0;
    }

    @Shadow
    private int getColumnMinY() {
        return 0;
    }

    @Shadow
    private static int getX(int i) {
        return 0;
    }

    @Shadow
    private static int getZ(int i) {
        return 0;
    }

    @Unique
    private static int[] bts$X_OFFSETS;
    @Unique
    private static int[] bts$Z_OFFSETS;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void bts$staticInit(CallbackInfo ci) {
        bts$X_OFFSETS = new int[CELL_COLUMN_COUNT];
        bts$Z_OFFSETS = new int[CELL_COLUMN_COUNT];
        for (int i = 0; i < CELL_COLUMN_COUNT; i++) {
            bts$X_OFFSETS[i] = getX(i);
            bts$Z_OFFSETS[i] = getZ(i);
        }
    }

    /**
     * @author Sixik
     * @reason Replace arithmetic calculation with Array Lookup (LUT)
     */
    @Overwrite
    public void iterateHeights(int chunkX, int chunkZ, BlendingData.HeightConsumer consumer) {
        final int[] xOff = bts$X_OFFSETS;
        final int[] zOff = bts$Z_OFFSETS;
        final double[] h = this.heights;
        final int len = h.length;

        for (int k = 0; k < len; ++k) {
            double d = h[k];
            if (d != Double.MAX_VALUE) {
                consumer.consume(chunkX + xOff[k], chunkZ + zOff[k], d);
            }
        }
    }

    /**
     * @author Sixik
     * @reason Replace arithmetic calculation with Array Lookup (LUT)
     */
    @Overwrite
    public void iterateDensities(int chunkX, int chunkZ, int minBlockY, int maxBlockY, BlendingData.DensityConsumer consumer) {
        int colMinY = this.getColumnMinY();
        int startY = Math.max(0, minBlockY - colMinY);
        int endY = Math.min(this.cellCountPerColumn(), maxBlockY - colMinY);

        if (startY >= endY) return; // Fast fail

        final int[] xOff = bts$X_OFFSETS;
        final int[] zOff = bts$Z_OFFSETS;
        final double[][] dens = this.densities;
        final int len = dens.length;

        for (int p = 0; p < len; ++p) {
            double[] column = dens[p];
            if (column != null) {
                int realX = chunkX + xOff[p];
                int realZ = chunkZ + zOff[p];

                for (int s = startY; s < endY; ++s) {
                    consumer.consume(realX, s + colMinY, realZ, column[s] * 0.1);
                }
            }
        }
    }

    /**
     * @author Sixik
     * @reason Replace arithmetic calculation with Array Lookup (LUT)
     */
    @Overwrite
    public void iterateBiomes(int chunkX, int chunkZ, int sectionY, BlendingData.BiomeConsumer consumer) {
        int minSec = QuartPos.fromBlock(this.areaWithOldGeneration.getMinBuildHeight());
        int maxSec = QuartPos.fromBlock(this.areaWithOldGeneration.getMaxBuildHeight());

        if (sectionY >= minSec && sectionY < maxSec) {
            int localY = sectionY - minSec;

            final int[] xOff = bts$X_OFFSETS;
            final int[] zOff = bts$Z_OFFSETS;
            final List<List<Holder<Biome>>> bList = this.biomes;
            final int size = bList.size();

            for (int m = 0; m < size; ++m) {
                List<Holder<Biome>> column = bList.get(m);
                if (column != null) {
                    Holder<Biome> holder = column.get(localY);
                    if (holder != null) {
                        consumer.consume(chunkX + xOff[m], chunkZ + zOff[m], holder);
                    }
                }
            }
        }
    }
}
