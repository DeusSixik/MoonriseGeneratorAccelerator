package dev.sixik.generator_accelerator.common.structures.mixin.safe;

import dev.sixik.generator_accelerator.common.structures.ChunkKeyedBooleanCache;
import dev.sixik.generator_accelerator.common.structures.StructureGenerationHotPath;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = StructureCheck.class, priority = 0)
public abstract class MixinStructureCheck {

    @Unique
    private final Object generator_accelerator$loadedChunksLock = new Object();

    @Unique
    private final Object generator_accelerator$featureChecksLock = new Object();

    @Unique
    private final ChunkKeyedBooleanCache<Structure> generator_accelerator$featureChecksByChunk = new ChunkKeyedBooleanCache<>();

    @Unique
    private final Object generator_accelerator$storageStateLock = new Object();

    @Unique
    private final Long2ByteOpenHashMap generator_accelerator$storageStateByChunk = new Long2ByteOpenHashMap();

    @Unique
    private static final byte generator_accelerator$storageUnknown = 0;

    @Unique
    private static final byte generator_accelerator$storageNoData = 1;

    @Unique
    private static final byte generator_accelerator$storageChunkLoadNeeded = 2;

    @Shadow
    @Final
    private Long2ObjectMap<Object2IntMap<Structure>> loadedChunks;

    @Shadow
    @Final
    private Map<Structure, Long2BooleanMap> featureChecks;

    @Shadow
    @Final
    private long seed;

    @Shadow
    @Final
    private RegistryAccess registryAccess;

    @Shadow
    @Final
    private ChunkGenerator chunkGenerator;

    @Shadow
    @Final
    private BiomeSource biomeSource;

    @Shadow
    @Final
    private RandomState randomState;

    @Shadow
    @Final
    private StructureTemplateManager structureTemplateManager;

    @Shadow
    @Final
    private LevelHeightAccessor heightAccessor;

    @Shadow
    @Nullable
    private StructureCheckResult tryLoadFromStorage(ChunkPos chunkPos, Structure structure, boolean skipReferencedStructures, long chunkKey) {
        throw new AssertionError();
    }

    @Shadow
    private StructureCheckResult checkStructureInfo(Object2IntMap<Structure> structureRefs, Structure structure, boolean skipReferencedStructures) {
        throw new AssertionError();
    }

    /**
     * @author Sixik
     * @reason Protect StructureCheck's mutable caches without synchronized map wrappers on every operation.
     */
    @Overwrite
    public StructureCheckResult checkStart(ChunkPos chunkPos, Structure structure, StructurePlacement structurePlacement, boolean skipReferencedStructures) {
        long chunkKey = chunkPos.toLong();
        Object2IntMap<Structure> structureRefs = generator_accelerator$getLoadedChunk(chunkKey);
        if (structureRefs != null) {
            return this.checkStructureInfo(structureRefs, structure, skipReferencedStructures);
        }

        StructureCheckResult storageResult = this.generator_accelerator$tryLoadFromStorageCached(chunkPos, structure, skipReferencedStructures, chunkKey);
        if (storageResult != null) {
            return storageResult;
        }

        if (!structurePlacement.applyAdditionalChunkRestrictions(chunkPos.x, chunkPos.z, this.seed)) {
            return StructureCheckResult.START_NOT_PRESENT;
        }

        boolean canGenerate = generator_accelerator$getOrComputeFeatureCheck(chunkPos, structure, chunkKey);
        structureRefs = generator_accelerator$getLoadedChunk(chunkKey);
        if (structureRefs != null) {
            return this.checkStructureInfo(structureRefs, structure, skipReferencedStructures);
        }

        return canGenerate ? StructureCheckResult.CHUNK_LOAD_NEEDED : StructureCheckResult.START_NOT_PRESENT;
    }

    @Inject(method = "storeFullResults", at = @At("HEAD"), cancellable = true)
    private void generator_accelerator$storeFullResults(long chunkKey, Object2IntMap<Structure> structureRefs, CallbackInfo ci) {
        synchronized (this.generator_accelerator$loadedChunksLock) {
            this.loadedChunks.put(chunkKey, generator_accelerator$publishStructureRefs(structureRefs));
        }

        synchronized (this.generator_accelerator$featureChecksLock) {
            this.generator_accelerator$featureChecksByChunk.removeChunk(chunkKey);
        }
        synchronized (this.generator_accelerator$storageStateLock) {
            this.generator_accelerator$storageStateByChunk.remove(chunkKey);
        }
        ci.cancel();
    }

