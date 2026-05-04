package dev.sixik.generator_accelerator.api.patches;

import dev.sixik.generator_accelerator.api.exceptions.MethodNotImplementedException;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.apache.commons.lang3.NotImplementedException;

public interface GA$PlacementModifierExtension {

    static GA$PlacementModifierExtension get(PlacementModifier modifier) {
        return (GA$PlacementModifierExtension) modifier;
    }

    /**
     * DOD-версия генерации позиций.
     *
     * @param context   Контекст размещения
     * @param random    Источник рандома
     * @param packedPos Входящая позиция, запакованная в long
     * @param output    Буфер, куда будут записаны результаты
     */
    default void generatePositionsFast(PlacementContext context, RandomSource random, long packedPos, LongArrayList output) {
        throw new MethodNotImplementedException(getClass(), "generatePositionsFast(PlacementContext, RandomSource, long, LongArrayList output)");
    }
}
