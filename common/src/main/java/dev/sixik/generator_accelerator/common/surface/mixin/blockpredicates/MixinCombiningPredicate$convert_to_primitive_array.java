package dev.sixik.generator_accelerator.common.surface.mixin.blockpredicates;

import dev.sixik.generator_accelerator.common.surface.CombiningPredicatePrimitiveArray;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.CombiningPredicate")
public abstract class MixinCombiningPredicate$convert_to_primitive_array implements BlockPredicate, CombiningPredicatePrimitiveArray {

    @Unique
    private BlockPredicate[] bts$primitiveArray;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(List<BlockPredicate> list, CallbackInfo ci) {
        this.bts$primitiveArray = list.toArray(BlockPredicate[]::new);
    }

    @Override
    public BlockPredicate[] bts$getPrimitiveArray() {
        return bts$primitiveArray;
    }
}
