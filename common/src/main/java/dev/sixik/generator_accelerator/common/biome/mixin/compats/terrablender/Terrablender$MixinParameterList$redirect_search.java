package dev.sixik.generator_accelerator.common.biome.mixin.compats.terrablender;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sixik.generator_accelerator.api.mixin.annotation.CompatMixin;
import dev.sixik.generator_accelerator.common.biome.climate.compat.TerraBlenderClimateSearch;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import terrablender.core.TerraBlender;

@CompatMixin(mod = TerraBlender.class)
@Mixin(value = {Climate.ParameterList.class}, priority = 1500)
public abstract class Terrablender$MixinParameterList$redirect_search<T> {

    /**
     * @author Sixik
     * @reason Intercept TerraBlender requests and redirect them to FlatClimateIndex.
     */
    @TargetHandler(
            mixin = "terrablender.mixin.MixinParameterList",
            name = "findValuePositional"
    )
    @WrapOperation(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/Climate$RTree;search(Lnet/minecraft/world/level/biome/Climate$TargetPoint;Lnet/minecraft/world/level/biome/Climate$DistanceMetric;)Ljava/lang/Object;"
            ),
            remap = false
    )
    private Object bts$fastTerraBlenderSearch(
            Climate.RTree<?> tree,
            Climate.TargetPoint target,
            Climate.DistanceMetric metric,
            Operation<Object> original
    ) {
        return TerraBlenderClimateSearch.search(tree, target);
    }
}
