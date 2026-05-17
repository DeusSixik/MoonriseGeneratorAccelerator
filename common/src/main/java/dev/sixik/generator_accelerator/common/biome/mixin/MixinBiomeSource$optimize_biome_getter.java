package dev.sixik.generator_accelerator.common.biome.mixin;

import dev.sixik.generator_accelerator.api.patches.GA$BiomeSourceExtern;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.function.Supplier;

@Mixin(BiomeSource.class)
public abstract class MixinBiomeSource$optimize_biome_getter implements GA$BiomeSourceExtern {


   /* @Mutable
    @Shadow
    @Final
    private Supplier<Set<Holder<Biome>>> possibleBiomes;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(CallbackInfo ci) {

        // Callback
        this.possibleBiomes = this::ga$getCache;
    }

    *//**
     * @author Sixik
     * @reason Redirect to fast caced version
     *//*
    @Overwrite
    public Set<Holder<Biome>> possibleBiomes() {
        return ga$getCache();
    }
*/
    @Shadow
    public abstract Holder<Biome> getNoiseBiome(int i, int j, int k, Climate.Sampler sampler);

    /**
     * @author Sixik
     * @reason Avoid allocating HashSet and use fast iteration.
     */
    @Overwrite
    public Set<Holder<Biome>> getBiomesWithin(int x, int y, int z, int radius, Climate.Sampler sampler) {
        final int minX = QuartPos.fromBlock(x - radius);
        final int minY = QuartPos.fromBlock(y - radius);
        final int minZ = QuartPos.fromBlock(z - radius);
        final int maxX = QuartPos.fromBlock(x + radius);
        final int maxY = QuartPos.fromBlock(y + radius);
        final int maxZ = QuartPos.fromBlock(z + radius);

        final int sizeX = maxX - minX + 1;
        final int sizeY = maxY - minY + 1;
        final int sizeZ = maxZ - minZ + 1;

        final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Holder<Biome>> set =
                new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();

        for(int k = 0; k < sizeZ; ++k) {
            for(int j = 0; j < sizeX; ++j) {
                for(int i = 0; i < sizeY; ++i) {
                    final int qX = minX + j;
                    final int qY = minY + i;
                    final int qZ = minZ + k;
                    set.add(this.getNoiseBiome(qX, qY, qZ, sampler));
                }
            }
        }

        return set;
    }
}
