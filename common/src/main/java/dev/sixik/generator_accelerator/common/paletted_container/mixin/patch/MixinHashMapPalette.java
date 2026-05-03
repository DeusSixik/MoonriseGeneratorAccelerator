package dev.sixik.generator_accelerator.common.paletted_container.mixin.patch;

import dev.sixik.generator_accelerator.api.patches.GA$PaletteDataExtern;
import dev.sixik.generator_accelerator.api.patches.GA$PaletteExtern;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * @author Spottedleaf
 * <p>
 * All code provided in this class was taken from <a href="https://github.com/Tuinity/Moonrise">Moonrise</a>
 */
@Mixin(HashMapPalette.class)
public abstract class MixinHashMapPalette<T> implements Palette<T>, GA$PaletteExtern<T> {

    @Shadow
    @Final
    private CrudeIncrementalIntIdentityHashBiMap<T> values;

    @SuppressWarnings("unchecked")
    public final T[] bts$getRawPalette(GA$PaletteDataExtern<T> container) {
        return ((GA$PaletteExtern<T>)this.values).bts$getRawPalette(container);
    }
}
