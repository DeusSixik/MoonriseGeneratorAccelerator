package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$LevelChunkSectionExtern;
import dev.sixik.generator_accelerator.api.patches.GA$PalettedContainerExtern;
import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelChunkSection.class)
public abstract class MixinLevelChunkSection implements GA$LevelChunkSectionExtern, LevelChunkSection$FlatBlockArray {

    @Shadow
    @Final
    public PalettedContainer<BlockState> states;

    @Override
    public int ga$getBlockRaw(int x, int y, int z) {
        final int[] array = bts$getRawBlockData();
        if(array == null) {
           return ((GA$PalettedContainerExtern)states).ga$getRawData(x, y, z);
        }
        return array[(x << 8) | (y << 4) | z];
    }
}
