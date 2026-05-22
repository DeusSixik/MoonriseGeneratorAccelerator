package dev.sixik.generator_accelerator.common.beardifier;

import net.minecraft.world.level.levelgen.NoiseChunk;

public final class GABeardifierThreadCache {
    public final GABeardifierCellScratch scratch = new GABeardifierCellScratch();
    public Object owner;
    public NoiseChunk chunk;
    public int startX;
    public int startY;
    public int startZ;
    public int cellWidth;
    public int cellHeight;
}
