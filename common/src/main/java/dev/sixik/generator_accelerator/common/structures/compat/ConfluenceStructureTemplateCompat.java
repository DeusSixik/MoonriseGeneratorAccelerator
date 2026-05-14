package dev.sixik.generator_accelerator.common.structures.compat;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public final class ConfluenceStructureTemplateCompat {
    private ConfluenceStructureTemplateCompat() {
    }

    @ExpectPlatform
    public static void load(CompoundTag blockInfoTag, StructureTemplate.StructureBlockInfo blockInfo) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void save(StructureTemplate.StructureBlockInfo blockInfo, CompoundTag blockInfoTag) {
        throw new AssertionError();
    }
}
