package dev.sixik.generator_accelerator.common.structures.mixin.safe;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Mixin(value = StructureTemplate.Palette.class, priority = 0)
public abstract class MixinStructureTemplatePalette {

    @Mutable
    @Shadow
    @Final
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Shadow
    @Final
    private List<StructureTemplate.StructureBlockInfo> blocks;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void generator_accelerator$useConcurrentCache(List<StructureTemplate.StructureBlockInfo> blocks, CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>(2);
    }

    /**
     * @author Sixik
     * @reason Faster version of the original method.
     */
    @Overwrite
    public List<StructureTemplate.StructureBlockInfo> blocks(Block putBlock) {
        return this.cache.computeIfAbsent(putBlock, (block) -> {
            List<StructureTemplate.StructureBlockInfo> outList = new ObjectArrayList<>(4);

            for (StructureTemplate.StructureBlockInfo blockInfo : this.blocks) {
                if(!blockInfo.state().is(block)) continue;
                outList.add(blockInfo);
            }

            return outList;
        });
    }
}
