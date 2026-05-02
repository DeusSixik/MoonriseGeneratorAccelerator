package dev.sixik.generator_accelerator.common.paletted_container.mixin.bitstorages;

import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Spottedleaf
 * <p>
 * All code provided in this class was taken from <a href="https://github.com/Tuinity/Moonrise">Moonrise</a>
 */
@Mixin(SimpleBitStorage.class)
public abstract class MixinSimpleBitStorage {


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

    static {
        for (int bits = 1; bits < BETTER_MAGIC.length; ++bits) {
            BETTER_MAGIC[bits] = (int) getUnsignedDivisorMagic(64L / (long) bits, 20);
        }

    }


    private static long getUnsignedDivisorMagic(final long divisor, final int bits) {
        return ((1L << bits) - 1L) / divisor + 1L;
    }
}
