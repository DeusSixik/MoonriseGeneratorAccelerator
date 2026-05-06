package dev.sixik.generator_accelerator.common.features.mixin.compats.confluence;

import dev.sixik.generator_accelerator.common.features.vm.FeaturePlacementCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.confluence.mod.util.OverworldUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FeaturePlacementCompat.class)
public class Confluence$FeaturePlacementCompatMixin {

    @Inject(method = "beforePlace", at = @At("HEAD"), cancellable = true)
    private static void ga$replaceConfluencePine(
            Holder<ConfiguredFeature<?, ?>> feature,
            PlacementContext context,
            RandomSource random,
            BlockPos.MutableBlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (feature.value().feature() instanceof TreeFeature) {
            ResourceLocation id = feature.unwrapKey().map(ResourceKey::location).orElse(null);
            if (id != null && OverworldUtils.replacePine(id, context, random, pos)) {
                cir.setReturnValue(true);
            }
        }
    }
}
