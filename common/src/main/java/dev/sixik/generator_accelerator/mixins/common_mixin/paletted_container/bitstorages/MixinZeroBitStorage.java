package dev.sixik.generator_accelerator.mixins.common_mixin.paletted_container.bitstorages;

import net.minecraft.util.ZeroBitStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(ZeroBitStorage.class)
public class MixinZeroBitStorage {

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public int getAndSet(int index, int value) {
        return 0;
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public void set(int index, int value) {
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public int get(int index) {
        return 0;
    }
}
