package dev.sixik.generator_accelerator.common.features.mixin.compats.terrainslabs;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.sugar.Local;
import dev.sixik.generator_accelerator.common.features.FastTarget;
import dev.sixik.generator_accelerator.common.worldgen.workspace.GAWorkspaceWriteBridge;
import net.countered.terrainslabs.block.ModSlabsMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = OreFeature.class, priority = 1500)
public abstract class Mixin$TerrainSlabs$OreFeature {

    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.features.mixin.features.MixinOreFeature",
            name = "doPlace"
    )
    @Inject(
            method = {"@MixinSquared:Handler"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/feature/OreFeature;bts$commitPlacement(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/BulkSectionAccess;Lnet/minecraft/world/level/chunk/LevelChunkSection;[ILnet/minecraft/core/BlockPos$MutableBlockPos;Ldev/sixik/generator_accelerator/common/features/FastTarget;IIIII[ZZ)V",
                    ordinal = 0
            )
    )
    private void terrain_slabs_compat$updateSlabs(
            WorldGenLevel level,
            RandomSource random,
            OreConfiguration config,
            double minX, double maxX, double minZ, double maxZ, double minY, double maxY,
            int x, int y, int z, int width, int height,
            CallbackInfoReturnable<Boolean> cir,
            @Local(ordinal = 0) BlockPos.MutableBlockPos mutableBlockPos,
            @Local(ordinal = 0) FastTarget target,
            @Local(ordinal = 0) BulkSectionAccess bulkSectionAccess
    ) {
        Block oreBlock = target.placementState().getBlock();
        Block newSlab = ModSlabsMap.getSlabForBlock(oreBlock);

        if (newSlab != null) {
            this.terrain_slabs_compat$checkAndReplace(level, bulkSectionAccess, mutableBlockPos.above(), newSlab);
            mutableBlockPos.move(0, -1, 0);

            this.terrain_slabs_compat$checkAndReplace(level, bulkSectionAccess, mutableBlockPos.below(), newSlab);
            mutableBlockPos.move(0, 1, 0);
        }
    }

    @Unique
    private void terrain_slabs_compat$checkAndReplace(WorldGenLevel level, BulkSectionAccess access, BlockPos pos, Block newSlabBlock) {
        if (!level.isOutsideBuildHeight(pos.getY())) {
            BlockState currentState = access.getBlockState(pos);
            if (currentState.getBlock() instanceof SlabBlock && !currentState.is(newSlabBlock)) {
                BlockState newState = newSlabBlock.defaultBlockState()
                        .setValue(SlabBlock.TYPE, currentState.getValue(SlabBlock.TYPE))
                        .setValue(SlabBlock.WATERLOGGED, currentState.getValue(SlabBlock.WATERLOGGED));

                LevelChunkSection section = access.getSection(pos);
                if (section != null) {
                    if (GAWorkspaceWriteBridge.writeCurrentWorkspaceOnly(null, pos, newState)) {
                        return;
                    }
                    section.setBlockState(
                            SectionPos.sectionRelative(pos.getX()),
                            SectionPos.sectionRelative(pos.getY()),
                            SectionPos.sectionRelative(pos.getZ()),
                            newState,
                            false
                    );
                }
            }
        }
    }
}
