package dev.sixik.generator_accelerator.mixins.common_mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BiomeExtension;
import dev.sixik.generator_accelerator.api.structures.FastBiomeCache;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Biome.class)
public abstract class MixinBiome$Extension implements GA$BiomeExtension {
    @Unique
    private int bts$generatorFastId = -1;

    @Override
    public int bts$getFastId() {
        if (this.bts$generatorFastId == -1) {
            this.bts$generatorFastId = FastBiomeCache.getId((Biome) (Object) this);
        }
        return this.bts$generatorFastId;
    }

    @Override
    public void bts$setFastId(int id) {
        this.bts$generatorFastId = id;
    }
}
