package dev.sixik.generator_accelerator.mixins.common_mixin.accessor;

import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.UpgradeData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ChunkAccess.class)
public interface MixinChunkAccessAccessor {
    @Accessor("postProcessing")
    ShortList[] ga$getPostProcessing();

    @Accessor("pendingBlockEntities")
    Map<BlockPos, CompoundTag> ga$getPendingBlockEntities();

    @Accessor("upgradeData")
    UpgradeData ga$getUpgradeData();
}
