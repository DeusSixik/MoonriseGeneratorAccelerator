package dev.sixik.generator_accelerator.mixins.common_mixin.features.place.tree_decorator;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.potionstudios.biomeswevegone.world.level.levelgen.feature.treedecorators.GlowBerryDecorator;
import org.spongepowered.asm.mixin.*;

@Mixin(GlowBerryDecorator.class)
public abstract class MixinGlowBerryDecorator extends TreeDecorator {

    @Shadow
    @Final
    private FloatProvider probability;
    @Shadow
    @Final
    private IntProvider length;
    @Shadow
    @Final
    private FloatProvider berriesProbability;
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> BTS$CHECK_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Zero-Allocation
     */
    @Overwrite
    public void m_214187_(Context context) {
        ObjectArrayList<BlockPos> logs = context.logs();
        if (logs.isEmpty()) return;

        RandomSource random = context.random();
        float prob = this.probability.sample(random);
        int lengthSample = this.length.sample(random);
        float berriesProb = this.berriesProbability.sample(random);

        Object[] logsArray = logs.elements();
        int size = logs.size();

        BlockPos.MutableBlockPos mutPos = BTS$MUTABLE_POS.get();
        BlockPos.MutableBlockPos checkPos = BTS$CHECK_POS.get();

        for (int idx = 0; idx < size; idx++) {
            BlockPos log = (BlockPos) logsArray[idx];

            if (random.nextFloat() < prob) {
                int px = log.getX();
                int py = log.getY();
                int pz = log.getZ();

                if (context.isAir(checkPos.set(px, py - 1, pz)) && context.isAir(checkPos.set(px, py - 2, pz))) {

                    mutPos.set(px, py - 1, pz);
                    boolean shouldBreak = false;

                    for (int i = 1; i <= lengthSample; ++i) {
                        int curY = mutPos.getY();

                        boolean isNext1Air = context.isAir(checkPos.set(px, curY - 1, pz));
                        boolean isNext2Air = context.isAir(checkPos.set(px, curY - 2, pz));

                        BlockState state;
                        if (isNext1Air && isNext2Air) {
                            state = (i == lengthSample) ? Blocks.CAVE_VINES.defaultBlockState() : Blocks.CAVE_VINES_PLANT.defaultBlockState();
                        } else {
                            state = Blocks.CAVE_VINES.defaultBlockState();
                            shouldBreak = true;
                        }

                        boolean hasBerries = random.nextFloat() < berriesProb;
                        state = state.setValue(BlockStateProperties.BERRIES, hasBerries);

                        if (state.hasProperty(BlockStateProperties.AGE_25)) {
                            state = state.setValue(BlockStateProperties.AGE_25, Mth.randomBetweenInclusive(random, 0, 25));
                        }

                        context.setBlock(mutPos, state);

                        if (shouldBreak) {
                            break;
                        }

                        mutPos.setY(curY - 1);
                    }
                }
            }
        }
    }
}
