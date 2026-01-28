package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.material;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(MaterialRuleList.class)
public class MixinMaterialRuleList {

    @Unique
    private NoiseChunk.BlockStateFiller[] bts$array;

    @Unique
    private int bts$lastSuccess = -1;

    @Inject(method = "<init>", at = @At("TAIL"))
    public void bts$init(List<NoiseChunk.BlockStateFiller> list, CallbackInfo ci) {
        final var size = list.size();
        bts$array = new NoiseChunk.BlockStateFiller[size];

        for (int i = 0; i < list.size(); i++) {
            bts$array[i] = list.get(i);
        }
    }



    @WrapMethod(method = "calculate")
    public BlockState bts$calculate(DensityFunction.FunctionContext context, Operation<BlockState> original) {
        if(bts$lastSuccess != -1) {
            final BlockState blockState = bts$array[bts$lastSuccess].calculate(context);
            if(blockState != null)
                return blockState;
        }


        for (int i = 0; i < bts$array.length; i++) {
            final BlockState blockState = bts$array[i].calculate(context);

            if (blockState != null) {
                bts$lastSuccess = i;
                return blockState;
            }
        }

        bts$lastSuccess = -1;
        return null;
    }
}
