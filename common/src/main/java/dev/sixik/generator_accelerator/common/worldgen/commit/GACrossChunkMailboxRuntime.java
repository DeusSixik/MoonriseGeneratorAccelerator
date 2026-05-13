package dev.sixik.generator_accelerator.common.worldgen.commit;

import dev.sixik.generator_accelerator.config.GAConfig;
import dev.sixik.generator_accelerator.config.GAConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded deterministic mailbox for workspace-only writes that target a
 * neighbor chunk. Owner chunks enqueue; target chunks drain on the commit lane.
 */
public final class GACrossChunkMailboxRuntime {
    private static final GAConfig CONFIG = GAConfigManager.getConfigOrLoad().orElseGet(GAConfig::new);
    private static final boolean ENABLED = booleanProperty(
            "ga.crossChunkMailbox.runtime.enabled",
            CONFIG.enableCrossChunkMailboxRuntime
    );
    private static final int MAX_QUEUED_COMMANDS = Math.max(0, intProperty(
            "ga.crossChunkMailbox.maxQueuedCommands",
            CONFIG.crossChunkMailboxMaxQueuedCommands
    ));
    private static final Object LOCK = new Object();
    private static final Map<Long, List<GACommitCommand<GABlockWriteValue>>> QUEUES = new LinkedHashMap<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong ENQUEUED = new AtomicLong();
    private static final AtomicLong DRAINED = new AtomicLong();
    private static final AtomicLong REJECTED = new AtomicLong();
    private static final AtomicLong OVERFLOW = new AtomicLong();
    private static int queuedCommands;

    private GACrossChunkMailboxRuntime() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean enqueueBlockWrite(
            int ownerChunkX,
            int ownerChunkZ,
            int x,
            int y,
            int z,
            BlockState state,
            int flags
    ) {
        if (!ENABLED || state == null) {
            REJECTED.incrementAndGet();
            return false;
        }
        int targetChunkX = x >> 4;
        int targetChunkZ = z >> 4;
        if (targetChunkX == ownerChunkX && targetChunkZ == ownerChunkZ) {
            REJECTED.incrementAndGet();
            return false;
        }
        long sequence = SEQUENCE.getAndIncrement();
        GACommitCommand<GABlockWriteValue> command = new GACommitCommand<>(
                new GABlockPosition(x, y, z),
                new GACommitOrderKey(
                        0,
                        0,
                        ownerChunkX,
                        ownerChunkZ,
                        targetChunkX,
                        targetChunkZ,
                        (y & 15) << 8 | (z & 15) << 4 | (x & 15),
                        sequence
                ),
                new GABlockWriteValue(state, flags)
        );

        synchronized (LOCK) {
            if (MAX_QUEUED_COMMANDS > 0 && queuedCommands >= MAX_QUEUED_COMMANDS) {
                OVERFLOW.incrementAndGet();
                return false;
            }
            long targetKey = ChunkPos.asLong(targetChunkX, targetChunkZ);
            QUEUES.computeIfAbsent(targetKey, ignored -> new ArrayList<>()).add(command);
            queuedCommands++;
            ENQUEUED.incrementAndGet();
            return true;
        }
    }

    public static GACommitEngine.GACommitExecution<GABlockWriteValue> drainBlockWrites(ChunkAccess targetChunk) {
        if (targetChunk == null) {
            throw new NullPointerException("targetChunk");
        }
        List<GACommitCommand<GABlockWriteValue>> commands;
        synchronized (LOCK) {
            commands = QUEUES.remove(targetChunk.getPos().toLong());
            if (commands == null || commands.isEmpty()) {
                return null;
            }
            queuedCommands -= commands.size();
        }

        GACommitEngine.GACommitExecution<GABlockWriteValue> execution = GACommitEngine.execute(
                GACommitBatch.of(commands),
                GACommitCollisionPolicy.LATER_WRITE_WINS,
                command -> {
                    Object state = command.value().state();
                    if (!(state instanceof BlockState blockState)) {
                        throw new IllegalArgumentException("mailbox block write state is not a BlockState: " + state);
                    }
                    GABlockPosition position = command.position();
                    targetChunk.setBlockState(new BlockPos(position.x(), position.y(), position.z()), blockState, false);
                }
        );
        DRAINED.addAndGet(execution.metrics().acceptedCount());
        return execution;
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (LOCK) {
            out.put("queuedCommands", queuedCommands);
            out.put("targetChunks", QUEUES.size());
        }
        out.put("enabled", ENABLED);
        out.put("maxQueuedCommands", MAX_QUEUED_COMMANDS);
        out.put("enqueued", ENQUEUED.get());
        out.put("drained", DRAINED.get());
        out.put("rejected", REJECTED.get());
        out.put("overflow", OVERFLOW.get());
        return out;
    }

    public static void resetForTests() {
        synchronized (LOCK) {
            QUEUES.clear();
            queuedCommands = 0;
        }
        SEQUENCE.set(0L);
        ENQUEUED.set(0L);
        DRAINED.set(0L);
        REJECTED.set(0L);
        OVERFLOW.set(0L);
    }

    private static boolean booleanProperty(String property, boolean fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int intProperty(String property, int fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
