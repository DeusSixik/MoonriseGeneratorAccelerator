package dev.sixik.generator_accelerator.utils.serialization.nbt;

import net.minecraft.nbt.*;
import net.sixik.concurrent_library.utils.nbt.FastNbtWriter;
import org.jetbrains.annotations.Nullable;

import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class FastCompoundTagForWrite extends CompoundTag {

    private final FastNbtWriter writer;
    private final Map<String, Tag> deferredTags = new LinkedHashMap<>();

    public FastCompoundTagForWrite() {
        this.writer = new FastNbtWriter();
    }

    public FastCompoundTagForWrite(FastNbtWriter writer) {
        this.writer = writer;
    }

    public FastNbtWriter getWriter() {
        return this.writer;
    }

    public byte[] toByteArray() {
        return this.writer.toByteArray();
    }

    @Override
    public void putByte(String key, byte value) {
        this.writer.putByte(key, value);
    }

    @Override
    public void putShort(String key, short value) {
        this.writer.putShort(key, value);
    }

    @Override
    public void putInt(String key, int value) {
        this.writer.putInt(key, value);
    }

    @Override
    public void putLong(String key, long value) {
        this.writer.putLong(key, value);
    }

    @Override
    public void putFloat(String key, float value) {
        this.writer.putFloat(key, value);
    }

    @Override
    public void putDouble(String key, double value) {
        this.writer.putDouble(key, value);
    }

    @Override
    public void putString(String key, String value) {
        this.writer.putString(key, value);
    }

    @Override
    public void putByteArray(String key, byte[] value) {
        this.writer.putByteArray(key, value);
    }

    @Override
    public void putIntArray(String key, int[] value) {
        this.writer.putIntArray(key, value);
    }

    @Override
    public void putLongArray(String key, long[] value) {
        this.writer.putLongArray(key, value);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        this.writer.putBoolean(key, value);
    }

    @Override
    public void putUUID(String key, UUID value) {
        long most = value.getMostSignificantBits();
        long least = value.getLeastSignificantBits();
        this.writer.putIntArray(key, new int[]{
                (int) (most >>> 32),
                (int) most,
                (int) (least >>> 32),
                (int) least
        });
    }

    @Override
    public @Nullable Tag put(String key, Tag tag) {
        // ChunkSerializer attaches compound/list objects before it has finished filling them.
        // Keep references and encode the final state only from write(DataOutput).
        if (tag instanceof CompoundTag || tag instanceof ListTag) {
            return this.deferredTags.put(key, tag);
        }

        byte type = tag.getId();
        switch (type) {
            case Tag.TAG_BYTE -> this.writer.putByte(key, ((ByteTag) tag).getAsByte());
            case Tag.TAG_SHORT -> this.writer.putShort(key, ((ShortTag) tag).getAsShort());
            case Tag.TAG_INT -> this.writer.putInt(key, ((IntTag) tag).getAsInt());
            case Tag.TAG_LONG -> this.writer.putLong(key, ((LongTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> this.writer.putFloat(key, ((FloatTag) tag).getAsFloat());
            case Tag.TAG_DOUBLE -> this.writer.putDouble(key, ((DoubleTag) tag).getAsDouble());
            case Tag.TAG_STRING -> this.writer.putString(key, tag.getAsString());
            case Tag.TAG_BYTE_ARRAY -> this.writer.putByteArray(key, ((ByteArrayTag) tag).getAsByteArray());
            case Tag.TAG_INT_ARRAY -> this.writer.putIntArray(key, ((IntArrayTag) tag).getAsIntArray());
            case Tag.TAG_LONG_ARRAY -> this.writer.putLongArray(key, ((LongArrayTag) tag).getAsLongArray());
            case Tag.TAG_COMPOUND -> {
                this.writer.beginCompound(key);
                CompoundTag compoundTag = (CompoundTag) tag;
                for (String childKey : compoundTag.getAllKeys()) {
                    Tag childValue = compoundTag.get(childKey);
                    if (childValue != null) {
                        this.put(childKey, childValue);
                    }
                }
                this.writer.endCompound();
            }
            case Tag.TAG_LIST -> {
                ListTag listTag = (ListTag) tag;
                byte elementType = (byte) listTag.getElementType();
                this.writer.beginList(key, elementType);
                for (int i = 0; i < listTag.size(); i++) {
                    writeListTagElement(this.writer, listTag.get(i), elementType);
                }
                this.writer.endList();
            }
        }
        return null;
    }

    private static void writeListTagElement(FastNbtWriter writer, Tag tag, byte elementType) {
        switch (elementType) {
            case Tag.TAG_BYTE -> writer.addByte(((ByteTag) tag).getAsByte());
            case Tag.TAG_SHORT -> writer.addShort(((ShortTag) tag).getAsShort());
            case Tag.TAG_INT -> writer.addInt(((IntTag) tag).getAsInt());
            case Tag.TAG_LONG -> writer.addLong(((LongTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> writer.addFloat(((FloatTag) tag).getAsFloat());
            case Tag.TAG_DOUBLE -> writer.addDouble(((DoubleTag) tag).getAsDouble());
            case Tag.TAG_STRING -> writer.addString(tag.getAsString());
            case Tag.TAG_BYTE_ARRAY -> writer.addByteArray(((ByteArrayTag) tag).getAsByteArray());
            case Tag.TAG_INT_ARRAY -> writer.addIntArray(((IntArrayTag) tag).getAsIntArray());
            case Tag.TAG_LONG_ARRAY -> writer.addLongArray(((LongArrayTag) tag).getAsLongArray());
            case Tag.TAG_COMPOUND -> {
                if (tag instanceof FastCompoundTagForWrite fastCompoundTag) {
                    writer.beginCompound();
                    fastCompoundTag.writePayloadTo(writer);
                    writer.endCompound();
                    return;
                }
                writer.beginCompound();
                CompoundTag compoundTag = (CompoundTag) tag;
                for (String k : compoundTag.getAllKeys()) {
                    Tag v = compoundTag.get(k);
                    if (v != null) {
                        putToWriterDirect(writer, k, v);
                    }
                }
                writer.endCompound();
            }
        }
    }

    private static void putToWriterDirect(FastNbtWriter writer, String key, Tag tag) {
        byte type = tag.getId();
        switch (type) {
            case Tag.TAG_BYTE -> writer.putByte(key, ((ByteTag) tag).getAsByte());
            case Tag.TAG_SHORT -> writer.putShort(key, ((ShortTag) tag).getAsShort());
            case Tag.TAG_INT -> writer.putInt(key, ((IntTag) tag).getAsInt());
            case Tag.TAG_LONG -> writer.putLong(key, ((LongTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> writer.putFloat(key, ((FloatTag) tag).getAsFloat());
            case Tag.TAG_DOUBLE -> writer.putDouble(key, ((DoubleTag) tag).getAsDouble());
            case Tag.TAG_STRING -> writer.putString(key, tag.getAsString());
            case Tag.TAG_BYTE_ARRAY -> writer.putByteArray(key, ((ByteArrayTag) tag).getAsByteArray());
            case Tag.TAG_INT_ARRAY -> writer.putIntArray(key, ((IntArrayTag) tag).getAsIntArray());
            case Tag.TAG_LONG_ARRAY -> writer.putLongArray(key, ((LongArrayTag) tag).getAsLongArray());
            case Tag.TAG_COMPOUND -> {
                if (tag instanceof FastCompoundTagForWrite fastCompoundTag) {
                    writer.beginCompound(key);
                    fastCompoundTag.writePayloadTo(writer);
                    writer.endCompound();
                    return;
                }
                writer.beginCompound(key);
                CompoundTag compoundTag = (CompoundTag) tag;
                for (String childKey : compoundTag.getAllKeys()) {
                    Tag childValue = compoundTag.get(childKey);
                    if (childValue != null) {
                        putToWriterDirect(writer, childKey, childValue);
                    }
                }
                writer.endCompound();
            }
            case Tag.TAG_LIST -> {
                ListTag listTag = (ListTag) tag;
                byte elementType = listTag.getElementType();
                writer.beginList(key, elementType);
                for (int i = 0; i < listTag.size(); i++) {
                    writeListTagElement(writer, listTag.get(i), elementType);
                }
                writer.endList();
            }
        }
    }

    private void writePayloadTo(FastNbtWriter target) {
        byte[] immediatePayload = this.writer.toByteArray();
        if (immediatePayload.length > 0) {
            target.writeRaw(immediatePayload);
        }

        for (Map.Entry<String, Tag> entry : this.deferredTags.entrySet()) {
            putToWriterDirect(target, entry.getKey(), entry.getValue());
        }
    }

    @Override
    public boolean contains(String key) {
        return this.deferredTags.containsKey(key);
    }

    @Override
    public boolean isEmpty() {
        return this.writer.size() == 0;
    }

    @Override
    public Set<String> getAllKeys() {
        return this.deferredTags.keySet();
    }

    @Override
    public void write(DataOutput dataOutput) throws IOException {
        FastNbtWriter payloadWriter = new FastNbtWriter(this.writer.size() + 1024);
        this.writePayloadTo(payloadWriter);
        byte[] bytes = payloadWriter.toByteArray();
        dataOutput.write(bytes);
        // NbtIo writes the root header, while CompoundTag.write must write its payload.
        // A compound payload is always terminated by TAG_End.
        dataOutput.writeByte(Tag.TAG_END);
    }
}
