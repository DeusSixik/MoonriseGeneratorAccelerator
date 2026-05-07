package dev.sixik.generator_accelerator.common.features.mixin;

import dev.sixik.generator_accelerator.common.features.ChunkAccess$getOrCreateHeightmapUnsynchronized;
import dev.sixik.generator_accelerator.common.features.ChunkAccess$primeFeatureHeightmapsUnsynchronized;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.EnumSet;
import java.util.Map;

@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess implements ChunkAccess$getOrCreateHeightmapUnsynchronized, ChunkAccess$primeFeatureHeightmapsUnsynchronized {
    @Unique
    private static final EnumSet<Heightmap.Types>[] GA$SINGLE_HEIGHTMAP_SETS = ga$buildHeightmapSets();
    @Unique
    private static final Heightmap.Types[] GA$FEATURE_HEIGHTMAP_TYPES = {
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.WORLD_SURFACE
    };
    @Unique
    private static final EnumSet<Heightmap.Types>[] GA$FEATURE_HEIGHTMAP_SETS_BY_MASK = ga$buildFeatureHeightmapSets();

    @Shadow
    @Final
    protected Map<Heightmap.Types, Heightmap> heightmaps;

    @Shadow
    protected abstract Heightmap getOrCreateHeightmapUnprimed(Heightmap.Types type);

    @Override
    public Heightmap bts$getOrCreateHeightmapUnsynchronized(Heightmap.Types types) {
        Heightmap heightmap = this.heightmaps.get(types);
        if (heightmap == null) {
            Heightmap.primeHeightmaps((ChunkAccess) (Object)this, GA$SINGLE_HEIGHTMAP_SETS[types.ordinal()]);
            heightmap = this.heightmaps.get(types);
        }
        if (heightmap == null) {
            heightmap = this.getOrCreateHeightmapUnprimed(types);
        }

        return heightmap;
    }

    @Unique
    private static EnumSet<Heightmap.Types>[] ga$buildHeightmapSets() {
        Heightmap.Types[] values = Heightmap.Types.values();
        @SuppressWarnings("unchecked")
        EnumSet<Heightmap.Types>[] sets = new EnumSet[values.length];
        for (Heightmap.Types value : values) {
            sets[value.ordinal()] = EnumSet.of(value);
        }
        return sets;
    }

    @Override
    public void ga$primeFeatureHeightmapsIfMissing() {
        int missingMask = 0;
        for (int index = 0; index < GA$FEATURE_HEIGHTMAP_TYPES.length; index++) {
            if (!this.heightmaps.containsKey(GA$FEATURE_HEIGHTMAP_TYPES[index])) {
                missingMask |= 1 << index;
            }
        }

        if (missingMask != 0) {
            Heightmap.primeHeightmaps((ChunkAccess) (Object)this, GA$FEATURE_HEIGHTMAP_SETS_BY_MASK[missingMask]);
        }
    }

    @Unique
    private static EnumSet<Heightmap.Types>[] ga$buildFeatureHeightmapSets() {
        @SuppressWarnings("unchecked")
        EnumSet<Heightmap.Types>[] sets = new EnumSet[1 << GA$FEATURE_HEIGHTMAP_TYPES.length];
        sets[0] = EnumSet.noneOf(Heightmap.Types.class);

        for (int mask = 1; mask < sets.length; mask++) {
            EnumSet<Heightmap.Types> set = EnumSet.noneOf(Heightmap.Types.class);
            for (int index = 0; index < GA$FEATURE_HEIGHTMAP_TYPES.length; index++) {
                if ((mask & (1 << index)) != 0) {
                    set.add(GA$FEATURE_HEIGHTMAP_TYPES[index]);
                }
            }
            sets[mask] = set;
        }

        return sets;
    }
}
