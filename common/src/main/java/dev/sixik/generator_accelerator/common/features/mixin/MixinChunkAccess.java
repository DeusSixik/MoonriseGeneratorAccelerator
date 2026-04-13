package dev.sixik.generator_accelerator.common.features.mixin;

import dev.sixik.generator_accelerator.common.features.ChunkAccess$getOrCreateHeightmapUnsynchronized;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.EnumSet;
import java.util.Map;

@Mixin(ChunkAccess.class)
public class MixinChunkAccess implements ChunkAccess$getOrCreateHeightmapUnsynchronized {

    @Shadow
    @Final
    protected Map<Heightmap.Types, Heightmap> heightmaps;

    @Override
    public Heightmap bts$getOrCreateHeightmapUnsynchronized(Heightmap.Types types) {
        Heightmap heightmap = this.heightmaps.get(types);
        if (heightmap == null) {
            Heightmap.primeHeightmaps((ChunkAccess) (Object)this, EnumSet.of(types));
            heightmap = this.heightmaps.get(types);
        }

        return heightmap;
    }
}
