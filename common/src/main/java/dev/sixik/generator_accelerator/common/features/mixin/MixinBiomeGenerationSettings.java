package dev.sixik.generator_accelerator.common.features.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

@Mixin(BiomeGenerationSettings.class)
public class MixinBiomeGenerationSettings {

    @Mutable
    @Shadow
    @Final
    private List<HolderSet<PlacedFeature>> features;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(Map map, List<HolderSet<PlacedFeature>> list, CallbackInfo ci) {
        features = new ObjectArrayList<>(list);
    }
}
