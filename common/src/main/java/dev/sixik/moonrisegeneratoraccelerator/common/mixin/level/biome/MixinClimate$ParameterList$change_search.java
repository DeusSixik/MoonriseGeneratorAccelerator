package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.biome;

import com.mojang.datafixers.util.Pair;
import dev.sixik.moonrisegeneratoraccelerator.common.level.biome.ClimateParameterListPrimitiveSearch;
import dev.sixik.moonrisegeneratoraccelerator.common.level.biome.FlatClimateIndex;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Climate.ParameterList.class)
public class MixinClimate$ParameterList$change_search<T> implements ClimateParameterListPrimitiveSearch<T> {

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

    @Override
    public T bts$findValue(long[] values) {
        return bts$flatIndex.search(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    @Override
    public FlatClimateIndex<T> bts$getClimateIndex() {
        return bts$flatIndex;
    }
}
