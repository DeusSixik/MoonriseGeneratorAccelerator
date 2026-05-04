package dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch;

import dev.sixik.generator_accelerator.api.patches.GA$PaletteDataExtern;
import dev.sixik.generator_accelerator.api.patches.GA$PaletteExtern;
import net.minecraft.core.IdMap;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Spottedleaf
 * <p>
 * All code provided in this class was taken from <a href="https://github.com/Tuinity/Moonrise">Moonrise</a>
 */
@Mixin(CrudeIncrementalIntIdentityHashBiMap.class)
public abstract class MixinCrudeIncrementalIntIdentityHashBiMap<K> implements IdMap<K>, GA$PaletteExtern<K> {

    @Shadow
    private K[] byId;
    @Unique
    private GA$PaletteDataExtern<K> generatorAccelerator$reference;

    public final K[] bts$getRawPalette(GA$PaletteDataExtern<K> src) {
        this.generatorAccelerator$reference = src;
        return this.byId;
    }

    @Inject(
            method = {"grow"},
            at = {@At("RETURN")}
    )
    private void growHook(CallbackInfo ci) {
        GA$PaletteDataExtern<K> ref = this.generatorAccelerator$reference;
        if (ref != null) {
            ref.bts$setPalette(this.byId);
        }

    }
}
