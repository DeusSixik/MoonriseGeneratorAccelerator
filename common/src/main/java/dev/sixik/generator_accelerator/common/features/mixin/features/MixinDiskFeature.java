package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.common.worldgen.GAWorldGenRegionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = DiskFeature.class, priority = 999)
public abstract class MixinDiskFeature extends Feature<DiskConfiguration> {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private MixinDiskFeature(Codec<DiskConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason Replace iterator-heavy disk scan with raw loops and one mutable position.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<DiskConfiguration> placeContext) {
        DiskConfiguration config = placeContext.config();
        BlockPos origin = placeContext.origin();
        WorldGenLevel level = placeContext.level();
        RandomSource random = placeContext.random();
        int centerX = origin.getX();
        int centerY = origin.getY();
        int centerZ = origin.getZ();
        int topY = centerY + config.halfHeight();
        int bottomY = centerY - config.halfHeight() - 1;
        int radius = config.radius().sample(random);
        int radiusSq = radius * radius;
        BlockPos.MutableBlockPos mutablePos = GA$MUTABLE_POS.get();
        boolean placedAny = false;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            int dx = x - centerX;
            int dxSq = dx * dx;
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                int dz = z - centerZ;
                if (dxSq + dz * dz > radiusSq) {
                    continue;
                }
                mutablePos.set(x, centerY, z);
                placedAny |= this.placeColumn(config, level, random, topY, bottomY, mutablePos);
            }
        }

        return placedAny;
    }

    /**
     * @author Sixik
     * @reason Keep mutable position hot and avoid extra coordinate objects in per-column scan.
     */
    @Overwrite
    protected boolean placeColumn(
            DiskConfiguration config,
            WorldGenLevel level,
            RandomSource random,
            int topY,
            int bottomY,
            BlockPos.MutableBlockPos mutablePos
    ) {
        boolean placedAny = false;

        for (int y = topY; y > bottomY; y--) {
            mutablePos.setY(y);
            if (!config.target().test(level, mutablePos)) {
                continue;
            }
            BlockState state = config.stateProvider().getState(level, random, mutablePos);
            if (GAWorldGenRegionAccess.canWriteWithoutLogging(level, mutablePos)) {
                level.setBlock(mutablePos, state, 2);
                this.markAboveForPostProcessing(level, mutablePos);
                placedAny = true;
            }
        }

        return placedAny;
    }
}
