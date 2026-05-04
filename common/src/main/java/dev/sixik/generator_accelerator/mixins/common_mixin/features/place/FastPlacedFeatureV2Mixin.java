package dev.sixik.generator_accelerator.mixins.common_mixin.features.place;

import dev.sixik.generator_accelerator.api.patches.GA$PlacementModifierExtension;
import dev.sixik.generator_accelerator.common.features.PrimitivePlacementPool;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.*;

import java.util.List;

@Mixin(PlacedFeature.class)
public class FastPlacedFeatureV2Mixin {

    @Shadow
    @Final
    private List<PlacementModifier> placement;
    @Shadow
    @Final
    private Holder<ConfiguredFeature<?, ?>> feature;
    @Unique
    private static final ThreadLocal<PrimitivePlacementPool> LONG_BUFFERS =
            ThreadLocal.withInitial(PrimitivePlacementPool::new);


    /**
     * @author Sixik
     * @reason Eliminating Stream.flatMap overhead while preserving vanilla Depth-First ordering
     */
    @Overwrite
    public final boolean placeWithContext(
            PlacementContext context, RandomSource random, BlockPos startPos
    ) {
        PrimitivePlacementPool pool = LONG_BUFFERS.get();

        LongArrayList current = pool.acquire();
        LongArrayList next = pool.acquire();

        try {
            current.add(startPos.asLong());

            /*
                Пропускаем координаты через все модификаторы по цепочке
             */
            for (int modIndex = 0; modIndex < this.placement.size(); modIndex++) {
                GA$PlacementModifierExtension modifier = GA$PlacementModifierExtension.get(this.placement.get(modIndex));
                next.clear();

                if (current.isEmpty()) return false; // Если всё отсеялось - выходим

                for (int i = 0; i < current.size(); i++) {
                    long packedPos = current.getLong(i);
                    modifier.generatePositionsFast(context, random, packedPos, next);
                }

                /*
                    Свапаем буферы
                 */
                LongArrayList temp = current;
                current = next;
                next = temp;
            }

            /*
                Финальная расстановка фичи
             */
            ConfiguredFeature<?, ?> feature = this.feature.value();
            MutableBoolean success = new MutableBoolean();
            BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();

            for (int i = 0; i < current.size(); i++) {
                mPos.set(current.getLong(i)); // Распаковываем long прямо в mPos

                if (feature.place(context.getLevel(), context.generator(), random, mPos)) {
                    success.setTrue();
                }
            }

            return success.isTrue();

        } finally {
            pool.release();
            pool.release();
        }
    }
}
