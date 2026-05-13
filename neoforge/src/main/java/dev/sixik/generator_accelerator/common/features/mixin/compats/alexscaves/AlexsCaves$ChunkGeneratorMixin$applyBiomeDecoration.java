package dev.sixik.generator_accelerator.common.features.mixin.compats.alexscaves;

import com.bawnorton.mixinsquared.TargetHandler;
import com.github.alexmodguy.alexscaves.AlexsCaves;
import com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

@Mixin(value = ChunkGenerator.class, priority = 1500)
public class AlexsCaves$ChunkGeneratorMixin$applyBiomeDecoration {

    @TargetHandler(
            mixin = "dev.sixik.generator_accelerator.common.features.mixin.MixinChunkGenerator$apply_biome_decoration",
            name = "applyBiomeDecoration"
    )
    @Inject(method = "@MixinSquared:Handler", at = @At("TAIL"))
    private void ga$alexsCaves$applyBiomeDecoration(
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager structureManager,
            CallbackInfo ci
    ) {
        if (!(level instanceof WorldGenRegion)) {
            return;
        }

        ChunkPos chunkPos = chunk.getPos();
        int centerX = chunkPos.getMiddleBlockX();
        int centerZ = chunkPos.getMiddleBlockZ();
        Holder<Biome> acBiomeHolder = null;
        ResourceKey<Biome> acBiome = null;
        int foundY = 0;

        for (int y = -64; y <= 64; y += 16) {
            BlockPos samplePos = new BlockPos(centerX, y, centerZ);
            Holder<Biome> biomeHolder = level.getBiome(samplePos);
            for (ResourceKey<Biome> key : ACBiomeRegistry.ALEXS_CAVES_BIOMES) {
                if (biomeHolder.is(key)) {
                    acBiome = key;
                    acBiomeHolder = biomeHolder;
                    foundY = y;
                    break;
                }
            }
            if (acBiome != null) {
                break;
            }
        }

        if (acBiome == null || acBiomeHolder == null) {
            return;
        }

        List<HolderSet<PlacedFeature>> biomeFeatures = acBiomeHolder.value().getGenerationSettings().features();
        long seed = level.getSeed();
        ChunkGenerator generator = (ChunkGenerator) (Object) this;
        BlockPos featurePos = new BlockPos(centerX, foundY, centerZ);

        try {
            for (HolderSet<PlacedFeature> holderSet : biomeFeatures) {
                for (Holder<PlacedFeature> holder : holderSet) {
                    Optional<ResourceKey<PlacedFeature>> optionalKey = holder.unwrapKey();
                    if (optionalKey.isEmpty()) {
                        continue;
                    }

                    ResourceKey<PlacedFeature> key = optionalKey.get();
                    if (!AlexsCaves.MODID.equals(key.location().getNamespace())) {
                        continue;
                    }

                    long featureSeed = seed ^ (long) key.location().hashCode() ^ chunkPos.toLong();
                    RandomSource random = RandomSource.create(featureSeed);
                    try {
                        holder.value().placeWithBiomeCheck(level, generator, random, featurePos);
                    } catch (Exception exception) {
                        AlexsCaves.LOGGER.error("AC feature {} failed to place in chunk {}", key.location(), chunkPos, exception);
                    }
                }
            }
        } catch (IndexOutOfBoundsException ignored) {
            // Alex's Caves swallows stale feature-step indexes here; keep the same behavior.
        }
    }
}
