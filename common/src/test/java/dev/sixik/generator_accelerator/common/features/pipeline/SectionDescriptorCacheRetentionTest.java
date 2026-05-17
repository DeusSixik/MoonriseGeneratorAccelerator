package dev.sixik.generator_accelerator.common.features.pipeline;

import dev.sixik.generator_accelerator_native_raw.memory.features.SectionDescriptorMemory;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionDescriptorCacheRetentionTest {

    @Test
    void clearTrimsOnlyExcessivelyLargeDescriptorCaches() throws Exception {
        SectionDescriptorCache cache = new SectionDescriptorCache();
        setField(cache, "chunks", new ChunkAccess[2_048]);
        setField(cache, "keys", new long[2_048]);
        setField(cache, "descriptors", nativeDescriptorArray(2_048));
        setField(cache, "heightChunks", new ChunkAccess[512]);
        setField(cache, "heightChunkKeys", new long[512]);
        setField(cache, "worldSurfaceHeights", new short[512 * SectionDescriptor.COLUMN_COUNT]);
        setField(cache, "oceanFloorHeights", new short[512 * SectionDescriptor.COLUMN_COUNT]);
        setField(cache, "motionBlockingHeights", new short[512 * SectionDescriptor.COLUMN_COUNT]);
        setField(cache, "topWaterHeights", new short[512 * SectionDescriptor.COLUMN_COUNT]);
        setField(cache, "chunkColumnPaletteFlags", new int[512 * SectionDescriptor.COLUMN_COUNT]);
        setField(cache, "chunkColumnBlockClassFlags", new int[512 * SectionDescriptor.COLUMN_COUNT]);
        setField(cache, "heightScanDescriptors", nativeDescriptorArray(1_024));

        for (int i = 0; i < 4; i++) {
            cache.clear();
        }

        String summary = cache.debugSummary();
        assertTrue(summary.contains("descriptorSize=0/256"), summary);
        assertTrue(summary.contains("heightEntries=0/64"), summary);
        assertTrue(summary.contains("heightScanCap=128"), summary);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object nativeDescriptorArray(int capacity) throws Exception {
        Class<?> memoryClass = Class.forName("net.sixik.javastructg.structs.NativeTypeMemory");
        Class<?> arrayClass = Class.forName("net.sixik.javastructg.structs.arrays.NativeObjectArray");
        Constructor<?> constructor = arrayClass.getConstructor(int.class, memoryClass);
        return constructor.newInstance(capacity, SectionDescriptorMemory.MEMORY);
    }
}
