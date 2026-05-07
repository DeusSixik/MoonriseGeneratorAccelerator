package dev.sixik.generator_accelerator.common.features;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SculkSpreader;

public final class SculkSpreaderCursorScratch {
    public final Object2ObjectOpenHashMap<BlockPos, SculkSpreader.ChargeCursor> cursorByPos = new Object2ObjectOpenHashMap<>();
    public final Object2IntOpenHashMap<BlockPos> chargeByPos = new Object2IntOpenHashMap<>();
}
