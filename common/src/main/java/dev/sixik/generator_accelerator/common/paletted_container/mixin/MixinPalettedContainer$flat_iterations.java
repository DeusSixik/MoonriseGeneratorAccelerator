package dev.sixik.generator_accelerator.common.paletted_container.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$PaletteDataExtern;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.function.Consumer;

@Mixin(value = PalettedContainer.class, priority = 999)
public class MixinPalettedContainer$flat_iterations<T> {

    @Shadow
    public volatile PalettedContainer.Data<T> data;

    @Unique
    private static final ThreadLocal<int[]> COUNTER_BUFFER = ThreadLocal.withInitial(() -> new int[4096]);

    @Unique
    private static final ThreadLocal<boolean[]> PRESENT_FLAGS_BUFFER = ThreadLocal.withInitial(() -> new boolean[4096]);

    @Unique
    private static final ThreadLocal<int[]> STORAGE_UNPACK_BUFFER = ThreadLocal.withInitial(() -> new int[4096]);


    /**
     * @author Sixik
     * @reason The logic associated with forEach has been removed and replaced with a flat implementation.
     */
    @Inject(method = "getAll", at = @At("HEAD"), cancellable = true)
    public void getAll(Consumer<T> consumer, CallbackInfo ci) {
        ci.cancel();
        final PalettedContainer.Data<T> data = this.data;
        final Palette<T> palette = data.palette;
        final GA$PaletteDataExtern<T> extern = (GA$PaletteDataExtern<T>) (Object) data;
        final int storageSize = data.storage().getSize();
        final int[] storageIndices = this.generatorAccelerator$getRawOrUnpackedStorage(data, extern, storageSize);
        final int paletteSize = palette.getSize();
        final T[] rawPalette = extern.bts$getPalette();

        if (paletteSize == 1) {
            consumer.accept(this.generatorAccelerator$readPalette(palette, rawPalette, 0));
            return;
        }

        boolean[] presentFlags = PRESENT_FLAGS_BUFFER.get();
        if (presentFlags.length < paletteSize) {
            presentFlags = new boolean[paletteSize];
            PRESENT_FLAGS_BUFFER.set(presentFlags);
        }

        Arrays.fill(presentFlags, 0, paletteSize, false);

        int uniqueFound = 0;

        for (int i = 0; i < storageSize; i++) {
            int paletteIndex = storageIndices[i];

            if (!presentFlags[paletteIndex]) {
                presentFlags[paletteIndex] = true;
                uniqueFound++;

                if (uniqueFound == paletteSize) {
                    break;
                }
            }
        }

        for (int i = 0; i < paletteSize; i++) {
            if (presentFlags[i]) {
                consumer.accept(this.generatorAccelerator$readPalette(palette, rawPalette, i));
            }
        }
    }

    /**
     * @author Sixik
     * @reason The logic associated with forEach has been removed and replaced with a flat implementation.
     */
    @Inject(method = "count", at = @At("HEAD"), cancellable = true)
    public void count(PalettedContainer.CountConsumer<T> countConsumer, CallbackInfo ci) {
        ci.cancel();
        final PalettedContainer.Data<T> data = this.data;
        final Palette<T> palette = data.palette;
        final GA$PaletteDataExtern<T> extern = (GA$PaletteDataExtern<T>) (Object) data;
        final int storageSize = data.storage().getSize();
        final int[] storageIndices = this.generatorAccelerator$getRawOrUnpackedStorage(data, extern, storageSize);
        final int paletteSize = palette.getSize();
        final T[] rawPalette = extern.bts$getPalette();

        if (paletteSize == 1) {
            countConsumer.accept(this.generatorAccelerator$readPalette(palette, rawPalette, 0), storageSize);
            return;
        }

        int[] counters = COUNTER_BUFFER.get();

        if (counters.length < paletteSize) {
            counters = new int[paletteSize];
            COUNTER_BUFFER.set(counters);
        }

        Arrays.fill(counters, 0, paletteSize, 0);

        for (int i = 0; i < storageSize; i++) {
            int paletteIndex = storageIndices[i];

            counters[paletteIndex]++;
        }

        for (int i = 0; i < paletteSize; i++) {
            int count = counters[i];
            if (count > 0) {
                countConsumer.accept(this.generatorAccelerator$readPalette(palette, rawPalette, i), count);
            }
        }
    }

    @Unique
    private int[] generatorAccelerator$getRawOrUnpackedStorage(PalettedContainer.Data<T> data, GA$PaletteDataExtern<T> extern, int storageSize) {
        int[] rawStorage = extern.bts$getRawStorage();
        if (rawStorage != null) {
            return rawStorage;
        }

        int[] unpacked = STORAGE_UNPACK_BUFFER.get();
        if (unpacked.length < storageSize) {
            unpacked = new int[storageSize];
            STORAGE_UNPACK_BUFFER.set(unpacked);
        }
        if (data.storage().getBits() == 0) {
            Arrays.fill(unpacked, 0, storageSize, 0);
        } else {
            data.storage().unpack(unpacked);
        }
        return unpacked;
    }

    @Unique
    private T generatorAccelerator$readPalette(Palette<T> palette, T[] rawPalette, int paletteIndex) {
        if (rawPalette != null) {
            T value = rawPalette[paletteIndex];
            if (value != null) {
                return value;
            }
        }
        return palette.valueFor(paletteIndex);
    }
}
