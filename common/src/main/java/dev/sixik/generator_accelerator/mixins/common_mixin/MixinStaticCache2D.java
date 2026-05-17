package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$StaticCache2DExtern;
import net.minecraft.util.StaticCache2D;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StaticCache2D.class)
public class MixinStaticCache2D<T> implements GA$StaticCache2DExtern<T> {

    @Shadow
    @Final
    private int minX;

    @Shadow
    @Final
    private int minZ;

    @Shadow
    @Final
    private int sizeZ;

    @Mutable
    @Shadow
    @Final
    private Object[] cache;

    private int ga$offset;

    private int ga$shift;

    @Inject(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/util/StaticCache2D;cache:[Ljava/lang/Object;",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER
            )
    )
    public void bts$init(int i, int j, int k, int l, StaticCache2D.Initializer<T> initializer, CallbackInfo ci) {
        this.ga$shift = 32 - Integer.numberOfLeadingZeros(l - 1);
        this.ga$offset = (i << this.ga$shift) + j;
        this.cache = new Object[k << this.ga$shift];
    }

    /**
     * @author Sixik
     * @reason Extreme optimization of index calculation through bit shifts
     */
    @Overwrite
    private int getIndex(int x, int z) {
        return (x << this.ga$shift) + z - this.ga$offset;
    }

    @Override
    public T ga$getFast(int index) {
        return (T) cache[index];
    }

    @Override
    public int ga$getIndex(int x, int z) {
        return ga$getX(x) + ga$getZ(z);
    }

    @Override
    public int ga$getX(int x) {
        return x << this.ga$shift;
    }

    @Override
    public int ga$getZ(int z) {
        return z - this.ga$offset;
    }

    @Override
    public int ga$offset() {
        return ga$offset;
    }

    @Override
    public int ga$shift() {
        return ga$shift;
    }

    @Override
    public Object[] ga$getRawData() {
        return cache;
    }
}
