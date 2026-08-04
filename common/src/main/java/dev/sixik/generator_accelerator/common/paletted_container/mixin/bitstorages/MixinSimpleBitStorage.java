package dev.sixik.generator_accelerator.common.paletted_container.mixin.bitstorages;

import dev.sixik.generator_accelerator.api.patches.GA$SimpleBitStorageExtern;
import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntConsumer;

/**
 * @author Spottedleaf
 * <p>
 * All code provided in this class was taken from <a href="https://github.com/Tuinity/Moonrise">Moonrise</a>
 */
@Mixin(SimpleBitStorage.class)
public abstract class MixinSimpleBitStorage implements GA$SimpleBitStorageExtern {


    @Shadow
    @Final
    private int bits;
    @Shadow
    @Final
    private long[] data;
    @Shadow
    @Final
    private long mask;
    @Shadow
    @Final
    private int size;
    @Unique
    private static final int[] BETTER_MAGIC = new int[33];
    @Unique
    private int magic;
    @Unique
    private int mulBits;

    @Inject(
            method = {"<init>(II[J)V"},
            at = {@At("RETURN")}
    )
    private void init(CallbackInfo ci) {
        this.magic = BETTER_MAGIC[this.bits];
        this.mulBits = 64 / this.bits * this.bits;
        if (this.size > 4096) {
            throw new IllegalStateException("Size > 4096 not supported");
        }
    }

    /**
     * @author Spottedleaf
     * @reason
     */
    @Overwrite
    public int getAndSet(int index, int value) {
        int full = this.magic * index;
        int divQ = full >>> 20;
        int divR = (full & 1048575) * this.mulBits >>> 20;
        long[] dataArray = this.data;
        long data = dataArray[divQ];
        long mask = this.mask;
        long write = data & ~(mask << divR) | ((long) value & mask) << divR;
        dataArray[divQ] = write;
        return (int) (data >>> divR & mask);
    }

    /**
     * @author Spottedleaf
     * @reason
     */
    @Overwrite
    public void set(int index, int value) {
        int full = this.magic * index;
        int divQ = full >>> 20;
        int divR = (full & 1048575) * this.mulBits >>> 20;
        long[] dataArray = this.data;
        long data = dataArray[divQ];
        long mask = this.mask;
        long write = data & ~(mask << divR) | ((long) value & mask) << divR;
        dataArray[divQ] = write;
    }

    /**
     * @author Spottedleaf
     * @reason
     */
    @Overwrite
    public int get(int index) {
        int full = this.magic * index;
        int divQ = full >>> 20;
        int divR = (full & 1048575) * this.mulBits >>> 20;
        return (int) (this.data[divQ] >>> divR & this.mask);
    }

    @Override
    public long[] ga$getRaw() {
        return this.data;
    }

    @Override
    public int ga$getBits() {
        return this.bits;
    }

    @Override
    public long ga$getMask() {
        return this.mask;
    }

    @Override
    public int ga$getSize() {
        return this.size;
    }

    @Override
    public int ga$getValuesPerLong() {
        return 64 / this.bits;
    }

    /**
     * @author Sixik
     * @reason Avoid validation and callback overhead when callers need the full section decoded.
     */
    @Overwrite
    public void unpack(int[] output) {
        final long[] dataArray = this.data;
        final long mask = this.mask;
        final int bits = this.bits;
        final int valuesPerLong = 64 / bits;
        final int size = this.size;
        int outIndex = 0;

        for (int cell = 0; cell < dataArray.length && outIndex < size; cell++) {
            long packed = dataArray[cell];
            int limit = Math.min(valuesPerLong, size - outIndex);
            for (int offset = 0; offset < limit; offset++) {
                output[outIndex++] = (int) (packed & mask);
                packed >>>= bits;
            }
        }
    }

    /**
     * @author Sixik
     * @reason Keep iteration branch-local and allocation-free.
     */
    @Overwrite
    public void getAll(IntConsumer consumer) {
        final long[] dataArray = this.data;
        final long mask = this.mask;
        final int bits = this.bits;
        final int valuesPerLong = 64 / bits;
        final int size = this.size;
        int emitted = 0;

        for (int cell = 0; cell < dataArray.length && emitted < size; cell++) {
            long packed = dataArray[cell];
            int limit = Math.min(valuesPerLong, size - emitted);
            for (int offset = 0; offset < limit; offset++) {
                consumer.accept((int) (packed & mask));
                packed >>>= bits;
                emitted++;
            }
        }
    }

    static {
        for (int bits = 1; bits < BETTER_MAGIC.length; ++bits) {
            BETTER_MAGIC[bits] = (int) getUnsignedDivisorMagic(64L / (long) bits, 20);
        }

    }


    private static long getUnsignedDivisorMagic(final long divisor, final int bits) {
        return ((1L << bits) - 1L) / divisor + 1L;
    }
}
