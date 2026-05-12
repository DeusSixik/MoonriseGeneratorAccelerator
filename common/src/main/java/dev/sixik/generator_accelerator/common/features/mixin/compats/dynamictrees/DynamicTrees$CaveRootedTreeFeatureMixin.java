package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.api.worldgen.LevelContext;
import com.dtteam.dynamictrees.systems.poissondisc.PoissonDisc;
import com.dtteam.dynamictrees.worldgen.BiomeDatabase;
import com.dtteam.dynamictrees.worldgen.BiomeDatabases;
import com.dtteam.dynamictrees.worldgen.feature.CaveRootedTreeFeature;
import com.dtteam.dynamictrees.worldgen.feature.DynamicTreeFeature;
import dev.sixik.generator_accelerator.common.features.compat.dynamictrees.GADynamicTreesCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(CaveRootedTreeFeature.class)
public abstract class DynamicTrees$CaveRootedTreeFeatureMixin extends DynamicTreeFeature {
    @Unique
    private static final ThreadLocal<BlockPos[]> GA$GROUND_SORT_BUFFER =
            ThreadLocal.withInitial(() -> new BlockPos[8]);

    /**
     * @author Sixik
     * @reason Remove streams, Optional, AtomicBoolean and ChunkPos allocation from CaveRootedTreeFeature.
     */
    @Overwrite(remap = false)
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        LevelContext levelContext = GADynamicTreesCompat.levelContext(context.level());
        if (BiomeDatabases.isBlacklisted(levelContext.dimensionName())) {
            return false;
        }

        BlockPos origin = context.origin();
        PoissonDisc disc = GADynamicTreesCompat.findDisc(GADynamicTreesCompat.chunkDiscs(levelContext, origin), origin);
        if (disc == null) {
            return false;
        }

        List<BlockPos> groundPositions = GADynamicTreesCompat.groundFinder(levelContext)
                .findGround(context.level(), origin, null);
        if (groundPositions.isEmpty()) {
            return false;
        }

        BiomeDatabase.Entry biomeEntry = BiomeDatabases.getDefault()
                .getEntry(getNoiseBiome(levelContext, origin));
        BiomeDatabase.CaveRootedData caveRootedData = biomeEntry.getCaveRootedData();
        if (caveRootedData == null) {
            return false;
        }

        if (caveRootedData.shouldGenerateOnSurface()) {
            BlockPos surface = ga$highestNonZero(groundPositions);
            return surface != null
                    && this.generateTree(levelContext, biomeEntry, disc, origin, surface)
                    == DynamicTreeFeature.GeneratorResult.GENERATED;
        }

        int count = ga$copyNonZeroSortedByY(groundPositions);
        if (count == 0) {
            return false;
        }

        boolean generated = false;
        BlockPos[] sorted = GA$GROUND_SORT_BUFFER.get();
        for (int i = 0; i < count; i++) {
            DynamicTreeFeature.GeneratorResult result =
                    this.generateTree(levelContext, biomeEntry, disc, origin, sorted[i]);
            if (result == DynamicTreeFeature.GeneratorResult.GENERATED) {
                generated = true;
            }
            sorted[i] = null;
        }
        return generated;
    }

    @Unique
    private static BlockPos ga$highestNonZero(List<BlockPos> positions) {
        BlockPos highest = null;
        for (int i = 0, size = positions.size(); i < size; i++) {
            BlockPos pos = positions.get(i);
            if (pos != BlockPos.ZERO && (highest == null || pos.getY() > highest.getY())) {
                highest = pos;
            }
        }
        return highest;
    }

    @Unique
    private static int ga$copyNonZeroSortedByY(List<BlockPos> positions) {
        BlockPos[] sorted = ga$ensureGroundCapacity(positions.size());
        int count = 0;
        for (int i = 0, size = positions.size(); i < size; i++) {
            BlockPos pos = positions.get(i);
            if (pos != BlockPos.ZERO) {
                int j = count - 1;
                int y = pos.getY();
                while (j >= 0 && sorted[j].getY() > y) {
                    sorted[j + 1] = sorted[j];
                    j--;
                }
                sorted[j + 1] = pos;
                count++;
            }
        }
        return count;
    }

    @Unique
    private static BlockPos[] ga$ensureGroundCapacity(int required) {
        BlockPos[] current = GA$GROUND_SORT_BUFFER.get();
        if (current.length >= required) {
            return current;
        }
        int newLength = current.length;
        while (newLength < required) {
            newLength <<= 1;
        }
        BlockPos[] grown = new BlockPos[newLength];
        GA$GROUND_SORT_BUFFER.set(grown);
        return grown;
    }
}
