package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.biome;

import com.google.common.annotations.VisibleForTesting;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Climate.TargetPoint.class)
public class MixinClimate$TargetPoint$optimize_allocation {

    @Shadow @Final
    long temperature;
    @Shadow
    @Final
    long humidity;
    @Shadow @Final
    long continentalness;
    @Shadow @Final
    long erosion;
    @Shadow @Final
    long depth;
    @Shadow @Final
    long weirdness;

    @Unique
    private static final ThreadLocal<long[]> bts$PARAMETER_BUFFER = ThreadLocal.withInitial(() -> new long[7]);

    /**
     * @author Sixik
     * @reason Removes memory allocation (new long[7]) on every biome lookup.
     */
    @VisibleForTesting
    @WrapMethod(method = "toParameterArray")
    public long[] toParameterArray(Operation<long[]> original) {
        long[] buffer = bts$PARAMETER_BUFFER.get();
        buffer[0] = this.temperature;
        buffer[1] = this.humidity;
        buffer[2] = this.continentalness;
        buffer[3] = this.erosion;
        buffer[4] = this.depth;
        buffer[5] = this.weirdness;
        buffer[6] = 0L;
        return buffer;
    }
}
