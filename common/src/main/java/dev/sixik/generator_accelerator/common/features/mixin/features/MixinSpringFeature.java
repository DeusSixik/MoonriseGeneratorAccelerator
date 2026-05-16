package dev.sixik.generator_accelerator.common.features.mixin.features;

import com.mojang.serialization.Codec;
import dev.sixik.generator_accelerator.common.worldgen.GAWorldGenRegionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = SpringFeature.class, priority = 999)
public abstract class MixinSpringFeature extends Feature<SpringConfiguration> {

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> GA$MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private MixinSpringFeature(Codec<SpringConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<SpringConfiguration> placeContext) {
        SpringConfiguration springConfiguration = placeContext.config();
        WorldGenLevel worldGenLevel = placeContext.level();
        BlockPos blockPos = placeContext.origin();
        int x = blockPos.getX();
        int y = blockPos.getY();
        int z = blockPos.getZ();

        BlockPos.MutableBlockPos mutableBlockPos = GA$MUTABLE_POS.get();

        try (BulkSectionAccess bulkSectionAccess = new BulkSectionAccess(worldGenLevel)) {
            BlockState aboveState = bts$getBlockState(worldGenLevel, bulkSectionAccess, mutableBlockPos, x, y + 1, z);
            if (!aboveState.is(springConfiguration.validBlocks)) {
                return false;
            }

            BlockState belowState = bts$getBlockState(worldGenLevel, bulkSectionAccess, mutableBlockPos, x, y - 1, z);
            if (springConfiguration.requiresBlockBelow && !belowState.is(springConfiguration.validBlocks)) {
                return false;
            }

            BlockState centerState = bts$getBlockState(worldGenLevel, bulkSectionAccess, mutableBlockPos, x, y, z);
            if (!centerState.isAir() && !centerState.is(springConfiguration.validBlocks)) {
                return false;
            }

            BlockState westState = bts$getBlockState(worldGenLevel, bulkSectionAccess, mutableBlockPos, x - 1, y, z);
            BlockState eastState = bts$getBlockState(worldGenLevel, bulkSectionAccess, mutableBlockPos, x + 1, y, z);
            BlockState northState = bts$getBlockState(worldGenLevel, bulkSectionAccess, mutableBlockPos, x, y, z - 1);
            BlockState southState = bts$getBlockState(worldGenLevel, bulkSectionAccess, mutableBlockPos, x, y, z + 1);

            int rockCount = 0;
            if (westState.is(springConfiguration.validBlocks)) {
                rockCount++;
            }
            if (eastState.is(springConfiguration.validBlocks)) {
                rockCount++;
            }
            if (northState.is(springConfiguration.validBlocks)) {
                rockCount++;
            }
            if (southState.is(springConfiguration.validBlocks)) {
                rockCount++;
            }
            if (belowState.is(springConfiguration.validBlocks)) {
                rockCount++;
            }

            int holeCount = 0;
            if (westState.isAir()) {
                holeCount++;
            }
            if (eastState.isAir()) {
                holeCount++;
            }
            if (northState.isAir()) {
                holeCount++;
            }
            if (southState.isAir()) {
                holeCount++;
            }
            if (belowState.isAir()) {
                holeCount++;
            }

            if (rockCount != springConfiguration.rockCount || holeCount != springConfiguration.holeCount) {
                return false;
            }
        }

        if (!GAWorldGenRegionAccess.canWriteWithoutLogging(worldGenLevel, blockPos)) {
            return false;
        }
        worldGenLevel.setBlock(blockPos, springConfiguration.state.createLegacyBlock(), 2);
        worldGenLevel.scheduleTick(blockPos, springConfiguration.state.getType(), 0);
        return true;
    }

    @Unique
    private static BlockState bts$getBlockState(
            WorldGenLevel worldGenLevel,
            BulkSectionAccess bulkSectionAccess,
            BlockPos.MutableBlockPos mutableBlockPos,
            int x,
            int y,
            int z
    ) {
        mutableBlockPos.set(x, y, z);
        if (y >= worldGenLevel.getMinBuildHeight() && y < worldGenLevel.getMaxBuildHeight()) {
            LevelChunkSection section = bulkSectionAccess.getSection(mutableBlockPos);
            if (section != null) {
                return section.getBlockState(x & 15, y & 15, z & 15);
            }
        }

        return worldGenLevel.getBlockState(mutableBlockPos);
    }
}
