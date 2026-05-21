package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

public final class StructurePieceCollisionIndex {

    private final Long2ObjectMap<ObjectArrayList<StructurePiece>> buckets = new Long2ObjectOpenHashMap<>();
    private final Reference2IntOpenHashMap<StructurePiece> visitedMarks = new Reference2IntOpenHashMap<>();
    private int queryStamp = 1;

    public void add(StructurePiece piece) {
        BoundingBox box = piece.getBoundingBox();
        int minChunkX = box.minX() >> 4;
        int maxChunkX = box.maxX() >> 4;
        int minChunkZ = box.minZ() >> 4;
        int maxChunkZ = box.maxZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                this.buckets.computeIfAbsent(chunkKey, key -> new ObjectArrayList<>()).add(piece);
            }
        }
    }

    public StructurePiece findCollision(BoundingBox target) {
        int stamp = this.generator_accelerator$nextStamp();
        int minChunkX = target.minX() >> 4;
        int maxChunkX = target.maxX() >> 4;
        int minChunkZ = target.minZ() >> 4;
        int maxChunkZ = target.maxZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ObjectArrayList<StructurePiece> bucket = this.buckets.get(ChunkPos.asLong(chunkX, chunkZ));
                if (bucket == null) {
                    continue;
                }

                Object[] rawBucket = bucket.elements();
                for (int i = 0, size = bucket.size(); i < size; i++) {
                    StructurePiece piece = (StructurePiece) rawBucket[i];
                    if (this.visitedMarks.getInt(piece) == stamp) {
                        continue;
                    }
                    this.visitedMarks.put(piece, stamp);
                    if (piece.getBoundingBox().intersects(target)) {
                        return piece;
                    }
                }
            }
        }
        return null;
    }

    public void clear() {
        this.buckets.clear();
        this.visitedMarks.clear();
        this.queryStamp = 1;
    }

    private int generator_accelerator$nextStamp() {
        int next = this.queryStamp + 1;
        if (next == Integer.MAX_VALUE) {
            this.visitedMarks.clear();
            next = 1;
        }
        this.queryStamp = next;
        return next;
    }
}
