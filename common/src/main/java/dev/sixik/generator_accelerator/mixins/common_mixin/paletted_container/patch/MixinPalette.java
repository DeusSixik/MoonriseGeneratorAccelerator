package dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.patch;

import dev.sixik.generator_accelerator.api.patches.GA$PaletteExtern;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;

/**
 * @author Spottedleaf
 * <p>
 * All code provided in this class was taken from <a href="https://github.com/Tuinity/Moonrise">Moonrise</a>
 */
@Mixin(Palette.class)
public interface MixinPalette<T> extends GA$PaletteExtern<T> {
}
