package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.api.worldgen.LevelContext;
import com.dtteam.dynamictrees.systems.poissondisc.PoissonDisc;
import com.dtteam.dynamictrees.worldgen.feature.CaveRootedTreePlacement;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.compat.dynamictrees.GADynamicTreesCompat;
import dev.sixik.generator_accelerator.common.features.vm.LongScratchBuffer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(CaveRootedTreePlacement.class)
public abstract class DynamicTrees$CaveRootedTreePlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Override
    public boolean ga$hasFastPositions() {
        return true;
    }

    @Override
    public void generatePositionsRaw(PlacementContext context, RandomSource random, long packedPos, LongScratchBuffer output) {
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        LevelContext levelContext = GADynamicTreesCompat.levelContext(context.getLevel());
        List<PoissonDisc> discs = GADynamicTreesCompat.chunkDiscs(levelContext, chunkX, chunkZ);
        for (int i = 0, size = discs.size(); i < size; i++) {
            PoissonDisc disc = discs.get(i);
            output.add(BlockPos.asLong(disc.x, 0, disc.z));
        }
    }

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        LevelContext levelContext = GADynamicTreesCompat.levelContext(context.getLevel());
        List<PoissonDisc> discs = GADynamicTreesCompat.chunkDiscs(levelContext, chunkX, chunkZ);
        for (int i = 0, size = discs.size(); i < size; i++) {
            PoissonDisc disc = discs.get(i);
            output.add(BlockPos.asLong(disc.x, 0, disc.z));
        }
    }
}
