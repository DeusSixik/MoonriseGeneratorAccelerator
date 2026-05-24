package dev.sixik.generator_accelerator.common.structures.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.sixik.generator_accelerator.common.structures.StructureReferenceSnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.StructureAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.*;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.locks.StampedLock;

/**
 * Concurrency note:
 * <p>
 * In profiling/tests, writes to structure data showed low contention, so this implementation
 * uses {@code Reference2ObjectOpenHashMap} guarded by a {@code StampedLock} instead of a
 * {@code ConcurrentHashMap}. This is expected to be faster in the common case.
 * </p>
 */
@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess$SynchronizeStructureData implements BlockGetter,
        BiomeManager.NoiseBiomeSource,
        LightChunk,
        StructureAccess {

    @Shadow
    @Final
    @Mutable
    private Map<Structure, StructureStart> structureStarts = new Reference2ObjectOpenHashMap<>();

    @Shadow
    @Final
    @Mutable
    private Map<Structure, LongSet> structuresRefences = new Reference2ObjectOpenHashMap<>();

    @Unique
    private final StampedLock bts$structuresData_structureStarts_Lock = new StampedLock();

    @Unique
    private final StampedLock bts$structuresData_structuresRefences_Lock = new StampedLock();

    @Unique
    private volatile Map<Structure, StructureStart> bts$structureStartsSnapshot;

    @Unique
    private volatile Map<Structure, LongSet> bts$structureReferencesSnapshot;

    @WrapMethod(method = "getStartForStructure")
    public StructureStart bts$getStartForStructure$synchronized(Structure structure, Operation<StructureStart> original) {
        final long stamp = bts$structuresData_structureStarts_Lock.readLock();
        try {
           return original.call(structure);
        } finally {
            bts$structuresData_structureStarts_Lock.unlockRead(stamp);
        }

    }

    @WrapMethod(method = "setStartForStructure")
    public void bts$setStartForStructure$synchronized(Structure structure, StructureStart structureStart, Operation<Void> original) {
        final long stamp = bts$structuresData_structureStarts_Lock.writeLock();
        try {
            original.call(structure, structureStart);
            bts$structureStartsSnapshot = null;
        } finally {
            bts$structuresData_structureStarts_Lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "setAllStarts")
    public void bts$setAllStarts$synchronized(Map<Structure, StructureStart> map, Operation<Void> original) {
        final long stamp = bts$structuresData_structureStarts_Lock.writeLock();
        try {
            original.call(map);
            bts$structureStartsSnapshot = null;
        } finally {
            bts$structuresData_structureStarts_Lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "getAllStarts")
    public Map<Structure, StructureStart> bts$getAllStarts$synchronized(Operation<Map<Structure, StructureStart>> original) {
        Map<Structure, StructureStart> snapshot = bts$structureStartsSnapshot;
        if (snapshot != null) {
            return snapshot;
        }
        final long stamp = bts$structuresData_structureStarts_Lock.readLock();
        try {
            snapshot = bts$structureStartsSnapshot;
            if (snapshot == null) {
                snapshot = Collections.unmodifiableMap(new Reference2ObjectOpenHashMap<>(structureStarts));
                bts$structureStartsSnapshot = snapshot;
            }
            return snapshot;
        } finally {
            bts$structuresData_structureStarts_Lock.unlockRead(stamp);
        }
    }

    @WrapMethod(method = "getReferencesForStructure")
    public LongSet bts$getReferencesForStructure$synchronized(Structure structure, Operation<LongSet> original) {
        final long stamp = bts$structuresData_structuresRefences_Lock.readLock();
        try {
            return StructureReferenceSnapshot.copyReferences(original.call(structure));
        } finally {
            bts$structuresData_structuresRefences_Lock.unlockRead(stamp);
        }
    }

    @WrapMethod(method = "addReferenceForStructure")
    public void bts$addReferenceForStructure$synchronized(Structure structure, long l, Operation<Void> original) {
        final var stamp = bts$structuresData_structuresRefences_Lock.writeLock();
        try {
            original.call(structure, l);
            bts$structureReferencesSnapshot = null;
        } finally {
            bts$structuresData_structuresRefences_Lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "setAllReferences")
    public void bts$setAllReferences$synchronized(Map<Structure, LongSet> map, Operation<Void> original) {
        final var stamp = bts$structuresData_structuresRefences_Lock.writeLock();
        try {
            original.call(map);
            bts$structureReferencesSnapshot = null;
        } finally {
            bts$structuresData_structuresRefences_Lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "getAllReferences")
    public Map<Structure, LongSet> bts$getAllReferences$synchronized(Operation<Map<Structure, LongSet>> original) {
        Map<Structure, LongSet> snapshot = bts$structureReferencesSnapshot;
        if (snapshot != null) {
            return snapshot;
        }
        final var stamp = bts$structuresData_structuresRefences_Lock.readLock();
        try {
            snapshot = bts$structureReferencesSnapshot;
            if (snapshot == null) {
                snapshot = StructureReferenceSnapshot.copyReferences(structuresRefences);
                bts$structureReferencesSnapshot = snapshot;
            }
            return snapshot;
        } finally {
            bts$structuresData_structuresRefences_Lock.unlockRead(stamp);
        }
    }
}
