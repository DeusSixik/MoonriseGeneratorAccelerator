package dev.sixik.generator_accelerator.common.features.compat.dynamictrees;

import com.dtteam.dynamictrees.api.worldgen.LevelContext;
import com.dtteam.dynamictrees.api.worldgen.GroundFinder;
import com.dtteam.dynamictrees.systems.poissondisc.PoissonDisc;
import com.dtteam.dynamictrees.worldgen.BiomeDatabase;
import com.dtteam.dynamictrees.worldgen.feature.DynamicTreeFeature;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;
import java.util.List;
import java.lang.ref.WeakReference;

public final class GADynamicTreesCompat {
    private static final ThreadLocal<LevelContextCache> LEVEL_CONTEXT =
            ThreadLocal.withInitial(LevelContextCache::new);
    private static final ThreadLocal<DiscCache> DISC_CACHE =
            ThreadLocal.withInitial(DiscCache::new);
    private static final ThreadLocal<HeightmapCache> HEIGHTMAP_CACHE =
            ThreadLocal.withInitial(HeightmapCache::new);
    private static final ThreadLocal<EntryCache> ENTRY_CACHE =
            ThreadLocal.withInitial(EntryCache::new);

    private GADynamicTreesCompat() {
    }

    public static LevelContext levelContext(LevelAccessor level) {
        LevelContextCache cache = LEVEL_CONTEXT.get();
        if (cache.level() != level) {
            cache.level = new WeakReference<>(level);
            cache.context = LevelContext.create(level);
            cache.groundFinder = null;
        }
        return cache.context;
    }

    public static GroundFinder groundFinder(LevelContext context) {
        LevelContextCache cache = LEVEL_CONTEXT.get();
        if (cache.context == context && cache.groundFinder != null) {
            return cache.groundFinder;
        }
        GroundFinder finder = GroundFinder.getGroundFinder(context.level());
        if (cache.context == context) {
            cache.groundFinder = finder;
        }
        return finder;
    }

    public static List<PoissonDisc> chunkDiscs(LevelContext context, BlockPos origin) {
        return chunkDiscs(context, origin.getX() >> 4, origin.getZ() >> 4);
    }

    public static List<PoissonDisc> chunkDiscs(LevelContext context, int chunkX, int chunkZ) {
        DiscCache cache = DISC_CACHE.get();
        if (cache.context == context && cache.chunkX == chunkX && cache.chunkZ == chunkZ && cache.discs != null) {
            return cache.discs;
        }
        List<PoissonDisc> discs = DynamicTreeFeature.DISC_PROVIDER
                .getProvider(context)
                .getPoissonDiscs(chunkX, 0, chunkZ);
        cache.context = context;
        cache.chunkX = chunkX;
        cache.chunkZ = chunkZ;
        cache.discs = discs;
        return discs;
    }

    public static PoissonDisc findDisc(List<PoissonDisc> discs, BlockPos origin) {
        int x = origin.getX();
        int z = origin.getZ();
        for (int i = 0, size = discs.size(); i < size; i++) {
            PoissonDisc disc = discs.get(i);
            if (disc.x == x && disc.z == z) {
                return disc;
            }
        }
        return null;
    }

    public static Heightmap.Types heightmapType(BiomeDatabase biomeDatabase, Holder<Biome> biome) {
        HeightmapCache cache = HEIGHTMAP_CACHE.get();
        if (cache.biomeDatabase() == biomeDatabase && cache.biome() == biome && cache.type != null) {
            return cache.type;
        }
        Heightmap.Types type = heightmapTypeByName(biomeEntry(biomeDatabase, biome).getHeightmap());
        cache.biomeDatabase = new WeakReference<>(biomeDatabase);
        cache.biome = new WeakReference<>(biome);
        cache.type = type;
        return type;
    }

    public static BiomeDatabase.Entry biomeEntry(BiomeDatabase biomeDatabase, Holder<Biome> biome) {
        EntryCache cache = ENTRY_CACHE.get();
        if (cache.biomeDatabase() == biomeDatabase && cache.biome() == biome && cache.entry != null) {
            return cache.entry;
        }
        BiomeDatabase.Entry entry = biomeDatabase.getEntry(biome);
        cache.biomeDatabase = new WeakReference<>(biomeDatabase);
        cache.biome = new WeakReference<>(biome);
        cache.entry = entry;
        return entry;
    }

    private static Heightmap.Types heightmapTypeByName(String name) {
        return switch (name) {
            case "WORLD_SURFACE_WG", "world_surface_wg" -> Heightmap.Types.WORLD_SURFACE_WG;
            case "OCEAN_FLOOR_WG", "ocean_floor_wg" -> Heightmap.Types.OCEAN_FLOOR_WG;
            case "MOTION_BLOCKING", "motion_blocking" -> Heightmap.Types.MOTION_BLOCKING;
            case "MOTION_BLOCKING_NO_LEAVES", "motion_blocking_no_leaves" -> Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
            case "OCEAN_FLOOR", "ocean_floor" -> Heightmap.Types.OCEAN_FLOOR;
            case "WORLD_SURFACE", "world_surface" -> Heightmap.Types.WORLD_SURFACE;
            default -> Heightmap.Types.valueOf(name.toUpperCase(Locale.ROOT));
        };
    }

    private static final class LevelContextCache {
        WeakReference<LevelAccessor> level;
        LevelContext context;
        GroundFinder groundFinder;

        LevelAccessor level() {
            return level == null ? null : level.get();
        }
    }

    private static final class DiscCache {
        LevelContext context;
        int chunkX;
        int chunkZ;
        List<PoissonDisc> discs;
    }

    private static final class HeightmapCache {
        WeakReference<BiomeDatabase> biomeDatabase;
        WeakReference<Holder<Biome>> biome;
        Heightmap.Types type;

        BiomeDatabase biomeDatabase() {
            return biomeDatabase == null ? null : biomeDatabase.get();
        }

        Holder<Biome> biome() {
            return biome == null ? null : biome.get();
        }
    }

    private static final class EntryCache {
        WeakReference<BiomeDatabase> biomeDatabase;
        WeakReference<Holder<Biome>> biome;
        BiomeDatabase.Entry entry;

        BiomeDatabase biomeDatabase() {
            return biomeDatabase == null ? null : biomeDatabase.get();
        }

        Holder<Biome> biome() {
            return biome == null ? null : biome.get();
        }
    }
}
