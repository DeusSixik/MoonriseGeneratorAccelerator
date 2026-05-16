package dev.sixik.generator_accelerator.common.features.mixin.compats.biomeswevegone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.potionstudios.biomeswevegone.world.level.levelgen.util.BlendUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.function.Function;

@Mixin(value = BlendUtil.class, remap = false)
public abstract class BiomesWeveGone$BlendUtil$fast_edge {

    /**
     * @author Sixik
     * @reason Avoid BlockPos.spiralAround's iterator/direction-array allocation and compute
     * squared distance directly while preserving BWG's east/south spiral visit order.
     */
    @Overwrite(remap = false)
    public static double blendBiomeEdge(
            Holder<Biome> currentBiome,
            Function<BlockPos, Holder<Biome>> biomeGetter,
            BlockPos origin,
            int blendRadius,
            int blendStep
    ) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int size = Math.floorDiv(blendRadius, blendStep);
        int radiusSqr = blendRadius * blendRadius;
        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();

        int relX = 0;
        int relZ = 1;
        int leg = -1;
        int legSize = 0;
        int legIndex = 0;
        int legs = 4 * size;

        while (true) {
            switch ((leg + 4) & 3) {
                case 0 -> relX++;
                case 1 -> relZ++;
                case 2 -> relX--;
                default -> relZ--;
            }

            if (legIndex >= legSize) {
                if (leg >= legs) {
                    return 1.0D;
                }
                leg++;
                legIndex = 0;
                legSize = leg / 2 + 1;
            }
            legIndex++;

            int dx = relX * blendStep;
            int dz = relZ * blendStep;
            mutable.set(originX + dx, originY, originZ + dz);
            Holder<Biome> nearbyBiome = biomeGetter.apply(mutable);
            int distSqr = dx * dx + dz * dz;
            if (nearbyBiome != currentBiome && distSqr < radiusSqr) {
                return (double) distSqr / (double) radiusSqr;
            }
        }
    }
}
