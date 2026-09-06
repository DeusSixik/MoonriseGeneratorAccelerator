package dev.sixik.generator_accelerator.common.serialization.mixin;

import dev.sixik.generator_accelerator.api.mixin.annotation.DissableMixinRegister;
import dev.sixik.generator_accelerator.utils.serialization.nbt.FastCompoundTagForWrite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * May be later
 */
@DissableMixinRegister
@Mixin(ChunkSerializer.class)
public class MixinChunkSerializer$redirect_to_fast_nbt {

    @Redirect(method = "write", at = @At(value = "NEW", target = "()Lnet/minecraft/nbt/CompoundTag;"))
    private static CompoundTag write$redirect_compound_tag() {
        return new FastCompoundTagForWrite();
    }
}
