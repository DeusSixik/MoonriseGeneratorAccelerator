package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.world.level.chunk.Palette;

public interface GA$PaletteExtern<T> {

    static <T> GA$PaletteExtern<T> get(Palette<T> palette) {
        return (GA$PaletteExtern<T>) palette;
    }

    default T[] bts$getRawPalette(GA$PaletteDataExtern<T> src) {
        return null;
    }
}
