package dev.sixik.generator_accelerator.mixins.common_mixin.features.compats.dynamictrees;

import com.ferreusveritas.dynamictrees.systems.poissondisc.PoissonDisc;
import com.ferreusveritas.dynamictrees.util.LevelContext;
import com.ferreusveritas.dynamictrees.worldgen.CaveRootedTreePlacement;
import com.ferreusveritas.dynamictrees.worldgen.DynamicTreeFeature;
import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(value = CaveRootedTreePlacement.class, remap = false)
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
