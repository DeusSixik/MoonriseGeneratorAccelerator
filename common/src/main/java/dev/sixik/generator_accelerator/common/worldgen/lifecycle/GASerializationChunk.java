package dev.sixik.generator_accelerator.common.worldgen.lifecycle;

public record GASerializationChunk(int chunkX, int chunkZ, long estimatedBytes, GASerializationUrgency urgency) {
}
