package dev.sixik.generator_accelerator.common.biome.mixin;

import com.mojang.datafixers.util.Pair;
import dev.sixik.generator_accelerator.common.biome.climate.FlatClimateIndex;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Climate.ParameterList.class)
public class MixinClimate$ParameterList$_redirect_vanilla_climate_to_custom<T> {

    @Unique
    private FlatClimateIndex<T> bts$flatIndex;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bts$buildFlatIndex(List<Pair<Climate.ParameterPoint, T>> list, CallbackInfo ci) {
        this.bts$flatIndex = new FlatClimateIndex<>(list);
    }

    /**
     * @author Sixik
     * @reason
     * Completely replace the search. <br>
     * We use raw values from TargetPointBuffer
     */
    @Overwrite
    public T findValueIndex(Climate.TargetPoint target) {
        return bts$flatIndex.search(
                target.temperature(), target.humidity(), target.continentalness(),
                target.erosion(), target.depth(), target.weirdness()
        );
    }

    /**
     * @author Sixik
     * @reason
     * Completely replace the search. <br>
     * We use raw values from TargetPointBuffer
     */
    @Overwrite
    public T findValue(Climate.TargetPoint target) {
        return bts$flatIndex.search(
                target.temperature(), target.humidity(), target.continentalness(),
                target.erosion(), target.depth(), target.weirdness()
        );
    }
}
