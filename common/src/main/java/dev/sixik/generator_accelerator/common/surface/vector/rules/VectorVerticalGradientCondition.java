package dev.sixik.generator_accelerator.common.surface.vector.rules;

import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorCondition;
import dev.sixik.generator_accelerator.common.utils.FastPositionalRandom;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.VerticalAnchor;

import java.util.BitSet;

public class VectorVerticalGradientCondition implements VectorCondition {
    private final VerticalAnchor trueAtAndBelowY;
    private final VerticalAnchor falseAtAndAboveY;
    private final ResourceLocation randomFactoryName;


    public VectorVerticalGradientCondition(VerticalAnchor trueY, VerticalAnchor falseY, ResourceLocation randomName) {
        this.trueAtAndBelowY = trueY;
        this.falseAtAndAboveY = falseY;
        this.randomFactoryName = randomName;
    }

    @Override
    public void filter(BitSet activeMask, VectorChunkContext ctx) {
        final int db0 = this.trueAtAndBelowY.resolveY(ctx.worldContext);
        final int db1 = this.falseAtAndAboveY.resolveY(ctx.worldContext);
        final PositionalRandomFactory randomFactory = ctx.randomState.getOrCreateRandomFactory(randomFactoryName);

        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localY = (i >> 8) & 15;
            int globalY = ctx.sectionStartY + localY;

            if (globalY <= db0) {
                continue;
            }
            if (globalY >= db1) {
                activeMask.clear(i);
                continue;
            }

            double chance = Mth.map(globalY, db0, db1, 1.0, 0.0);

            int localX = i & 15;
            int localZ = (i >> 4) & 15;
            if (FastPositionalRandom.nextFloatAt(randomFactory, ctx.sectionStartX + localX, globalY, ctx.sectionStartZ + localZ) >= chance) {
                activeMask.clear(i); // Не повезло в рандоме
            }
        }
    }
}
