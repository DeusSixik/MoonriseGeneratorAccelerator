package dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch;

import dev.sixik.generator_accelerator.api.patches.GA$PaletteDataExtern;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;


/**
 * @author Spottedleaf
 * <p>
 * All code provided in this class was taken from <a href="https://github.com/Tuinity/Moonrise">Moonrise</a>
 */
@Mixin(PalettedContainer.Data.class)
public class MixinPalettedContainer$Data<T> implements GA$PaletteDataExtern<T> {

    @Unique
    private T[] generatorAccelerator$palette;

    @Override
    public T[] bts$getPalette() {
        return generatorAccelerator$palette;
    }

    @Override
    public void bts$setPalette(T[] var1) {
        this.generatorAccelerator$palette = var1;
    }
}
