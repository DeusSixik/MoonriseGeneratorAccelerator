package dev.sixik.generator_accelerator.mixins.common_mixin.surface;

import dev.sixik.generator_accelerator.common.surface.SurfaceBiomeCondition;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

@Mixin(SurfaceRules.BiomeConditionSource.class)
public abstract class MixinSurfaceRules$BiomeConditionSource implements SurfaceRules.ConditionSource {

    @Mutable
    @Shadow
    @Final
    public Predicate<ResourceKey<Biome>> biomeNameTest;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInitFastBiomeSet(List<ResourceKey<Biome>> list, CallbackInfo ci) {
        ReferenceOpenHashSet<ResourceKey<Biome>> fastIdentitySet = new ReferenceOpenHashSet<>(list);
        this.biomeNameTest = fastIdentitySet::contains;
    }

    /**
     * @author Sixik
     * @reason Redirect to cached biome getter
     */
    @Overwrite
    public SurfaceRules.Condition apply(final SurfaceRules.Context context) {
        return new SurfaceBiomeCondition(context, (SurfaceRules.BiomeConditionSource) (Object) this);
    }
}
