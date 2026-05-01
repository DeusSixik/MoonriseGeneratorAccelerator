package dev.sixik.generator_accelerator.common.features.mixin.compats.confluence;

import com.bawnorton.mixinsquared.TargetHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.confluence.mod.util.OverworldUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlacedFeature.class, priority = 1500)
public class Confluence$PlacedFeatureMixin$fix {

    @Shadow
    @Final
    private Holder<ConfiguredFeature<?, ?>> feature;

    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.features.mixin.place.FastPlacedFeatureMixin",
            name = "bts$placeRecursively"
    )
    @Inject(
            method = "@MixinSquared:Handler",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/ConfiguredFeature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),
            cancellable = true)
    private void bts$placeRecursively$fixCompat(PlacementContext context, RandomSource random, BlockPos pos, int modifierIndex, MutableBoolean success, CallbackInfo ci) {
        if (this.feature.value().feature() instanceof TreeFeature) {
            ResourceLocation id = this.feature.unwrapKey().map(ResourceKey::location).orElse(null);
            if (id != null) {
                if (OverworldUtils.replacePine(id, context, random, pos)) {
                    success.setTrue();
                    ci.cancel();
                }
            }
        }
    }
}
