package dev.sixik.generator_accelerator.common.chunks.mixin.compats.modernfix;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.embeddedt.modernfix.common.mixin.bugfix.chunk_deadlock.ServerChunkCacheAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Mixin(ChunkStatusTasks.class)
public abstract class ModernFix$ChunkStatusTasksMixin {
    @Unique
    private static final ThreadLocal<CompletableFuture<ChunkAccess>> GA$SURROGATE_FUTURE = new ThreadLocal<>();

    @Redirect(
            method = "full",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", ordinal = 0)
    )
    private static CompletableFuture<ChunkAccess> ga$createSurrogateFuture(Supplier<ChunkAccess> supplier, Executor executor,
                                                                           @Local(ordinal = 0, argsOnly = true) WorldGenContext worldGenContext) {
        CompletableFuture<ChunkAccess> surrogate = new CompletableFuture<>();
        Executor mainThreadExecutor = ga$getMainThreadProcessor(worldGenContext.level().getChunkSource());

        try {
            executor.execute(() -> {
                try {
                    mainThreadExecutor.execute(() -> ga$runFullPromotionOnMainThread(supplier, surrogate));
                } catch (Throwable throwable) {
                    ga$completeSurrogateExceptionally(surrogate, throwable);
                }
            });
        } catch (Throwable throwable) {
            ga$completeSurrogateExceptionally(surrogate, throwable);
        }

        return surrogate;
    }

    @Unique
    private static void ga$runFullPromotionOnMainThread(Supplier<ChunkAccess> supplier, CompletableFuture<ChunkAccess> surrogate) {
        ChunkAccess chunk;
        GA$SURROGATE_FUTURE.set(surrogate);
        try {
            chunk = supplier.get();
        } catch (Throwable throwable) {
            ga$completeSurrogateExceptionally(surrogate, throwable);
            return;
        } finally {
            GA$SURROGATE_FUTURE.remove();
        }
        surrogate.complete(chunk);
    }

    @Unique
    private static void ga$completeSurrogateExceptionally(CompletableFuture<ChunkAccess> surrogate, Throwable throwable) {
        if (!surrogate.isDone()) {
            surrogate.completeExceptionally(throwable);
        } else {
            MinecraftServer.setFatalException(new ReportedException(CrashReport.forThrowable(throwable, "Exception during promotion of chunk to FULL status")));
        }
    }

    @Unique
    private static Executor ga$getMainThreadProcessor(Object chunkSource) {
        return ((ServerChunkCacheAccessor) chunkSource).mfix$getMainThreadProcessor();
    }

    @Inject(
            method = {"method_60553"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;runPostLoad()V"),
            remap = false,
            require = 0
    )
    private static void ga$completeSurrogateFuture(CallbackInfoReturnable<ChunkAccess> cir, @Local(name = "levelChunk") LevelChunk levelChunk) {
        CompletableFuture<ChunkAccess> future = GA$SURROGATE_FUTURE.get();
        if (future != null) {
            future.complete(levelChunk);
        }
    }
}
