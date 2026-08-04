package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import java.util.Locale;

public enum GATaskClass {
    CPU_NOISE("cpu_noise", true, true, "NOISE"),
    CPU_WORKSPACE("cpu_workspace", true, true, "WORKSPACE"),
    WRITE_GUARDED("write_guarded", false, false, "TRANSACTIONAL"),
    COMMIT_BOUNDARY("commit_boundary", false, false, "COMMIT"),
    SERIAL_LEGACY("serial_legacy", false, false, "SERIAL"),
    BG_COMPILE("bg_compile", false, true, "COMPILE"),
    BOUNDARY("boundary", true, true, "WORKSPACE");

    private final String jsonName;
    private final boolean foreground;
    private final boolean stealable;
    private final String legacyLaneName;

    GATaskClass(String jsonName, boolean foreground, boolean stealable, String legacyLaneName) {
        this.jsonName = jsonName;
        this.foreground = foreground;
        this.stealable = stealable;
        this.legacyLaneName = legacyLaneName;
    }

    public String jsonName() {
        return jsonName;
    }

    public boolean foreground() {
        return foreground;
    }

    public boolean stealable() {
        return stealable;
    }

    public String legacyLaneName() {
        return legacyLaneName;
    }

    public String legacyJsonName() {
        return legacyLaneName.toLowerCase(Locale.ROOT);
    }
}
