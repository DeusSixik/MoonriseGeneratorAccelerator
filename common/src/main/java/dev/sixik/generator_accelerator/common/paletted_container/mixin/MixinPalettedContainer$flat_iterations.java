package dev.sixik.generator_accelerator.common.paletted_container.mixin;

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


    /**
     * @author Sixik
     * @reason The logic associated with forEach has been removed and replaced with a flat implementation.
     */
    @Inject(method = "getAll", at = @At("HEAD"), cancellable = true)
    public void getAll(Consumer<T> consumer, CallbackInfo ci) {
        ci.cancel();
        final Palette<T> palette = this.data.palette;
        final BitStorage storage = this.data.storage();
        final int paletteSize = palette.getSize();

        if (paletteSize == 1) {
            consumer.accept(palette.valueFor(0));
            return;
        }

        boolean[] presentFlags = PRESENT_FLAGS_BUFFER.get();
        if (presentFlags.length < paletteSize) {
            presentFlags = new boolean[paletteSize];
            PRESENT_FLAGS_BUFFER.set(presentFlags);
        }

        Arrays.fill(presentFlags, 0, paletteSize, false);

        final int storageSize = storage.getSize();
        int uniqueFound = 0;

        for (int i = 0; i < storageSize; i++) {
            int paletteIndex = storage.get(i);

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
                consumer.accept(palette.valueFor(i));
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
        final Palette<T> palette = this.data.palette;
        final BitStorage storage = this.data.storage();
        final int paletteSize = palette.getSize();

        if (paletteSize == 1) {
            countConsumer.accept(palette.valueFor(0), storage.getSize());
            return;
        }

        int[] counters = COUNTER_BUFFER.get();

        if (counters.length < paletteSize) {
            counters = new int[paletteSize];
            COUNTER_BUFFER.set(counters);
        }

        Arrays.fill(counters, 0, paletteSize, 0);

        final int storageSize = storage.getSize();
        for (int i = 0; i < storageSize; i++) {
            int paletteIndex = storage.get(i);

            counters[paletteIndex]++;
        }

        for (int i = 0; i < paletteSize; i++) {
            int count = counters[i];
            if (count > 0) {
                countConsumer.accept(palette.valueFor(i), count);
            }
        }
    }
}
