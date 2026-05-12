package dev.sixik.generator_accelerator.common.biome.mixin.compat.biolith;

import com.mojang.serialization.MapCodec;
import com.terraformersmc.biolith.api.biome.BiolithFittestNodes;
import com.terraformersmc.biolith.api.biome.sub.Criterion;
import com.terraformersmc.biolith.api.biome.sub.CriterionType;
import com.terraformersmc.biolith.impl.biome.DimensionBiomePlacement;
import com.terraformersmc.biolith.impl.biome.sub.AllOfCriterion;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(value = AllOfCriterion.class, remap = false)
public abstract class Biolith$AllOfCriterion$fast_iter implements Criterion {
    @Shadow
    @Final
    private List<Criterion> criteria;

    @Shadow
    public abstract CriterionType<AllOfCriterion> getType();

    @Shadow
    public abstract MapCodec<AllOfCriterion> getCodec();

    /**
     * @author Sixik
     * @reason Avoid ListItr allocation in Biolith sub-biome criteria during BWG
     * biome edge sampling.
     */
    @Overwrite(remap = false)
    public boolean matches(
            BiolithFittestNodes<Holder<Biome>> fittestNodes,
            DimensionBiomePlacement biomePlacement,
            Climate.TargetPoint noisePoint,
            @Nullable InclusiveRange<Float> replacementRange,
            float replacementNoise
    ) {
        List<Criterion> list = this.criteria;
        for (int i = 0, size = list.size(); i < size; i++) {
            if (!list.get(i).matches(fittestNodes, biomePlacement, noisePoint, replacementRange, replacementNoise)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @author Sixik
     * @reason Keep setup-time criteria traversal allocation-free too.
     */
    @Overwrite(remap = false)
    public void complete(HolderGetter<Biome> biomeEntryGetter) {
        List<Criterion> list = this.criteria;
        for (int i = 0, size = list.size(); i < size; i++) {
            list.get(i).complete(biomeEntryGetter);
        }
    }

    /**
     * @author Sixik
     * @reason Keep teardown-time criteria traversal allocation-free too.
     */
    @Overwrite(remap = false)
    public void reopen() {
        List<Criterion> list = this.criteria;
        for (int i = 0, size = list.size(); i < size; i++) {
            list.get(i).reopen();
        }
    }
}
