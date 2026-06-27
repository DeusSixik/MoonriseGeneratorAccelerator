package dev.sixik.generator_accelerator.common.paletted_container.mixin.patch;

import dev.sixik.generator_accelerator.api.patches.GA$PaletteDataExtern;
import dev.sixik.generator_accelerator.api.patches.GA$PaletteExtern;
import dev.sixik.generator_accelerator.api.patches.GA$SingleValuePaletteMutator;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * @author Spottedleaf
 * <p>
 * All code provided in this class was taken from <a href="https://github.com/Tuinity/Moonrise">Moonrise</a>
 */
@Mixin(SingleValuePalette.class)
public abstract class MixinSingleValuePalette<T> implements Palette<T>, GA$PaletteExtern<T>, GA$SingleValuePaletteMutator<T> {

    @Shadow
    private T value;
    @Unique
    private T[] generatorAccelerator$rawPalette;

    public final T[] bts$getRawPalette(GA$PaletteDataExtern<T> container) {
        return this.generatorAccelerator$rawPalette;
    }

    @Override
    public void ga$setSingleValue(T value) {
        this.value = value;
        if (this.generatorAccelerator$rawPalette != null) {
            this.generatorAccelerator$rawPalette[0] = value;
        }
    }

    @Inject(
            method = {"idFor"},
            at = {@At(
                    value = "FIELD",
                    opcode = 181,
                    target = "Lnet/minecraft/world/level/chunk/SingleValuePalette;value:Ljava/lang/Object;"
            )}
    )
    private void updateRawPalette1(T object, CallbackInfoReturnable<Integer> cir) {
        if (this.generatorAccelerator$rawPalette != null) {
            this.generatorAccelerator$rawPalette[0] = object;
        }

    }

    @Redirect(
            method = {"read"},
            at = @At(
                    value = "FIELD",
                    opcode = 181,
                    target = "Lnet/minecraft/world/level/chunk/SingleValuePalette;value:Ljava/lang/Object;"
            )
    )
    private void updateRawPalette2(SingleValuePalette<T> instance, T value) {
        ((MixinSingleValuePalette) (Object)instance).value = value;
        if (this.generatorAccelerator$rawPalette != null) {
            this.generatorAccelerator$rawPalette[0] = value;
        }

    }
}
