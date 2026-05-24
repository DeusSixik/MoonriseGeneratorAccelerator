package dev.sixik.generator_accelerator.api.patches;

import dev.sixik.generator_accelerator.api.structures.FastStructureCache;
import net.minecraft.world.level.levelgen.structure.Structure;

public interface GA$StructureExtension {
    final class Fallback implements GA$StructureExtension {
        private final Structure structure;

        private Fallback(Structure structure) {
            this.structure = structure;
        }

        @Override
        public int bts$getFastId() {
            return FastStructureCache.getId(this.structure);
        }

        @Override
        public void bts$setFastId(int id) {
            // Plain unit tests do not apply the mixin-backed fast-id field.
        }
    }

    static GA$StructureExtension get(Structure structure) {
        if (structure instanceof GA$StructureExtension extension) {
            return extension;
        }
        return new Fallback(structure);
    }

    int bts$getFastId();

    void bts$setFastId(int id);
}
