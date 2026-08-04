package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import java.util.IdentityHashMap;

public final class GAWorkerTopology {
    public static final int DEFAULT_SHARD_SHIFT = 2;

    private final int workerCount;
    private final int shardShift;
    private final IdentityHashMap<Object, Integer> worldIds = new IdentityHashMap<>();
    private int nextWorldId;

    public GAWorkerTopology(int workerCount) {
        this(workerCount, DEFAULT_SHARD_SHIFT);
    }

    public GAWorkerTopology(int workerCount, int shardShift) {
        this.workerCount = Math.max(1, workerCount);
        this.shardShift = Math.max(0, shardShift);
    }

    public int workerCount() {
        return workerCount;
    }

    public int shardShift() {
        return shardShift;
    }

    public synchronized int worldId(Object worldIdentity) {
        if (worldIdentity == null) {
            return 0;
        }
        Integer existing = worldIds.get(worldIdentity);
        if (existing != null) {
            return existing;
        }
        int assigned = ++nextWorldId;
        worldIds.put(worldIdentity, assigned);
        return assigned;
    }

    public int owner(GAChunkWorkKey key) {
        return owner(key.worldId(), key.chunkX(), key.chunkZ());
    }

    public int owner(int worldId, int chunkX, int chunkZ) {
        int shardX = chunkX >> shardShift;
        int shardZ = chunkZ >> shardShift;
        int mixed = mix(worldId, shardX, shardZ);
        return Math.floorMod(mixed, workerCount);
    }

    public int ownerForHash(int hash) {
        return Math.floorMod(smear(hash), workerCount);
    }

    private static int mix(int worldId, int shardX, int shardZ) {
        int h = worldId * 0x9E37_79B9;
        h ^= Integer.rotateLeft(shardX * 0x85EB_CA6B, 13);
        h ^= Integer.rotateLeft(shardZ * 0xC2B2_AE35, 17);
        return smear(h);
    }

    private static int smear(int value) {
        int h = value;
        h ^= h >>> 16;
        h *= 0x7FEB_352D;
        h ^= h >>> 15;
        h *= 0x846C_A68B;
        h ^= h >>> 16;
        return h;
    }
}
