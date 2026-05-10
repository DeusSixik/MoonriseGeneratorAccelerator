package dev.sixik.generator_accelerator.common.structures.mixin.safe;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(StructureTemplate.Palette.class)
public interface StructureTemplatePaletteInvoker {

    @Invoker("<init>")
    static StructureTemplate.Palette generator_accelerator$createPalette(List<StructureTemplate.StructureBlockInfo> blocks) {
        throw new AssertionError();
    }
}
