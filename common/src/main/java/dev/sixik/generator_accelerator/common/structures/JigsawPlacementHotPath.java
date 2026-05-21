package dev.sixik.generator_accelerator.common.structures;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JigsawPlacementHotPath {
    private JigsawPlacementHotPath() {
    }

    public static int computeProjectionExpansion(
            List<StructureTemplate.StructureBlockInfo> jigsaws,
            BoundingBox localBounds,
            PoolAliasLookup aliasLookup,
            Registry<StructureTemplatePool> pools,
            StructureTemplateManager structureTemplateManager,
            Object2IntMap<ResourceKey<StructureTemplatePool>> poolMaxSizeCache
    ) {
        int maxSize = 0;
        int minX = localBounds.minX();
        int minY = localBounds.minY();
        int minZ = localBounds.minZ();
        int maxX = localBounds.maxX();
        int maxY = localBounds.maxY();
        int maxZ = localBounds.maxZ();

        for (int i = 0, size = jigsaws.size(); i < size; i++) {
            StructureTemplate.StructureBlockInfo jigsaw = jigsaws.get(i);
            Direction frontFacing = JigsawBlock.getFrontFacing(jigsaw.state());
            int x = jigsaw.pos().getX() + frontFacing.getStepX();
            int y = jigsaw.pos().getY() + frontFacing.getStepY();
            int z = jigsaw.pos().getZ() + frontFacing.getStepZ();
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
                continue;
            }

            ResourceKey<StructureTemplatePool> poolKey = readPoolKey(jigsaw, aliasLookup);
            int candidateSize = resolvePoolExpansion(poolKey, pools, structureTemplateManager, poolMaxSizeCache);
            if (candidateSize > maxSize) {
                maxSize = candidateSize;
            }
        }

        return maxSize;
    }

    public static int resolvePoolExpansion(
            ResourceKey<StructureTemplatePool> poolKey,
            Registry<StructureTemplatePool> pools,
            StructureTemplateManager structureTemplateManager,
            Object2IntMap<ResourceKey<StructureTemplatePool>> poolMaxSizeCache
    ) {
        int cached = poolMaxSizeCache.getInt(poolKey);
        if (cached >= 0) {
            return cached;
        }

        Optional<Holder.Reference<StructureTemplatePool>> holder = pools.getHolder(poolKey);
        int resolved = 0;
        if (holder.isPresent()) {
            StructureTemplatePool pool = holder.get().value();
            int primary = pool.getMaxSize(structureTemplateManager);
            int fallback = pool.getFallback().value().getMaxSize(structureTemplateManager);
            resolved = Math.max(primary, fallback);
        }

        poolMaxSizeCache.put(poolKey, resolved);
        return resolved;
    }

    public static BoundingBox moveAndExpand(BoundingBox localBounds, int dx, int dy, int dz, int projectionExpansion) {
        int minX = localBounds.minX() + dx;
        int minY = localBounds.minY() + dy;
        int minZ = localBounds.minZ() + dz;
        int maxX = localBounds.maxX() + dx;
        int maxY = localBounds.maxY() + dy;
        int maxZ = localBounds.maxZ() + dz;

        if (projectionExpansion > 0) {
            int extraHeight = Math.max(projectionExpansion + 1, maxY - minY);
            int expandedMaxY = minY + extraHeight;
            if (expandedMaxY > maxY) {
                maxY = expandedMaxY;
            }
        }

        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static ResourceKey<StructureTemplatePool> readPoolKey(
            StructureTemplate.StructureBlockInfo jigsaw,
            PoolAliasLookup aliasLookup
    ) {
        CompoundTag nbt = Objects.requireNonNull(jigsaw.nbt(), () -> jigsaw + " nbt was null");
        return aliasLookup.lookup(net.minecraft.data.worldgen.Pools.parseKey(nbt.getString("pool")));
    }
}
