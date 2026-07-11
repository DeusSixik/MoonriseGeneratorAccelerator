package dev.sixik.generator_accelerator.common.biome.mixin.compat.terrablender;

import com.bawnorton.mixinsquared.TargetHandler;
import dev.sixik.generator_accelerator.api.patches.GA$BiomeSourceExtern;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Mixin(value = BiomeSource.class, priority = 1600)
public abstract class Terrablender$MixinBiomeSource$cache_possible_biomes implements GA$BiomeSourceExtern {

    @Unique
    private boolean ga$hasAppended;

    @Mutable
    @Shadow
    @Final
    private Supplier<Set<Holder<Biome>>> possibleBiomes;

    /**
     * @author Sixik
     * @reason TerraBlender's appended possible-biome supplier rebuilds a stream/distinct
     * set on every chunk decoration. The appended list is immutable after bootstrap, so
     * materialize it once and keep possibleBiomes() allocation-free in worldgen.
     */
    @TargetHandler(
            mixin = "terrablender.mixin.MixinBiomeSource",
            name = "appendDeferredBiomesList"
    )
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), cancellable = true, remap = false)
    private void ga$cacheDeferredPossibleBiomes(List<Holder<Biome>> biomes, CallbackInfo ci) {
        if (this.ga$hasAppended) {
            ci.cancel();
            return;
        }

        Set<Holder<Biome>> current = this.possibleBiomes.get();
        ObjectLinkedOpenHashSet<Holder<Biome>> merged = new ObjectLinkedOpenHashSet<>(current.size() + biomes.size());
        merged.addAll(current);
        merged.addAll(biomes);

        Set<Holder<Biome>> cached = Collections.unmodifiableSet(merged);
        this.possibleBiomes = () -> cached;
        this.ga$hasAppended = true;
        ci.cancel();
    }
}
