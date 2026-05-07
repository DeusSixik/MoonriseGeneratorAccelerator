package dev.sixik.generator_accelerator.common.features.mixin.features;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SculkSpreader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = SculkSpreader.ChargeCursor.class, priority = 999)
public abstract class MixinSculkSpreaderChargeCursor {
    @Shadow
    @Final
    private static ObjectArrayList<Vec3i> NON_CORNER_NEIGHBOURS;

    @Unique
    private static final ThreadLocal<ObjectArrayList<Vec3i>> GA$SHUFFLED_NEIGHBOURS =
            ThreadLocal.withInitial(() -> new ObjectArrayList<>(18));

    /**
     * @author Sixik
     * @reason Preserve Util.shuffle RNG order while reusing the temporary neighbour list.
     */
    @Overwrite
    private static List<Vec3i> getRandomizedNonCornerNeighbourOffsets(RandomSource random) {
        ObjectArrayList<Vec3i> shuffled = GA$SHUFFLED_NEIGHBOURS.get();
        shuffled.clear();
        shuffled.addAll(NON_CORNER_NEIGHBOURS);

        for (int j = shuffled.size(); j > 1; --j) {
            int k = random.nextInt(j);
            shuffled.set(j - 1, shuffled.set(k, shuffled.get(j - 1)));
        }
        return shuffled;
    }
}
