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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(value = PlacedFeature.class, priority = 1500)
public class Confluence$PlacedFeatureMixin$fix {

    @Shadow
    @Final
    private Holder<ConfiguredFeature<?, ?>> feature;

    /**
     * Целимся в твой Overwrite метод placeWithContext.
     */
    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.features.mixin.place.FastPlacedFeatureV2Mixin", // Проверь правильность пути к твоему новому Mixin!
            name = "placeWithContext"
    )
    @Inject(
            method = "@MixinSquared:Handler",
            // Вклиниваемся ровно перед вызовом ванильного feature.place() внутри твоего цикла
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/feature/ConfiguredFeature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"
            ),
            // Захватываем локальные переменные из твоего метода
            locals = LocalCapture.CAPTURE_FAILHARD,
            cancellable = true
    )
    private void bts$placeWithContext$fixCompat(
            PlacementContext context,
            RandomSource random,
            BlockPos startPos,
            CallbackInfoReturnable<Boolean> cir,
            dev.sixik.generator_accelerator.common.features.PrimitivePlacementPool pool,
            it.unimi.dsi.fastutil.longs.LongArrayList current,
            it.unimi.dsi.fastutil.longs.LongArrayList next,
            ConfiguredFeature<?, ?> currentFeature,
            MutableBoolean success,
            BlockPos.MutableBlockPos mPos,
            int i
    ) {
        if (this.feature.value().feature() instanceof TreeFeature) {
            ResourceLocation id = this.feature.unwrapKey().map(ResourceKey::location).orElse(null);
            if (id != null) {
                if (OverworldUtils.replacePine(id, context, random, mPos)) {
                    success.setTrue();
                }
            }
        }
    }
}
