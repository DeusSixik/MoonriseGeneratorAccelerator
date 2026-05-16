package dev.sixik.generator_accelerator.common.features;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.MultifaceSpreader;

public final class GAMultifaceSpreadScratch {
    private final BlockPos.MutableBlockPos[] positions = new BlockPos.MutableBlockPos[Direction.values().length];
    private final MultifaceSpreader.SpreadPos[] spreadPositions = new MultifaceSpreader.SpreadPos[Direction.values().length];

    public GAMultifaceSpreadScratch() {
        Direction[] directions = Direction.values();
        for (int i = 0; i < directions.length; i++) {
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            this.positions[i] = position;
            this.spreadPositions[i] = new MultifaceSpreader.SpreadPos(position, directions[i]);
        }
    }

    public MultifaceSpreader.SpreadPos set(BlockPos origin, Direction face, int offsetX, int offsetY, int offsetZ) {
        int index = face.ordinal();
        this.positions[index].set(origin.getX() + offsetX, origin.getY() + offsetY, origin.getZ() + offsetZ);
        return this.spreadPositions[index];
    }
}
