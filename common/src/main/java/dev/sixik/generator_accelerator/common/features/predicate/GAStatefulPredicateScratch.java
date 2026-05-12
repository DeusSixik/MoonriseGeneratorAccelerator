package dev.sixik.generator_accelerator.common.features.predicate;

import net.minecraft.core.BlockPos;

public final class GAStatefulPredicateScratch {
    public static final ThreadLocal<BlockPos.MutableBlockPos> POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private GAStatefulPredicateScratch() {
    }
}
