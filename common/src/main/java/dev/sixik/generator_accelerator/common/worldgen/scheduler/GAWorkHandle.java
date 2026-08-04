package dev.sixik.generator_accelerator.common.worldgen.scheduler;

/**
 * Compact 64-bit handle used by the GA affinity scheduler hot path.
 *
 * <pre>
 * [ generation low32 ][ arena index 8 ][ node index 18 ][ flags 6 ]
 * </pre>
 */
public final class GAWorkHandle {
    public static final long NULL_HANDLE = 0L;
    public static final int MAX_ARENA_INDEX = 0xFF;
    public static final int MAX_NODE_INDEX = 0x3_FFFF;
    public static final int MAX_FLAGS = 0x3F;

    public static final int FLAG_URGENT = 1;
    public static final int FLAG_RESUME = 1 << 1;
    public static final int FLAG_LEGACY = 1 << 2;

    private static final int FLAGS_BITS = 6;
    private static final int NODE_BITS = 18;
    private static final int ARENA_BITS = 8;
    private static final int NODE_SHIFT = FLAGS_BITS;
    private static final int ARENA_SHIFT = NODE_SHIFT + NODE_BITS;
    private static final int GENERATION_SHIFT = ARENA_SHIFT + ARENA_BITS;

    private GAWorkHandle() {
    }

    public static long encode(long slotGeneration, int arenaIndex, int nodeIndex, int flags) {
        if (arenaIndex < 0 || arenaIndex > MAX_ARENA_INDEX) {
            throw new IllegalArgumentException("arenaIndex out of handle range: " + arenaIndex);
        }
        if (nodeIndex < 0 || nodeIndex > MAX_NODE_INDEX) {
            throw new IllegalArgumentException("nodeIndex out of handle range: " + nodeIndex);
        }
        if ((flags & ~MAX_FLAGS) != 0) {
            throw new IllegalArgumentException("flags out of handle range: " + flags);
        }
        long generation = slotGeneration & 0xFFFF_FFFFL;
        if (generation == 0L) {
            generation = 1L;
        }
        return (generation << GENERATION_SHIFT)
                | ((long) arenaIndex << ARENA_SHIFT)
                | ((long) nodeIndex << NODE_SHIFT)
                | (long) flags;
    }

    public static int arenaIndex(long handle) {
        return (int) ((handle >>> ARENA_SHIFT) & MAX_ARENA_INDEX);
    }

    public static int nodeIndex(long handle) {
        return (int) ((handle >>> NODE_SHIFT) & MAX_NODE_INDEX);
    }

    public static int flags(long handle) {
        return (int) (handle & MAX_FLAGS);
    }

    public static int generationLow32(long handle) {
        return (int) (handle >>> GENERATION_SHIFT);
    }

    public static boolean urgent(long handle) {
        return (flags(handle) & FLAG_URGENT) != 0;
    }

    public static boolean resume(long handle) {
        return (flags(handle) & FLAG_RESUME) != 0;
    }

    public static long withFlags(long handle, int flags) {
        return (handle & ~((long) MAX_FLAGS)) | (flags & MAX_FLAGS);
    }
}
