package dev.sixik.generator_accelerator.common.modernfix.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksCompatMixin {
    @Unique
    private static final ThreadLocal<CompletableFuture<ChunkAccess>> GA$SURROGATE_FUTURE = new ThreadLocal<>();

    @Unique
    private static Field ga$mainThreadProcessorField;

    @Redirect(
            method = "full",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", ordinal = 0)
    )
    private static CompletableFuture<ChunkAccess> ga$createSurrogateFuture(Supplier<ChunkAccess> supplier, Executor executor,
                                                                           @Local(ordinal = 0, argsOnly = true) WorldGenContext worldGenContext) {
        CompletableFuture<ChunkAccess> surrogate = new CompletableFuture<>();
        Executor mainThreadExecutor = ga$getMainThreadProcessor(worldGenContext.level().getChunkSource());

        CompletableFuture.runAsync(() -> {}, executor).thenApplyAsync(unused -> {
            GA$SURROGATE_FUTURE.set(surrogate);
            try {
                return supplier.get();
            } finally {
                GA$SURROGATE_FUTURE.remove();
            }
        }, mainThreadExecutor).whenComplete((chunk, throwable) -> {
            if (throwable != null) {
                if (!surrogate.isDone()) {
                    surrogate.completeExceptionally(throwable);
                } else {
                    MinecraftServer.setFatalException(new ReportedException(CrashReport.forThrowable(throwable, "Exception during promotion of chunk to FULL status")));
                }
            } else {
                surrogate.complete(chunk);
            }
        });

        return surrogate;
    }

    @Unique
    private static Executor ga$getMainThreadProcessor(Object chunkSource) {
        try {
            Field field = ga$mainThreadProcessorField;
            if (field == null) {
                field = ga$findMainThreadProcessorField(chunkSource.getClass());
                field.setAccessible(true);
                ga$mainThreadProcessorField = field;
            }
            return (Executor) field.get(chunkSource);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not access ServerChunkCache main thread executor", e);
        }
    }

    @Unique
    private static Field ga$findMainThreadProcessorField(Class<?> chunkSourceClass) throws NoSuchFieldException {
        try {
            return chunkSourceClass.getDeclaredField("mainThreadProcessor");
        } catch (NoSuchFieldException ignored) {
            for (Field field : chunkSourceClass.getDeclaredFields()) {
                if (Executor.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
            throw ignored;
        }
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
