package dev.sixik.generator_accelerator.common.structures;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class StructureBlockEntityNbtSanitizerTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void malformedSignTextIsReplacedWithValidEmptyText() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("front_text", new CompoundTag());
        nbt.put("back_text", wrongSizedText());

        CompoundTag sanitized = StructureBlockEntityNbtSanitizer.sanitizeTemplateNbt(
                Blocks.OAK_SIGN.defaultBlockState(),
                nbt
        );

        assertNotSame(nbt, sanitized);
        assertValidEmptyText(sanitized.getCompound("front_text"));
        assertValidEmptyText(sanitized.getCompound("back_text"));
    }

    @Test
    void legacySignTextIsConvertedToFrontText() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("Text1", "{\"text\":\"hello\"}");
        nbt.putString("Text2", "\"\"");
        nbt.putString("Text3", "\"\"");
        nbt.putString("Text4", "\"\"");
        nbt.putString("Color", "blue");
        nbt.putBoolean("GlowingText", true);

        CompoundTag sanitized = StructureBlockEntityNbtSanitizer.sanitizeTemplateNbt(
                Blocks.OAK_SIGN.defaultBlockState(),
                nbt
        );

        CompoundTag frontText = sanitized.getCompound("front_text");
        ListTag messages = frontText.getList("messages", Tag.TAG_STRING);
        assertEquals(4, messages.size());
        assertEquals("{\"text\":\"hello\"}", messages.getString(0));
        assertEquals("blue", frontText.getString("color"));
        assertEquals(true, frontText.getBoolean("has_glowing_text"));
    }

    @Test
    void validSignAndNonSignNbtStayShared() {
        CompoundTag validSign = new CompoundTag();
        validSign.put("front_text", validText());

        assertSame(validSign, StructureBlockEntityNbtSanitizer.sanitizeTemplateNbt(
                Blocks.OAK_SIGN.defaultBlockState(),
                validSign
        ));

        CompoundTag nonSign = new CompoundTag();
        nonSign.put("front_text", new CompoundTag());
        assertSame(nonSign, StructureBlockEntityNbtSanitizer.sanitizeTemplateNbt(
                Blocks.STONE.defaultBlockState(),
                nonSign
        ));
    }

    private static CompoundTag wrongSizedText() {
        CompoundTag text = new CompoundTag();
        ListTag messages = new ListTag();
        messages.add(StringTag.valueOf("\"\""));
        text.put("messages", messages);
        return text;
    }

    private static CompoundTag validText() {
        CompoundTag text = new CompoundTag();
        ListTag messages = new ListTag();
        for (int i = 0; i < 4; i++) {
            messages.add(StringTag.valueOf("\"\""));
        }
        text.put("messages", messages);
        return text;
    }

    private static void assertValidEmptyText(CompoundTag text) {
        ListTag messages = text.getList("messages", Tag.TAG_STRING);
        assertEquals(4, messages.size());
        for (int i = 0; i < 4; i++) {
            assertEquals("\"\"", messages.getString(i));
        }
    }
}
