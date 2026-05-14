package dev.sixik.generator_accelerator.neoforge.structures.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.ModList;

public final class ConfluenceStructureTemplateCompatImpl {
    private static final boolean CONFLUENCE_LOADED = ModList.get().isLoaded("confluence");

    private ConfluenceStructureTemplateCompatImpl() {
    }

    public static void load(CompoundTag blockInfoTag, StructureTemplate.StructureBlockInfo blockInfo) {
        if (CONFLUENCE_LOADED) {
            ConfluenceStructureTemplateCompatHooks.load(blockInfoTag, blockInfo);
        }
    }

    public static void save(StructureTemplate.StructureBlockInfo blockInfo, CompoundTag blockInfoTag) {
        if (CONFLUENCE_LOADED) {
            ConfluenceStructureTemplateCompatHooks.save(blockInfo, blockInfoTag);
        }
    }
}
