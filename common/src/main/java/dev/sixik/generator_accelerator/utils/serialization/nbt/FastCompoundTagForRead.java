package dev.sixik.generator_accelerator.utils.serialization.nbt;

import net.minecraft.nbt.*;
import net.sixik.concurrent_library.utils.nbt.FastNbtReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

public class FastCompoundTagForRead extends CompoundTag {

    private final FastNbtReader.FastCompound fastCompound;

    public FastCompoundTagForRead(FastNbtReader.FastCompound fastCompound) {
        this.fastCompound = fastCompound;
    }

    public FastNbtReader.FastCompound getFastCompound() {
        return this.fastCompound;
    }

    @Override
    public boolean contains(String key) {
        return this.fastCompound.contains(key);
    }

    @Override
    public boolean contains(String key, int type) {
        if (!this.fastCompound.contains(key)) {
            return false;
        }
        if (type == Tag.TAG_ANY_NUMERIC) {
            byte t = this.fastCompound.getType(key);
            return t >= Tag.TAG_BYTE && t <= Tag.TAG_DOUBLE;
        }
        return this.fastCompound.getType(key) == (byte) type;
    }

    @Override
    public byte getByte(String key) {
        return this.fastCompound.getByte(key, (byte) 0);
    }

    @Override
    public short getShort(String key) {
        return this.fastCompound.getShort(key, (short) 0);
    }

    @Override
    public int getInt(String key) {
        return this.fastCompound.getInt(key, 0);
    }

    @Override
    public long getLong(String key) {
        return this.fastCompound.getLong(key, 0L);
    }

    @Override
    public float getFloat(String key) {
        return this.fastCompound.getFloat(key, 0.0F);
    }

    @Override
    public double getDouble(String key) {
        return this.fastCompound.getDouble(key, 0.0D);
    }

    @Override
    public String getString(String key) {
        return this.fastCompound.getString(key, "");
    }

    @Override
    public byte[] getByteArray(String key) {
        byte[] arr = this.fastCompound.getByteArray(key);
        return arr != null ? arr : new byte[0];
    }

    @Override
    public int[] getIntArray(String key) {
        int[] arr = this.fastCompound.getIntArray(key);
        return arr != null ? arr : new int[0];
    }

    @Override
    public long[] getLongArray(String key) {
        long[] arr = this.fastCompound.getLongArray(key);
        return arr != null ? arr : new long[0];
    }

    @Override
    public boolean getBoolean(String key) {
        return this.fastCompound.getBoolean(key, false);
    }

    @Override
    public CompoundTag getCompound(String key) {
        FastNbtReader.FastCompound child = this.fastCompound.getCompound(key);
        return child != null ? new FastCompoundTagForRead(child) : new CompoundTag();
    }

    @Override
    public ListTag getList(String key, int expectedType) {
        FastNbtReader.FastList fastList = this.fastCompound.getList(key);
        return fastList != null ? new FastListTagForRead(fastList) : new ListTag();
    }

    @Override
    public UUID getUUID(String key) {
        long[] array = this.fastCompound.getLongArray(key);
        if (array != null && array.length == 2) {
            return new UUID(array[0], array[1]);
        }
        return new UUID(0L, 0L);
    }

    @Override
    public boolean hasUUID(String key) {
        long[] array = this.fastCompound.getLongArray(key);
        return array != null && array.length == 2;
    }

    @Override
    public int size() {
        return this.fastCompound.size();
    }

    @Override
    public boolean isEmpty() {
        return this.fastCompound.size() == 0;
    }

    @Override
    public Set<String> getAllKeys() {
        Set<String> keys = new HashSet<>(this.fastCompound.size());
        this.fastCompound.forEach((name, type) -> keys.add(name));
        return keys;
    }

    @Override
    public @Nullable Tag get(String key) {
        if (!this.fastCompound.contains(key)) {
            return null;
        }
        byte type = this.fastCompound.getType(key);
        return switch (type) {
            case Tag.TAG_BYTE -> ByteTag.valueOf(this.fastCompound.getByte(key, (byte) 0));
            case Tag.TAG_SHORT -> ShortTag.valueOf(this.fastCompound.getShort(key, (short) 0));
            case Tag.TAG_INT -> IntTag.valueOf(this.fastCompound.getInt(key, 0));
            case Tag.TAG_LONG -> LongTag.valueOf(this.fastCompound.getLong(key, 0L));
            case Tag.TAG_FLOAT -> FloatTag.valueOf(this.fastCompound.getFloat(key, 0.0F));
            case Tag.TAG_DOUBLE -> DoubleTag.valueOf(this.fastCompound.getDouble(key, 0.0D));
            case Tag.TAG_STRING -> StringTag.valueOf(this.fastCompound.getString(key, ""));
            case Tag.TAG_BYTE_ARRAY -> new ByteArrayTag(this.getByteArray(key));
            case Tag.TAG_INT_ARRAY -> new IntArrayTag(this.getIntArray(key));
            case Tag.TAG_LONG_ARRAY -> new LongArrayTag(this.getLongArray(key));
            case Tag.TAG_COMPOUND -> this.getCompound(key);
            case Tag.TAG_LIST -> this.getList(key, 0);
            default -> null;
        };
    }
}
