package dev.sixik.generator_accelerator.common.carver;

import net.minecraft.core.BlockPos;
import org.apache.commons.lang3.mutable.MutableBoolean;

public final class CarveStateScratch {
    private boolean active;
    private boolean debug;
    private boolean restoreSurface;
    private int lavaLevel;
    private boolean[] replaceableStateIds;
    private final BlockPos.MutableBlockPos carvePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos();
    private final MutableBoolean surfaceHit = new MutableBoolean();

    public void set(int lavaLevel, boolean debug, boolean restoreSurface, boolean[] replaceableStateIds) {
        this.active = true;
        this.debug = debug;
        this.restoreSurface = restoreSurface;
        this.lavaLevel = lavaLevel;
        this.replaceableStateIds = replaceableStateIds;
    }

    public void clear() {
        this.active = false;
        this.debug = false;
        this.restoreSurface = false;
        this.lavaLevel = 0;
        this.replaceableStateIds = null;
        this.surfaceHit.setFalse();
    }

    public boolean isActive() {
        return this.active;
    }

    public boolean isDebug() {
        return this.debug;
    }

    public boolean shouldRestoreSurface() {
        return this.restoreSurface;
    }

    public int getLavaLevel() {
        return this.lavaLevel;
    }

    public boolean[] getReplaceableStateIds() {
        return this.replaceableStateIds;
    }

    public BlockPos.MutableBlockPos carvePos() {
        return this.carvePos;
    }

    public BlockPos.MutableBlockPos belowPos() {
        return this.belowPos;
    }

    public MutableBoolean surfaceHit() {
        return this.surfaceHit;
    }
}