    /**
     * @author Sixik
     * @reason Update loaded chunk refs with copy-on-write so concurrent readers never observe an in-place mutation.
     */
    @Overwrite
    public void incrementReference(ChunkPos chunkPos, Structure structure) {
        long chunkKey = chunkPos.toLong();
        synchronized (this.generator_accelerator$loadedChunksLock) {
            Object2IntMap<Structure> current = this.loadedChunks.get(chunkKey);
            Object2IntOpenHashMap<Structure> updated = current == null || current.isEmpty()
                    ? new Object2IntOpenHashMap<>()
                    : new Object2IntOpenHashMap<>(current);
            updated.put(structure, updated.getOrDefault(structure, 0) + 1);
            this.loadedChunks.put(chunkKey, generator_accelerator$publishStructureRefs(updated));
        }
    }

    @Unique
    private Object2IntMap<Structure> generator_accelerator$getLoadedChunk(long chunkKey) {
        synchronized (this.generator_accelerator$loadedChunksLock) {
            return this.loadedChunks.get(chunkKey);
        }
    }

    @Unique
    private boolean generator_accelerator$getOrComputeFeatureCheck(ChunkPos chunkPos, Structure structure, long chunkKey) {
        synchronized (this.generator_accelerator$featureChecksLock) {
            Boolean cached = this.generator_accelerator$featureChecksByChunk.get(chunkKey, structure);
            if (cached != null) {
                return cached;
            }
        }

        boolean computed = this.generator_accelerator$canCreateStructure(chunkPos, structure);

        synchronized (this.generator_accelerator$featureChecksLock) {
            return this.generator_accelerator$featureChecksByChunk.getOrCompute(chunkKey, structure, () -> computed);
        }
    }

    @Unique
    @Nullable
    private StructureCheckResult generator_accelerator$tryLoadFromStorageCached(ChunkPos chunkPos, Structure structure, boolean skipReferencedStructures, long chunkKey) {
        byte cachedState;
        synchronized (this.generator_accelerator$storageStateLock) {
            cachedState = this.generator_accelerator$storageStateByChunk.get(chunkKey);
        }

        if (cachedState == generator_accelerator$storageNoData) {
            return null;
        }
        if (cachedState == generator_accelerator$storageChunkLoadNeeded) {
            return StructureCheckResult.CHUNK_LOAD_NEEDED;
        }

        StructureCheckResult result = this.tryLoadFromStorage(chunkPos, structure, skipReferencedStructures, chunkKey);
        synchronized (this.generator_accelerator$storageStateLock) {
            if (result == null) {
                this.generator_accelerator$storageStateByChunk.put(chunkKey, generator_accelerator$storageNoData);
            } else if (result == StructureCheckResult.CHUNK_LOAD_NEEDED) {
                this.generator_accelerator$storageStateByChunk.put(chunkKey, generator_accelerator$storageChunkLoadNeeded);
            } else {
                this.generator_accelerator$storageStateByChunk.remove(chunkKey);
            }
        }
        return result;
    }

    @Unique
    private boolean generator_accelerator$canCreateStructure(ChunkPos chunkPos, Structure structure) {
        return structure.findValidGenerationPoint(
                StructureGenerationHotPath.createContext(
                        structure,
                        this.registryAccess,
                        this.chunkGenerator,
                        this.biomeSource,
                        this.randomState,
                        this.structureTemplateManager,
                        this.seed,
                        chunkPos,
                        this.heightAccessor
                )
        ).isPresent();
    }

    @Unique
    private static Object2IntMap<Structure> generator_accelerator$publishStructureRefs(Object2IntMap<Structure> structureRefs) {
        if (structureRefs.isEmpty()) {
            return Object2IntMaps.emptyMap();
        }

        if (structureRefs instanceof Object2IntOpenHashMap<?> openHashMap) {
            openHashMap.trim();
        }
        return structureRefs;
    }
}
