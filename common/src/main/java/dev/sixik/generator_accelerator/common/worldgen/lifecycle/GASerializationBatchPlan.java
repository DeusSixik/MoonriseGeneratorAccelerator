package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Safe serialization batch plan: orders dirty chunks and cuts by count/bytes budget.
 */
public record GASerializationBatchPlan(List<GASerializationChunk> chunks, long estimatedBytes, boolean truncated) {
    public static GASerializationBatchPlan plan(List<GASerializationChunk> dirtyChunks, int maxChunks, long maxBytes) {
        if (dirtyChunks == null || dirtyChunks.isEmpty() || maxChunks <= 0 || maxBytes <= 0L) {
            return new GASerializationBatchPlan(List.of(), 0L, dirtyChunks != null && !dirtyChunks.isEmpty());
        }
        List<GASerializationChunk> ordered = new ArrayList<>(dirtyChunks);
        ordered.sort(Comparator
                .comparing(GASerializationChunk::urgency)
                .thenComparingInt(GASerializationChunk::chunkX)
                .thenComparingInt(GASerializationChunk::chunkZ));
        List<GASerializationChunk> selected = new ArrayList<>(Math.min(maxChunks, ordered.size()));
        long bytes = 0L;
        for (GASerializationChunk chunk : ordered) {
            if (chunk == null) {
                throw new NullPointerException("chunk");
            }
            if (selected.size() >= maxChunks) {
                return new GASerializationBatchPlan(selected, bytes, true);
            }
            long nextBytes = bytes + chunk.estimatedBytes();
            if (!selected.isEmpty() && nextBytes > maxBytes) {
                return new GASerializationBatchPlan(selected, bytes, true);
            }
            selected.add(chunk);
            bytes = nextBytes;
            if (bytes >= maxBytes && selected.size() < ordered.size()) {
                return new GASerializationBatchPlan(selected, bytes, true);
            }
        }
        return new GASerializationBatchPlan(selected, bytes, selected.size() < ordered.size());
    }

    public int chunkCount() {
        return chunks.size();
    }
}
