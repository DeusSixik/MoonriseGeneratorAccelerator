package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.common.features.ReusablePlacementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.RandomPatchFeature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = RandomPatchFeature.class, priority = 999)
public abstract class MixinRandomPatchFeature extends Feature<RandomPatchConfiguration> {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$SHARED_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<ReusablePlacementContext> GA$PLACEMENT_CONTEXT =
            new ThreadLocal<>();

    private MixinRandomPatchFeature(Codec<RandomPatchConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Reuse the mutable position and cached nested placed feature/context while preserving vanilla RNG order.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<RandomPatchConfiguration> placeContext) {
        RandomPatchConfiguration config = placeContext.config();
        RandomSource random = placeContext.random();
        BlockPos origin = placeContext.origin();
        WorldGenLevel level = placeContext.level();
        ChunkGenerator generator = placeContext.chunkGenerator();

        PlacedFeature nestedFeature = config.feature().value();
        ReusablePlacementContext nestedContext = GA$PLACEMENT_CONTEXT.get();
        if (nestedContext == null || nestedContext.generator() != generator) {
            nestedContext = new ReusablePlacementContext(level, generator);
            GA$PLACEMENT_CONTEXT.set(nestedContext);
        } else {
            nestedContext.set(level, generator);
        }
        BlockPos.MutableBlockPos mutablePos = GA$SHARED_POS.get();

        int spreadXZ = config.xzSpread() + 1;
        int spreadY = config.ySpread() + 1;
        int tries = config.tries();
        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();
        boolean placedAny = false;

        for (int i = 0; i < tries; i++) {
            mutablePos.set(
                    originX + random.nextInt(spreadXZ) - random.nextInt(spreadXZ),
                    originY + random.nextInt(spreadY) - random.nextInt(spreadY),
                    originZ + random.nextInt(spreadXZ) - random.nextInt(spreadXZ)
            );

            if (nestedFeature.placeWithContext(nestedContext, random, mutablePos)) {
                placedAny = true;
            }
        }

        return placedAny;
    }
}
