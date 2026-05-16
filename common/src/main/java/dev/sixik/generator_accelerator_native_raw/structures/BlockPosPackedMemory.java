package dev.sixik.generator_accelerator_native_raw.structures;

import net.minecraft.core.BlockPos;
import net.sixik.javastructg.structs.NativeStructLayout;
import net.sixik.javastructg.structs.NativeTypeMemory;
import net.sixik.javastructg.utils.NativeUtils;
import sun.misc.Unsafe;

public final class BlockPosPackedMemory implements NativeTypeMemory<BlockPos> {

    public static final BlockPosPackedMemory MEMORY = new BlockPosPackedMemory();

    public static final NativeStructLayout LAYOUT;
    public static final long PACKED_OFFSET;
    public static final long SIZEOF;

    static {
        NativeStructLayout.Builder builder = new NativeStructLayout.Builder();
        PACKED_OFFSET = builder.longField();
        LAYOUT = builder.build();
        SIZEOF = LAYOUT.sizeof();
    }

    private BlockPosPackedMemory() { }

    @Override
    public void readFromMemory(Unsafe unsafe, long offset, BlockPos outElement) {
        long packed = unsafe.getLong(offset + PACKED_OFFSET);
        outElement.setX(BlockPos.getX(packed));
        outElement.setY(BlockPos.getY(packed));
        outElement.setZ(BlockPos.getZ(packed));
    }

    @Override
    public void writeToMemory(Unsafe unsafe, long offset, BlockPos element) {
        unsafe.putLong(offset + PACKED_OFFSET, element.asLong());
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
    public boolean equalsMemory(Unsafe unsafe, long offset, BlockPos value) {
        return unsafe.getLong(offset) == value.asLong();
    }

    @Override
    public boolean supportsHashMemory() {
        return true;
    }

    @Override
    public long hashMemory(Unsafe unsafe, long offset) {
        long packedPos = unsafe.getLong(offset + PACKED_OFFSET);
        return NativeUtils.mix(packedPos);
    }
}
