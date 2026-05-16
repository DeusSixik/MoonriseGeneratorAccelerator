package dev.sixik.generator_accelerator.common.features.mixin.compats.dynamictrees;

import com.dtteam.dynamictrees.api.worldgen.LevelContext;
import com.dtteam.dynamictrees.systems.poissondisc.PoissonDisc;
import com.dtteam.dynamictrees.worldgen.BiomeDatabase;
import com.dtteam.dynamictrees.worldgen.BiomeDatabases;
import com.dtteam.dynamictrees.worldgen.feature.DynamicTreeFeature;
import dev.sixik.generator_accelerator.common.features.compat.dynamictrees.GADynamicTreesCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(DynamicTreeFeature.class)
public abstract class DynamicTrees$DynamicTreeFeatureMixin {
    @Shadow
    protected abstract DynamicTreeFeature.GeneratorResult generateTree(
            LevelContext levelContext,
            BiomeDatabase.EntryReader biomeEntry,
            PoissonDisc disc,
            BlockPos origin,
            BlockPos rootPos
    );

    @Shadow
    protected static Holder<Biome> getNoiseBiome(LevelContext levelContext, BlockPos pos) {
        throw new AssertionError();
    }

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$TREE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Sixik
     * @reason Avoid ChunkPos/lambda/Consumer allocation in the hot Dynamic Trees feature pass.
     */
    @Overwrite(remap = false)
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        LevelContext levelContext = GADynamicTreesCompat.levelContext(context.level());
        if (BiomeDatabases.isBlacklisted(levelContext.dimensionName())) {
            return false;
        }

        BiomeDatabase biomeDatabase = BiomeDatabases.getDimensionalOrDefault(levelContext.dimensionName());
        BlockPos origin = context.origin();
        List<PoissonDisc> discs = GADynamicTreesCompat.chunkDiscs(levelContext, origin);
        for (int i = 0, size = discs.size(); i < size; i++) {
            this.generateTrees(levelContext, biomeDatabase, discs.get(i), origin);
        }
        return true;
    }

    /**
     * @author Sixik
     * @reason Keep Dynamic Trees generation semantics but avoid Heightmap.valueOf/toUpperCase,
     * repeated GroundFinder lookup and one temporary BlockPos allocation per poisson disc.
     */
    @Overwrite(remap = false)
    protected void generateTrees(LevelContext levelContext, BiomeDatabase biomeDatabase, PoissonDisc disc, BlockPos origin) {
        BlockPos.MutableBlockPos treePos = GA$TREE_POS.get().set(disc.x, origin.getY(), disc.z);
        Holder<Biome> biome = getNoiseBiome(levelContext, treePos);
        Heightmap.Types heightmap = GADynamicTreesCompat.heightmapType(biomeDatabase, biome);
        List<BlockPos> groundPositions = GADynamicTreesCompat.groundFinder(levelContext)
                .findGround(levelContext.accessor(), treePos, heightmap);
        for (int i = 0, size = groundPositions.size(); i < size; i++) {
            BlockPos groundPos = groundPositions.get(i);
            BiomeDatabase.Entry biomeEntry = biomeDatabase.getEntry(getNoiseBiome(levelContext, groundPos));
            this.generateTree(levelContext, biomeEntry, disc, origin, groundPos);
        }
    }
}
