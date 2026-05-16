package dev.sixik.generator_accelerator_native_raw.memory.dfc;

import dev.sixik.generator_accelerator.common.density.compiler.compiler.ir.CoordDep;
import net.sixik.javastructg.structs.NativeStructLayout;
import net.sixik.javastructg.structs.NativeTypeMemory;
import net.sixik.javastructg.utils.NativeUtils;
import sun.misc.Unsafe;

public class CoordDep$FlagsMemory implements NativeTypeMemory<CoordDep.Flags> {

    public static final CoordDep$FlagsMemory MEMORY = new CoordDep$FlagsMemory();

    public static final NativeStructLayout LAYOUT;
    public static final long PACKED_OFFSET;
    public static final int SIZEOF;

    static {
        NativeStructLayout.Builder builder = new NativeStructLayout.Builder();
        PACKED_OFFSET = builder.byteField();
        LAYOUT = builder.build();
        SIZEOF = (int) LAYOUT.sizeof();
    }

    private CoordDep$FlagsMemory() {}

    @Override
    public void readFromMemory(Unsafe unsafe, long offset, CoordDep.Flags outElement) {
        byte packed = unsafe.getByte(offset + PACKED_OFFSET);
        outElement.setUsesX((packed & 1) != 0);
        outElement.setUsesY((packed & 2) != 0);
        outElement.setUsesZ((packed & 4) != 0);
    }

    @Override
    public void writeToMemory(Unsafe unsafe, long offset, CoordDep.Flags element) {
        byte packed = (byte) ((element.usesX() ? 1 : 0) |
                (element.usesY() ? 2 : 0) |
                (element.usesZ() ? 4 : 0));

        unsafe.putByte(offset + PACKED_OFFSET, packed);
    }

    @Override
    public long sizeof() {
        return SIZEOF;
    }

    @Override
    public boolean supportsHashMemory() {
        return true;
    }

    @Override
    public long hashMemory(Unsafe unsafe, long offset) {
        int packed = unsafe.getByte(offset + PACKED_OFFSET) & 0xFF;
        return NativeUtils.mix(packed);
    }

    @Override
    public boolean supportsEqualsMemory() {
        return true;
    }

    @Override
    public boolean equalsMemory(Unsafe unsafe, long offset, CoordDep.Flags value) {
        byte memoryFlags = unsafe.getByte(offset + PACKED_OFFSET);
        byte objectFlags = (byte) ((value.usesX() ? 1 : 0) |
                (value.usesY() ? 2 : 0) |
                (value.usesZ() ? 4 : 0));

        return memoryFlags == objectFlags;
    }
}
