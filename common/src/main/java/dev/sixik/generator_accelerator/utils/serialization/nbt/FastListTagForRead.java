package dev.sixik.generator_accelerator.utils.serialization.nbt;

import net.minecraft.nbt.*;
import net.sixik.concurrent_library.utils.nbt.FastNbtReader;
import org.jetbrains.annotations.NotNull;

import java.util.AbstractList;

public class FastListTagForRead extends ListTag {

    private final FastNbtReader.FastList fastList;

    public FastListTagForRead(FastNbtReader.FastList fastList) {
        this.fastList = fastList;
    }

    public FastNbtReader.FastList getFastList() {
        return this.fastList;
    }

    @Override
    public int size() {
        return this.fastList.size();
    }

    @Override
    public boolean isEmpty() {
        return this.fastList.size() == 0;
    }

    @Override
    public byte getElementType() {
        return this.fastList.getElementType();
    }

    @Override
    public CompoundTag getCompound(int index) {
        FastNbtReader.FastCompound compound = this.fastList.getCompound(index);
        return compound != null ? new FastCompoundTagForRead(compound) : new CompoundTag();
    }

    @Override
    public ListTag getList(int index) {
        // FastList не хранит вложенные списки напрямую, fallback к новому
        return new ListTag();
    }

    @Override
    public short getShort(int index) {
        return this.fastList.getShort(index);
    }

    @Override
    public int getInt(int index) {
        return this.fastList.getInt(index);
    }

    @Override
    public int[] getIntArray(int index) {
        return new int[0];
    }

    @Override
    public long[] getLongArray(int index) {
        return new long[0];
    }

    @Override
    public double getDouble(int index) {
        return this.fastList.getDouble(index);
    }

    @Override
    public float getFloat(int index) {
        return this.fastList.getFloat(index);
    }

    @Override
    public String getString(int index) {
        String str = this.fastList.getString(index);
        return str != null ? str : "";
    }

    @Override
    public Tag get(int index) {
        byte type = this.fastList.getElementType();
        return switch (type) {
            case Tag.TAG_BYTE -> ByteTag.valueOf(this.fastList.getByte(index));
            case Tag.TAG_SHORT -> ShortTag.valueOf(this.fastList.getShort(index));
            case Tag.TAG_INT -> IntTag.valueOf(this.fastList.getInt(index));
            case Tag.TAG_LONG -> LongTag.valueOf(this.fastList.getLong(index));
            case Tag.TAG_FLOAT -> FloatTag.valueOf(this.fastList.getFloat(index));
            case Tag.TAG_DOUBLE -> DoubleTag.valueOf(this.fastList.getDouble(index));
            case Tag.TAG_STRING -> StringTag.valueOf(this.fastList.getString(index));
            case Tag.TAG_COMPOUND -> this.getCompound(index);
            default -> EndTag.INSTANCE;
        };
    }
}
