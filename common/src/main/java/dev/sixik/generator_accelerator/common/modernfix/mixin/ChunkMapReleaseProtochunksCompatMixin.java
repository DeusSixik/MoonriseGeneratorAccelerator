package dev.sixik.generator_accelerator.common.modernfix.mixin;

import dev.sixik.generator_accelerator.common.modernfix.GAIClearableChunkHolder;
import dev.sixik.generator_accelerator.common.modernfix.GAISuspendedHolderTrackingChunkMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public abstract class ChunkMapReleaseProtochunksCompatMixin implements GAISuspendedHolderTrackingChunkMap {
    @Unique
    private static final int GA$TICKS_TO_WAIT_BEFORE_SUSPENDING = 100;

    @Unique
    private static Method ga$scheduleUnloadBody;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Shadow
    @Final
    private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Unique
    private final Long2IntOpenHashMap ga$protoChunksToDrop = new Long2IntOpenHashMap();

    @Unique
    private int ga$dropTickCounter = 0;

    @Inject(method = "processUnloads(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void ga$dropProtoChunks(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        int suspended = 0;
        int iterations = 0;
        ga$dropTickCounter++;

        var dropIterator = ga$protoChunksToDrop.long2IntEntrySet().fastIterator();
        while (dropIterator.hasNext() && suspended < 50 && iterations < 500 && (hasMoreTime.getAsBoolean() || ga$protoChunksToDrop.size() > 1000)) {
            iterations++;
            var entry = dropIterator.next();
            long pos = entry.getLongKey();
            ChunkHolder holder = updatingChunkMap.get(pos);

            if (holder == null
                    || ChunkLevel.fullStatus(holder.getTicketLevel()).isOrAfter(FullChunkStatus.FULL)
                    || !ChunkLevel.isLoaded(holder.getTicketLevel())) {
                dropIterator.remove();
                continue;
            }

            if (!holder.isReadyForSaving()) {
                entry.setValue(ga$dropTickCounter);
                continue;
            }

            if ((ga$dropTickCounter - entry.getIntValue()) < GA$TICKS_TO_WAIT_BEFORE_SUSPENDING) {
                continue;
            }

            dropIterator.remove();
            pendingUnloads.put(pos, holder);
            if (ga$invokeScheduleUnloadBody((ChunkMap) (Object) this, holder, pos)) {
                ((GAIClearableChunkHolder) holder).ga$resetProtoChunkFutures();
                suspended++;
            } else {
                pendingUnloads.remove(pos, holder);
            }
        }
    }

    @Override
    public void ga$markForSuspensionCheck(ChunkPos pos) {
        ga$protoChunksToDrop.put(pos.toLong(), ga$dropTickCounter);
    }

    @Override
    public Executor ga$getMainThreadExecutor() {
        return mainThreadExecutor;
    }

    @Unique
    private static boolean ga$invokeScheduleUnloadBody(ChunkMap chunkMap, ChunkHolder holder, long pos) {
        try {
            Method method = ga$getScheduleUnloadBody();
            method.invoke(chunkMap, holder, pos);
            return true;
        } catch (ReflectiveOperationException e) {
            if (e instanceof InvocationTargetException invocationTargetException) {
                Throwable cause = invocationTargetException.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
            }
            return false;
        }
    }

    // TODO: add better solution
    @Unique
    private static Method ga$getScheduleUnloadBody() throws NoSuchMethodException {
        Method method = ga$scheduleUnloadBody;
        if (method != null) {
            return method;
        }

        try {
            method = ChunkMap.class.getDeclaredMethod("method_60440", ChunkHolder.class, long.class);
        } catch (NoSuchMethodException ignored) {
            method = ChunkMap.class.getDeclaredMethod("lambda$scheduleUnload$12", ChunkHolder.class, long.class);
        }

        method.setAccessible(true);
        ga$scheduleUnloadBody = method;
        return method;
    }
}
