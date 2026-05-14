package dev.sixik.generator_accelerator.common.structures.compat;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.confluence.mod.mixed.IStructureTemplate$StructureBlockInfo;

final class ConfluenceStructureTemplateCompatHooks {
    private static final String COLORS_KEY = "confluence:colors";
    private static final String DROPLETS_KEY = "confluence:droplets";

    private ConfluenceStructureTemplateCompatHooks() {
    }

    static void load(CompoundTag blockInfoTag, StructureTemplate.StructureBlockInfo blockInfo) {
        if (!blockInfoTag.contains(COLORS_KEY, Tag.TAG_INT_ARRAY) && !blockInfoTag.contains(DROPLETS_KEY)) {
            return;
        }

        IStructureTemplate$StructureBlockInfo confluenceInfo = IStructureTemplate$StructureBlockInfo.of(blockInfo);
        if (blockInfoTag.contains(COLORS_KEY, Tag.TAG_INT_ARRAY)) {
            confluenceInfo.confluence$setColors(blockInfoTag.getIntArray(COLORS_KEY));
        }

        Tag dropletTag = blockInfoTag.get(DROPLETS_KEY);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (dropletTag != null && server != null) {
            ParticleTypes.CODEC.parse(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), dropletTag)
                    .ifSuccess(confluenceInfo::confluence$setDroplets);
        }
    }

    static void save(StructureTemplate.StructureBlockInfo blockInfo, CompoundTag blockInfoTag) {
        IStructureTemplate$StructureBlockInfo confluenceInfo = IStructureTemplate$StructureBlockInfo.of(blockInfo);

        int[] colors = confluenceInfo.confluence$getColors();
        if (colors != null) {
            blockInfoTag.putIntArray(COLORS_KEY, colors);
        }

        ParticleOptions particle = confluenceInfo.confluence$getDroplets();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (particle != null && server != null) {
            ParticleTypes.CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), particle)
                    .ifSuccess(tag -> blockInfoTag.put(DROPLETS_KEY, tag));
        }
    }
}
