package dev.sixik.generator_accelerator.common.features.mixin.place;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineCompatibility;
import dev.sixik.generator_accelerator.common.features.vm.FeatureProgram;
import dev.sixik.generator_accelerator.common.features.vm.FeatureProgramCache;
import dev.sixik.generator_accelerator.common.features.vm.FeatureVm;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(PlacedFeature.class)
public class FastPlacedFeatureV2Mixin {

    @Shadow
    @Final
    private List<PlacementModifier> placement;

    @Shadow
    @Final
    private Holder<ConfiguredFeature<?, ?>> feature;

    @Unique
    private FeatureProgram ga$featureProgram;

    @Unique
    private byte ga$treeLikeStatus;

    @WrapMethod(method = "placeWithContext")
    private boolean ga$placeWithContext(
            PlacementContext context,
            RandomSource random,
            BlockPos startPos,
            Operation<Boolean> original
    ) {
        if (this.ga$isTreeLike()) {
            return original.call(context, random, startPos);
        }

        FeatureProgram program = this.ga$featureProgram;
        if (program == null) {
            program = FeatureProgramCache.getOrCompile(this.placement, this.feature);
            this.ga$featureProgram = program;
        }
        if (!program.compatibleWithVm()) {
            return original.call(context, random, startPos);
        }
        return FeatureVm.execute(program, context, random, startPos);
    }

    @Unique
    private boolean ga$isTreeLike() {
        byte status = this.ga$treeLikeStatus;
        if (status == 0) {
            PlacedFeature self = (PlacedFeature) (Object) this;
            status = DecorationPipelineCompatibility.isTreeLikeFeature(self) ? (byte) 2 : (byte) 1;
            this.ga$treeLikeStatus = status;
        }
        return status == 2;
    }
}
