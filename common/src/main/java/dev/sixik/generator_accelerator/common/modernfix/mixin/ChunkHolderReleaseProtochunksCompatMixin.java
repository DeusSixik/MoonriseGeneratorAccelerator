package dev.sixik.generator_accelerator.common.modernfix.mixin;

import dev.sixik.generator_accelerator.common.modernfix.GAIClearableChunkHolder;
import dev.sixik.generator_accelerator.common.modernfix.GAISuspendedHolderTrackingChunkMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(ChunkHolder.class)
public abstract class ChunkHolderReleaseProtochunksCompatMixin extends GenerationChunkHolder implements GAIClearableChunkHolder {
    @Shadow
    private CompletableFuture<?> saveSync;

    @Shadow
    private int ticketLevel;

    @Shadow
    @Final
    private ChunkHolder.PlayerProvider playerProvider;

    public ChunkHolderReleaseProtochunksCompatMixin(ChunkPos pos) {
        super(pos);
    }

    @Override
    public void ga$resetProtoChunkFutures() {
        GenerationChunkHolderAccessor accessor = (GenerationChunkHolderAccessor) this;
        var futures = accessor.ga$getFutures();
        int len = futures.length();
        for (int i = 0; i < len; i++) {
            futures.set(i, null);
        }

        this.saveSync = CompletableFuture.completedFuture(null);
        accessor.ga$getStartedWork().set(null);
    }

    @Inject(method = "addSaveDependency", at = @At("RETURN"))
    private void ga$recheckSuspensionAfterNeighbor(CompletableFuture<?> future, CallbackInfo ci) {
        ga$markAsNeedingProtoChunkDrop();
    }

    @Inject(method = "updateFutures", at = @At("RETURN"))
    private void ga$markForSuspensionOnDemotion(ChunkMap chunkMap, Executor executor, CallbackInfo ci) {
        ga$markAsNeedingProtoChunkDrop();
    }

    private void ga$markAsNeedingProtoChunkDrop() {
        if (!ChunkLevel.fullStatus(this.ticketLevel).isOrAfter(FullChunkStatus.FULL)
                && ChunkLevel.isLoaded(this.ticketLevel)
                && this.playerProvider instanceof GAISuspendedHolderTrackingChunkMap map) {
            this.saveSync.whenCompleteAsync((chunk, throwable) -> {
                if (this.getLatestChunk() != null) {
                    map.ga$markForSuspensionCheck(this.getPos());
                }
            }, map.ga$getMainThreadExecutor());
        }
    }
}
