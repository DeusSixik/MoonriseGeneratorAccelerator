package dev.sixik.moonrisegeneratoraccelerator.common.mixin.level.strucutres;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
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
        } finally {
            bts$structuresData_structureStarts_Lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "setAllStarts")
    public void bts$setAllStarts$synchronized(Map<Structure, StructureStart> map, Operation<Void> original) {
        final long stamp = bts$structuresData_structureStarts_Lock.writeLock();
        try {
            original.call(map);
        } finally {
            bts$structuresData_structureStarts_Lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "getAllStarts")
    public Map<Structure, StructureStart> bts$getAllStarts$synchronized(Operation<Map<Structure, StructureStart>> original) {
        final long stamp = bts$structuresData_structureStarts_Lock.readLock();
        try {
            return new Reference2ObjectOpenHashMap<>(structureStarts);
        } finally {
            bts$structuresData_structureStarts_Lock.unlockRead(stamp);
        }
    }

    @WrapMethod(method = "getReferencesForStructure")
    public LongSet bts$getReferencesForStructure$synchronized(Structure structure, Operation<LongSet> original) {
        final long stamp = bts$structuresData_structuresRefences_Lock.readLock();
        try {
            return original.call(structure);
        } finally {
            bts$structuresData_structuresRefences_Lock.unlockRead(stamp);
        }
    }

    @WrapMethod(method = "addReferenceForStructure")
    public void bts$addReferenceForStructure$synchronized(Structure structure, long l, Operation<Void> original) {
        final var stamp = bts$structuresData_structuresRefences_Lock.writeLock();
        try {
            original.call(structure, l);
        } finally {
            bts$structuresData_structuresRefences_Lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "setAllReferences")
    public void bts$setAllReferences$synchronized(Map<Structure, LongSet> map, Operation<Void> original) {
        final var stamp = bts$structuresData_structuresRefences_Lock.writeLock();
        try {
            original.call(map);
        } finally {
            bts$structuresData_structuresRefences_Lock.unlockWrite(stamp);
        }
    }

    @WrapMethod(method = "getAllReferences")
    public Map<Structure, LongSet> bts$getAllReferences$synchronized(Operation<Map<Structure, LongSet>> original) {
        final var stamp = bts$structuresData_structuresRefences_Lock.readLock();
        try {
            return new Reference2ObjectOpenHashMap<>(structuresRefences);
        } finally {
            bts$structuresData_structuresRefences_Lock.unlockRead(stamp);
        }
    }
}
