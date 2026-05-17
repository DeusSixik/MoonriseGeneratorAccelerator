package dev.sixik.generator_accelerator_native_raw.memory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.sixik.javastructg.structs.NativeStructLayout;
import net.sixik.javastructg.structs.NativeTypeMemory;
import net.sixik.javastructg.utils.NativeUtils;
import sun.misc.Unsafe;

public class BoundingBoxPackedMemory implements NativeTypeMemory<BoundingBox> {

    public static final BoundingBoxPackedMemory MEMORY = new BoundingBoxPackedMemory();

    public static final NativeStructLayout LAYOUT;
    public static final long MIN_PACKED_OFFSET;
    public static final long MAX_PACKED_OFFSET;
    public static final long SIZEOF;

    static {
        NativeStructLayout.Builder builder = new NativeStructLayout.Builder();
        MIN_PACKED_OFFSET = builder.longField();
        MAX_PACKED_OFFSET = builder.longField();
        LAYOUT = builder.build();
        SIZEOF = LAYOUT.sizeof();
    }

    private BoundingBoxPackedMemory() { }


    @Override
    public void readFromMemory(Unsafe unsafe, long offset, BoundingBox outElement) {
        long min = unsafe.getLong(offset + MIN_PACKED_OFFSET);
        long max = unsafe.getLong(offset + MAX_PACKED_OFFSET);
        outElement.minX = BlockPos.getX(min);
        outElement.minY = BlockPos.getY(min);
        outElement.minZ = BlockPos.getZ(min);
        outElement.maxX = BlockPos.getX(max);
        outElement.maxY = BlockPos.getY(max);
        outElement.maxZ = BlockPos.getZ(max);
    }

    @Override
    public void writeToMemory(Unsafe unsafe, long offset, BoundingBox element) {
        long minPacked = BlockPos.asLong(element.minX(), element.minY(), element.minZ());
        long maxPacked = BlockPos.asLong(element.maxX(), element.maxY(), element.maxZ());

        unsafe.putLong(offset + MIN_PACKED_OFFSET, minPacked);
        unsafe.putLong(offset + MAX_PACKED_OFFSET, maxPacked);
    }

    @Override
    public long sizeof() {
        return SIZEOF;
    }

    @Override
    public boolean supportsEqualsMemory() {
        return true;
    }

    @Override
    public boolean equalsMemory(Unsafe unsafe, long offset, BoundingBox value) {
        long expectedMin = BlockPos.asLong(value.minX(), value.minY(), value.minZ());
        if (unsafe.getLong(offset + MIN_PACKED_OFFSET) != expectedMin) return false;

        long expectedMax = BlockPos.asLong(value.maxX(), value.maxY(), value.maxZ());
        return unsafe.getLong(offset + MAX_PACKED_OFFSET) == expectedMax;
    }

    @Override
    public boolean supportsHashMemory() {
        return true;
    }

    @Override
    public long hashMemory(Unsafe unsafe, long offset) {
        long minPacked = unsafe.getLong(offset + MIN_PACKED_OFFSET);
        long maxPacked = unsafe.getLong(offset + MAX_PACKED_OFFSET);
        return NativeUtils.mix(minPacked ^ NativeUtils.mix(maxPacked));
    }
}
