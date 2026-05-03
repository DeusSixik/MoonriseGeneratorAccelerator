package dev.sixik.generator_accelerator.mixins.common_mixin;

import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.MapCodec;
import dev.sixik.generator_accelerator.GeneratorAccelerator;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import dev.sixik.generator_accelerator.api.structures.FastBlockStateCache;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class MixinBlockStateBase$Extension extends StateHolder<Block, BlockState> implements GA$BlockStateExtension {

    @Unique
    private int bts$generatorFastId = -1;

    protected MixinBlockStateBase$Extension(Block object, ImmutableMap<Property<?>, Comparable<?>> immutableMap, MapCodec<BlockState> mapCodec) {
        super(object, immutableMap, mapCodec);
    }

    @Override
    public int bts$getFastId() {
        if(bts$generatorFastId == -1) {
            FastBlockStateCache.init(GeneratorAccelerator.platform);
        }

        return bts$generatorFastId;
    }

    @Override
    public void bts$setFastId(int id) {
        bts$generatorFastId = id;
    }
}
