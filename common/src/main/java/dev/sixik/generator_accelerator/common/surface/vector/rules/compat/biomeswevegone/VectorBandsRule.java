package dev.sixik.generator_accelerator.common.surface.vector.rules.compat.biomeswevegone;

import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.common.surface.vector.VectorChunkContext;
import dev.sixik.generator_accelerator.common.surface.vector.VectorRule;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BandsContext;
import net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.BandsRuleSource;

import java.util.BitSet;

public class VectorBandsRule implements VectorRule {
    private final BandsRuleSource source;

    public VectorBandsRule(BandsRuleSource source) {
        this.source = source;
    }

    @Override
    public void apply(int[] rawBlockData, BitSet activeMask, VectorChunkContext ctx) {
        if (!(ctx.surfaceSystem instanceof BandsContext bandsContext)) {
            return;
        }

        for (int i = activeMask.nextSetBit(0); i >= 0; i = activeMask.nextSetBit(i + 1)) {
            int localX = i & 15;
            int localZ = (i >> 4) & 15;
            int localY = i >> 8;

            int globalX = ctx.sectionStartX + localX;
            int globalY = ctx.sectionStartY + localY;
            int globalZ = ctx.sectionStartZ + localZ;

            BlockState state = bandsContext.getBandsState(
                    this.source,
                    this.source.bandStates(),
                    this.source.bandSizeProvider(),
                    this.source.bandsCountProvider(),
                    globalX, globalY, globalZ,
                    this.source.frequency(),
                    this.source.noiseScale()
            );

            if (state != null) {
                rawBlockData[i] = GA$BlockStateExtension.get(state).bts$getFastId();
                activeMask.clear(i);
            }
        }
    }
}