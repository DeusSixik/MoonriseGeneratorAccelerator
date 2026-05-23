package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$StructureExtension;
import dev.sixik.generator_accelerator.api.structures.FastStructureCache;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Structure.class)
public abstract class MixinStructure$Extension implements GA$StructureExtension {
    @Unique
    private int bts$generatorFastId = -1;

    @Override
    public int bts$getFastId() {
        if (this.bts$generatorFastId == -1) {
            this.bts$generatorFastId = FastStructureCache.getId((Structure) (Object) this);
        }
        return this.bts$generatorFastId;
    }

    @Override
    public void bts$setFastId(int id) {
        this.bts$generatorFastId = id;
    }
}
