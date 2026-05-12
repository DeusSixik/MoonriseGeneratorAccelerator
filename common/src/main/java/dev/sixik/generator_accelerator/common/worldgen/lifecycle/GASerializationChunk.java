package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

public record GASerializationChunk(int chunkX, int chunkZ, long estimatedBytes, GASerializationUrgency urgency) {
    public GASerializationChunk {
        estimatedBytes = Math.max(0L, estimatedBytes);
        urgency = urgency == null ? GASerializationUrgency.NORMAL : urgency;
    }
}
