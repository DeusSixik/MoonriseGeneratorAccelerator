package dev.sixik.generator_accelerator.common.paletted_container.mixin.patch;

import dev.sixik.generator_accelerator.api.patches.GA$PaletteDataExtern;
import dev.sixik.generator_accelerator.api.patches.GA$PaletteExtern;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/**
 * @author Spottedleaf
 * <p>
 * All code provided in this class was taken from <a href="https://github.com/Tuinity/Moonrise">Moonrise</a>
 */
@Mixin(PalettedContainer.class)
public abstract class MixinPalettedContainer<T> implements PaletteResize<T>, PalettedContainerRO<T> {

    @Shadow
    public volatile PalettedContainer.Data<T> data;

    @Unique
    @SuppressWarnings("unchecked")
    private void updateData(PalettedContainer.Data<T> data) {
        if (data != null) {
            GA$PaletteDataExtern<T> extern = (GA$PaletteDataExtern<T>) (Object) data;
            extern.bts$setPalette(((GA$PaletteExtern<T>) data.palette()).bts$getRawPalette(extern));
            this.updateRawStorage(data, extern);
        }

    }

    @Unique
    private void updateRawStorage(PalettedContainer.Data<T> data, GA$PaletteDataExtern<T> extern) {
        final int storageSize = data.storage().getSize();
        int[] rawStorage = extern.bts$getRawStorage();
        if (rawStorage == null || rawStorage.length != storageSize) {
            rawStorage = new int[storageSize];
        }

        if (data.storage().getBits() == 0) {
            Arrays.fill(rawStorage, 0, storageSize, 0);
        } else {
            data.storage().unpack(rawStorage);
        }
        extern.bts$setRawStorage(rawStorage);
    }

    @Inject(
            method = {"<init>(Lnet/minecraft/core/IdMap;Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;Lnet/minecraft/world/level/chunk/PalettedContainer$Configuration;Lnet/minecraft/util/BitStorage;Ljava/util/List;)V", "<init>(Lnet/minecraft/core/IdMap;Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;Lnet/minecraft/world/level/chunk/PalettedContainer$Data;)V", "<init>(Lnet/minecraft/core/IdMap;Ljava/lang/Object;Lnet/minecraft/world/level/chunk/PalettedContainer$Strategy;)V"},
            at = {@At("RETURN")},
            require = 3
    )
    private void constructorHook(CallbackInfo ci) {
        this.updateData(this.data);
    }

    @Inject(
            method = {"onResize"},
            at = {@At("RETURN")}
    )
    private void resizeHook(CallbackInfoReturnable<Integer> cir) {
        this.updateData(this.data);
    }

    @Inject(
            method = {"read"},
            at = {@At("RETURN")}
    )
    private void readHook(CallbackInfo ci) {
        this.updateData(this.data);
    }

    @Unique
    private T readPaletteSlow(PalettedContainer.Data<T> data, int paletteIdx) {
        return (T)data.palette.valueFor(paletteIdx);
    }

    @Unique
    private T readPalette(PalettedContainer.Data<T> data, int paletteIdx) {
        T[] palette = ((GA$PaletteDataExtern<T>) (Object) data).bts$getPalette();
        if (palette == null) {
            return this.readPaletteSlow(data, paletteIdx);
        } else {
            T ret = palette[paletteIdx];
            if (ret == null) {
                throw new IllegalArgumentException("Palette index out of bounds");
            } else {
                return ret;
            }
        }
    }

    /**
     * @author Spottedleaf
     * @reason
     */
    @Overwrite
    public T getAndSet(int index, T value) {
        int paletteIdx = this.data.palette.idFor(value);
        PalettedContainer.Data<T> data = this.data;
        int[] rawStorage = ((GA$PaletteDataExtern<T>) (Object) data).bts$getRawStorage();
        int prev;
        if (rawStorage != null) {
            prev = rawStorage[index];
            data.storage().set(index, paletteIdx);
            rawStorage[index] = paletteIdx;
        } else {
            prev = data.storage().getAndSet(index, paletteIdx);
        }
        return this.readPalette(data, prev);
    }

    /**
     * @author Sixik
     * @reason Keep the packed vanilla storage authoritative while updating GA's raw palette-index mirror.
     */
    @Overwrite
    public final void set(int index, T value) {
        int paletteIdx = this.data.palette.idFor(value);
        PalettedContainer.Data<T> data = this.data;
        data.storage().set(index, paletteIdx);
        int[] rawStorage = ((GA$PaletteDataExtern<T>) (Object) data).bts$getRawStorage();
        if (rawStorage != null) {
            rawStorage[index] = paletteIdx;
        }
    }

    /**
     * @author Spottedleaf
     * @reason
     */
    @Overwrite
    public T get(int index) {
        PalettedContainer.Data<T> data = this.data;
        int[] rawStorage = ((GA$PaletteDataExtern<T>) (Object) data).bts$getRawStorage();
        int paletteIdx = rawStorage != null ? rawStorage[index] : data.storage().get(index);
        return this.readPalette(data, paletteIdx);
    }
}
