package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.levelgen.material;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(MaterialRuleList.class)
public class MixinMaterialRuleList {

    @Shadow
    @Final
    private List<NoiseChunk.BlockStateFiller> materialRuleList;

    @WrapMethod(method = "calculate")
    public BlockState bts$calculate(DensityFunction.FunctionContext context, Operation<BlockState> original) {
        final List<NoiseChunk.BlockStateFiller> list = materialRuleList;

        for (int i = 0; i < list.size(); i++) {
            final BlockState blockState = list.get(i).calculate(context);

            if (blockState != null)
                return blockState;
        }

        return null;
    }
}
