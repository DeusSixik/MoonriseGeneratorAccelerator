package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SurfaceRules.Context.class)
public class MixinSurfaceRules$Context {

    @Shadow @Final
    private int[] preliminarySurfaceCache;
    @Shadow
    int blockX;
    @Shadow int blockZ;
    @Shadow int surfaceDepth;

    /**
     * @author Sixik
     * @reason Faster math for min surface level calculation
     */
    @Overwrite
    protected int getMinSurfaceLevel() {
        final double fX = (double)(this.blockX & 15) * 0.0625D;
        final double fZ = (double)(this.blockZ & 15) * 0.0625D;

        final double v0 = this.preliminarySurfaceCache[0];
        final double v1 = this.preliminarySurfaceCache[1];
        final double v2 = this.preliminarySurfaceCache[2];
        final double v3 = this.preliminarySurfaceCache[3];

        final double lerpX1 = v0 + fX * (v1 - v0);
        final double lerpX2 = v2 + fX * (v3 - v2);
        final int resY = (int) (lerpX1 + fZ * (lerpX2 - lerpX1));

        return resY + this.surfaceDepth - 8;
    }
}
