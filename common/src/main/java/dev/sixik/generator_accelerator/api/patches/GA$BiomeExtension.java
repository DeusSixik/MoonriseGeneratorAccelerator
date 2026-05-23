package dev.sixik.generator_accelerator.api.patches;

import dev.sixik.generator_accelerator.api.structures.FastBiomeCache;
import net.minecraft.world.level.biome.Biome;

public interface GA$BiomeExtension {
    final class Fallback implements GA$BiomeExtension {
        private final Biome biome;

        private Fallback(Biome biome) {
            this.biome = biome;
        }

        @Override
        public int bts$getFastId() {
            return FastBiomeCache.getId(this.biome);
        }

        @Override
        public void bts$setFastId(int id) {
            // Plain unit tests do not apply the mixin-backed fast-id field.
        }
    }

    static GA$BiomeExtension get(Biome biome) {
        if ((Object) biome instanceof GA$BiomeExtension extension) {
            return extension;
        }
        return new Fallback(biome);
    }

    int bts$getFastId();

    void bts$setFastId(int id);
}
