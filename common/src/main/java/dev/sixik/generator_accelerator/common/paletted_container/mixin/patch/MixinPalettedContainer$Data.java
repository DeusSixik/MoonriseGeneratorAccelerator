package dev.sixik.generator_accelerator.common.paletted_container.mixin.patch;

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
    private volatile T[] generatorAccelerator$palette;

    @Unique
    private volatile int[] generatorAccelerator$rawStorage;

    @Override
    public T[] bts$getPalette() {
        return generatorAccelerator$palette;
    }

    @Override
    public void bts$setPalette(T[] var1) {
        this.generatorAccelerator$palette = var1;
    }

    @Override
    public int[] bts$getRawStorage() {
        return this.generatorAccelerator$rawStorage;
    }

    @Override
    public void bts$setRawStorage(int[] var1) {
        this.generatorAccelerator$rawStorage = var1;
    }
}
