package dev.sixik.generator_accelerator.api.patches;

import net.minecraft.core.BlockPos;
import net.sixik.javastructg.structs.arrays.NativeObjectArray;

import java.util.List;

public interface GA$FixedPlacementAccess {
    List<BlockPos> ga$fixedPositions();

    NativeObjectArray<BlockPos> ga$nativePositions();
}
