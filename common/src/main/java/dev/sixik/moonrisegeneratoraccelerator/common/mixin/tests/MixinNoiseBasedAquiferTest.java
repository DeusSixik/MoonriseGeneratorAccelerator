package dev.sixik.moonrisegeneratoraccelerator.common.mixin.tests;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Aquifer.NoiseBasedAquifer.class)
public class MixinNoiseBasedAquiferTest {

//    @Shadow
//    private boolean shouldScheduleFluidUpdate;
//
//    @Shadow
//    @Final
//    private Aquifer.FluidPicker globalFluidPicker;
//
//    /**
//     * @author
//     * @reason
//     */
//    @Nullable
//    @Overwrite
//    public BlockState computeSubstance(DensityFunction.FunctionContext functionContext, double d) {
//        if (d > 0.0) {
//            return null;
//        }
////        return Blocks.BEDROCK.defaultBlockState();
//        return globalFluidPicker.computeFluid(functionContext.blockX(), functionContext.blockY(), functionContext.blockZ()).at(functionContext.blockY());
//
//    }
}
