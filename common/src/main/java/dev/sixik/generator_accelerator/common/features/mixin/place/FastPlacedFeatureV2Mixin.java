package dev.sixik.generator_accelerator.common.features.mixin.place;

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
import org.spongepowered.asm.mixin.Overwrite;
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

    /**
     * @author Sixik
     * @reason Execute feature placement through the compiled GA Feature Placement VM.
     */
    @Overwrite
    public final boolean placeWithContext(PlacementContext context, RandomSource random, BlockPos startPos) {
        FeatureProgram program = this.ga$featureProgram;
        if (program == null) {
            program = FeatureProgramCache.getOrCompile(this.placement, this.feature);
            this.ga$featureProgram = program;
        }
        return FeatureVm.execute(program, context, random, startPos);
    }
}
