package dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.sixik.generator_accelerator.common.biome.ClimateParameterListPrimitiveSearch;
import dev.sixik.generator_accelerator.common.biome.FlatClimateIndex;
import dev.sixik.generator_accelerator.common.biome.compat.terrablender.TreeCache;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import terrablender.worldgen.IExtendedParameterList;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(value = {Climate.ParameterList.class}, priority = 1500)
public abstract class Terrablender$MixinParameterList$redirect_search<T> {

    // Global Storage: Vanilla Tree -> Our Flat Index
    @Unique
    private ConcurrentHashMap<Climate.RTree<?>, FlatClimateIndex<?>> bts$tbMap;

    // Thread local cache: remembers the last used tree to avoid Map churn
    @Unique
    private ThreadLocal<TreeCache> bts$tbThreadCache = ThreadLocal.withInitial(TreeCache::new);

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(List list, CallbackInfo ci) {
        bts$tbMap = new ConcurrentHashMap<>();
        bts$tbThreadCache = ThreadLocal.withInitial(TreeCache::new);
    }

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
        TreeCache cache = bts$tbThreadCache.get();
        FlatClimateIndex<?> flatIndex = cache.flatIndex;

        // If this is a new region (TerraBlender has replaced RTree), we get/create a new FlatIndex
        if (cache.tree != tree) {
            flatIndex = bts$tbMap.computeIfAbsent(tree, FlatClimateIndex::new);
            cache.tree = tree;
            cache.flatIndex = flatIndex;
        }

        return flatIndex.search(
                target.temperature(), target.humidity(), target.continentalness(),
                target.erosion(), target.depth(), target.weirdness()
        );
    }
}
