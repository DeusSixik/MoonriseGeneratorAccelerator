package dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch;

import dev.sixik.generator_accelerator.api.patches.GA$PaletteDataExtern;
import dev.sixik.generator_accelerator.api.patches.GA$PaletteExtern;
import net.minecraft.world.level.chunk.LinearPalette;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * @author Spottedleaf
 * <p>
 * All code provided in this class was taken from <a href="https://github.com/Tuinity/Moonrise">Moonrise</a>
 */
@Mixin(LinearPalette.class)
public class MixinLinearPalette<T> implements GA$PaletteExtern<T> {

    @Shadow
    @Final
    private T[] values;

    public final T[] bts$getRawPalette(GA$PaletteDataExtern<T> container) {
        return this.values;
    }

}
