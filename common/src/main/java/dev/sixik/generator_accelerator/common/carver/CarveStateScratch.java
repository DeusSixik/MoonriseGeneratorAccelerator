package dev.sixik.generator_accelerator.common.carver;

public final class CarveStateScratch {
    private boolean active;
    private boolean debug;
    private boolean restoreSurface;
    private int lavaLevel;
    private boolean[] replaceableStateIds;

    public void set(int lavaLevel, boolean debug, boolean restoreSurface, boolean[] replaceableStateIds) {
        this.active = true;
        this.debug = debug;
        this.restoreSurface = restoreSurface;
        this.lavaLevel = lavaLevel;
        this.replaceableStateIds = replaceableStateIds;
    }

    public void clear() {
        this.active = false;
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
}
