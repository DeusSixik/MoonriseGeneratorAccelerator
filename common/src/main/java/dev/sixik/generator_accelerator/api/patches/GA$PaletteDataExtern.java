package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.world.level.chunk.PalettedContainer;

public interface GA$PaletteDataExtern<T> {

    static <T> GA$PaletteDataExtern<T> get(PalettedContainer.Data<T> data) {
        return (GA$PaletteDataExtern<T>) (Object) data;
    }

    T[] bts$getPalette();

    void bts$setPalette(T[] var1);
}
