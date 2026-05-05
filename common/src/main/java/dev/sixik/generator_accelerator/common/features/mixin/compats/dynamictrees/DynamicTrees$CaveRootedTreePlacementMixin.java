package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.api.worldgen.LevelContext;
import com.dtteam.dynamictrees.systems.poissondisc.PoissonDisc;
import com.dtteam.dynamictrees.worldgen.feature.CaveRootedTreePlacement;
import com.dtteam.dynamictrees.worldgen.feature.DynamicTreeFeature;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(CaveRootedTreePlacement.class)
public abstract class DynamicTrees$CaveRootedTreePlacementMixin extends PlacementModifier implements GA$PlacementModifierExtension {

    @Override
    public void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);

        LevelContext levelContext = LevelContext.create(context.getLevel());
        List<PoissonDisc> discs = DynamicTreeFeature.DISC_PROVIDER.getPoissonDiscs(levelContext, chunkPos);
        for (int i = 0; i < discs.size(); i++) {
            PoissonDisc disc = discs.get(i);
            long finalPacked = BlockPos.asLong(disc.x, 0, disc.z);
            output.add(finalPacked);
        }
    }
}
