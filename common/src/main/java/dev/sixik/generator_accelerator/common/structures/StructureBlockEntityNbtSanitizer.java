package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps structure template block-entity NBT cheap to load on hot worldgen paths.
 */
public final class StructureBlockEntityNbtSanitizer {
    private static final String FRONT_TEXT = "front_text";
    private static final String BACK_TEXT = "back_text";
    private static final String MESSAGES = "messages";
    private static final String FILTERED_MESSAGES = "filtered_messages";
    private static final String EMPTY_COMPONENT_JSON = "\"\"";

    private StructureBlockEntityNbtSanitizer() {
    }

    public static CompoundTag sanitizeTemplateNbt(BlockState state, CompoundTag nbt) {
        if (nbt == null || !(state.getBlock() instanceof SignBlock)) {
            return nbt;
        }

        CompoundTag sanitized = nbt;
        if (needsFrontTextRepair(nbt)) {
            sanitized = copyIfShared(sanitized, nbt);
            sanitized.put(FRONT_TEXT, createFrontText(sanitized));
        } else {
            sanitized = sanitizeOptionalFilteredMessages(sanitized, nbt, FRONT_TEXT);
        }

        Tag backText = sanitized.get(BACK_TEXT);
        if (backText != null && !isValidSignText(backText)) {
            sanitized = copyIfShared(sanitized, nbt);
            sanitized.put(BACK_TEXT, createEmptyText());
        } else {
            sanitized = sanitizeOptionalFilteredMessages(sanitized, nbt, BACK_TEXT);
        }

        return sanitized;
    }

    private static boolean needsFrontTextRepair(CompoundTag nbt) {
        Tag frontText = nbt.get(FRONT_TEXT);
        return frontText != null && !isValidSignText(frontText) || frontText == null && hasLegacyFrontText(nbt);
    }

    private static CompoundTag sanitizeOptionalFilteredMessages(CompoundTag current, CompoundTag original, String key) {
        Tag textTag = current.get(key);
        if (!(textTag instanceof CompoundTag text) || !text.contains(FILTERED_MESSAGES)) {
            return current;
        }
        Tag filtered = text.get(FILTERED_MESSAGES);
        if (isValidMessageList(filtered)) {
            return current;
        }

        CompoundTag copiedRoot = copyIfShared(current, original);
        CompoundTag copiedText = copiedRoot.getCompound(key).copy();
        copiedText.remove(FILTERED_MESSAGES);
        copiedRoot.put(key, copiedText);
        return copiedRoot;
    }

    private static boolean isValidSignText(Tag tag) {
        if (!(tag instanceof CompoundTag text)) {
            return false;
        }
        return isValidMessageList(text.get(MESSAGES));
    }

    private static boolean isValidMessageList(Tag tag) {
        return tag instanceof ListTag messages
                && messages.size() == 4
                && messages.getElementType() == Tag.TAG_STRING;
    }

    private static boolean hasLegacyFrontText(CompoundTag nbt) {
        for (int i = 1; i <= 4; i++) {
            if (nbt.contains("Text" + i, Tag.TAG_STRING)) {
                return true;
            }
        }
        return false;
    }

    private static CompoundTag createFrontText(CompoundTag nbt) {
        CompoundTag text = createEmptyText();
        ListTag messages = new ListTag();
        for (int i = 1; i <= 4; i++) {
            String legacy = nbt.contains("Text" + i, Tag.TAG_STRING) ? nbt.getString("Text" + i) : EMPTY_COMPONENT_JSON;
            messages.add(StringTag.valueOf(legacy.isBlank() ? EMPTY_COMPONENT_JSON : legacy));
        }
        text.put(MESSAGES, messages);
        if (nbt.contains("Color", Tag.TAG_STRING)) {
            text.putString("color", nbt.getString("Color"));
        }
        if (nbt.contains("GlowingText", Tag.TAG_ANY_NUMERIC)) {
            text.putBoolean("has_glowing_text", nbt.getBoolean("GlowingText"));
        }
        return text;
    }

    private static CompoundTag createEmptyText() {
        CompoundTag text = new CompoundTag();
        ListTag messages = new ListTag();
        for (int i = 0; i < 4; i++) {
            messages.add(StringTag.valueOf(EMPTY_COMPONENT_JSON));
        }
        text.put(MESSAGES, messages);
        return text;
    }

    private static CompoundTag copyIfShared(CompoundTag current, CompoundTag original) {
        return current == original ? original.copy() : current;
    }
}
