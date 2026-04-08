package dev.sixik.generator_accelerator.common.flat_block_structure.mixin;

import dev.sixik.generator_accelerator.common.flat_block_structure.LevelChunkSection$FlatBlockArray;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(value = LevelChunkSection.class, priority = 2000)
public abstract class MixinLevelChunkSection$flat_block_array implements LevelChunkSection$FlatBlockArray {

    @Shadow
    @Final
    public PalettedContainer<BlockState> states;

    @Shadow
    public short nonEmptyBlockCount;

    @Unique
    private int @Nullable [] bts$rawBlockData;

    @Override
    public int @Nullable [] bts$getRawBlockData() {
        return bts$rawBlockData;
    }

    @Override
    public void bts$unpackForGeneration() {
        if (this.bts$rawBlockData != null) return;

        this.bts$rawBlockData = new int[4096];

        if (this.nonEmptyBlockCount == 0) {
            return;
        }

        // Copy data from PalettedContainer
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = this.states.get(x, y, z);

                    if (!state.isAir()) {
                        int index = (y << 8) | (z << 4) | x;
                        this.bts$rawBlockData[index] = Block.getId(state);
                    }
                }
            }
        }
    }

    @Override
    public void bts$packAndFreeze() {
        if (bts$rawBlockData == null) return;

        // Converting raw data to a familiar PalettedContainer
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = (y << 8) | (z << 4) | x;
                    int stateId = bts$rawBlockData[index];
                    BlockState state = Block.stateById(stateId);
                    this.states.set(x, y, z, state);
                }
            }
        }

        bts$rawBlockData = null;
    }

    @Inject(method = "write", at = @At("HEAD"))
    public void bts$write(FriendlyByteBuf friendlyByteBuf, CallbackInfo ci) {
        this.bts$packAndFreeze();
    }

    @Inject(method = "getStates", at = @At("HEAD"))
    public void bts$getStates(CallbackInfoReturnable<PalettedContainer<BlockState>> cir) {
        this.bts$packAndFreeze();
    }

    @Inject(method = "recalcBlockCounts", at = @At("HEAD"))
    public void bts$recalcBlockCounts(CallbackInfo ci) {
        this.bts$packAndFreeze();
    }

    @Inject(method = "maybeHas", at = @At("HEAD"))
    public void bts$maybeHas(Predicate<BlockState> predicate, CallbackInfoReturnable<Boolean> cir) {
        this.bts$packAndFreeze();
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    public void bts$getBlockState(int pX, int pY, int pZ, CallbackInfoReturnable<BlockState> cir) {
        if (this.bts$rawBlockData != null) {
            int index = (pY << 8) | (pZ << 4) | pX;
            cir.setReturnValue(Block.stateById(this.bts$rawBlockData[index]));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    public void bts$getFluidState(int pX, int pY, int pZ, CallbackInfoReturnable<FluidState> cir) {
        if (this.bts$rawBlockData != null) {
            int index = (pY << 8) | (pZ << 4) | pX;
            cir.setReturnValue(Block.stateById(this.bts$rawBlockData[index]).getFluidState());
        }
    }

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
    public void bts$setBlockState_Inject(int pX, int pY, int pZ, BlockState pState, boolean pUseLocks, CallbackInfoReturnable<BlockState> cir) {
        if (this.bts$rawBlockData != null) {
            int index = (pY << 8) | (pZ << 4) | pX;
            int oldId = this.bts$rawBlockData[index];
            this.bts$rawBlockData[index] = Block.getId(pState);
            BlockState oldState = Block.stateById(oldId);

            if (!oldState.isAir()) this.nonEmptyBlockCount--;
            if (!pState.isAir()) this.nonEmptyBlockCount++;

            cir.setReturnValue(oldState);
        }
    }
}
