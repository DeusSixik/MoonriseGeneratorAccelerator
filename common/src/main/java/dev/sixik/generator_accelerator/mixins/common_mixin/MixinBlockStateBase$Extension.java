package dev.sixik.generator_accelerator.mixins.common_mixin;

import com.mojang.serialization.MapCodec;
import dev.sixik.generator_accelerator.api.patches.GA$BlockStateExtension;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
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

    private MixinBlockStateBase$Extension(Block object, Reference2ObjectArrayMap<Property<?>, Comparable<?>> reference2ObjectArrayMap, MapCodec<BlockState> mapCodec) {
        super(object, reference2ObjectArrayMap, mapCodec);
    }

    @Override
    public int bts$getFastId() {
        return bts$generatorFastId;
    }

    @Override
    public void bts$setFastId(int id) {
        bts$generatorFastId = id;
    }
}
