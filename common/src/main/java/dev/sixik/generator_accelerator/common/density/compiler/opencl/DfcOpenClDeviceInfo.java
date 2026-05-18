package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import java.util.Locale;

public record DfcOpenClDeviceInfo(
        int platformIndex,
        int deviceIndex,
        String platformName,
        String platformVendor,
        String platformVersion,
        String deviceName,
        String deviceVendor,
        String deviceVersion,
        String driverVersion,
        long deviceType,
        long globalMemBytes,
        int computeUnits,
        boolean openCl12Supported,
        boolean fp64Supported) {

    private static final long TYPE_DEFAULT = 1L;
    private static final long TYPE_CPU = 1L << 1;
    private static final long TYPE_GPU = 1L << 2;
    private static final long TYPE_ACCELERATOR = 1L << 3;
    private static final long TYPE_CUSTOM = 1L << 4;

    public boolean isCpu() {
        return (this.deviceType & TYPE_CPU) != 0L;
    }

    public boolean isGpu() {
        return (this.deviceType & TYPE_GPU) != 0L;
    }

    public boolean isAccelerator() {
        return (this.deviceType & TYPE_ACCELERATOR) != 0L;
    }

    public boolean matchesFilter(String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) {
            return true;
        }
        String filter = rawFilter.toLowerCase(Locale.ROOT);
        return safe(this.deviceName).toLowerCase(Locale.ROOT).contains(filter)
                || safe(this.deviceVendor).toLowerCase(Locale.ROOT).contains(filter)
                || safe(this.platformName).toLowerCase(Locale.ROOT).contains(filter)
                || safe(this.driverVersion).toLowerCase(Locale.ROOT).contains(filter)
                || shortDescription().toLowerCase(Locale.ROOT).contains(filter);
    }

    public String shortDescription() {
        return typeName()
                + " " + safe(this.deviceVendor)
                + " " + safe(this.deviceName)
                + " (platform=" + safe(this.platformName)
                + ", driver=" + safe(this.driverVersion)
                + ", cu=" + this.computeUnits
                + ", mem=" + formatBytes(this.globalMemBytes)
                + ", cl12=" + this.openCl12Supported
                + ", fp64=" + this.fp64Supported
                + ")";
    }

    public String typeName() {
        if ((this.deviceType & TYPE_GPU) != 0L) {
            return "GPU";
        }
        if ((this.deviceType & TYPE_CPU) != 0L) {
            return "CPU";
        }
        if ((this.deviceType & TYPE_ACCELERATOR) != 0L) {
            return "ACCELERATOR";
        }
        if ((this.deviceType & TYPE_CUSTOM) != 0L) {
            return "CUSTOM";
        }
        if ((this.deviceType & TYPE_DEFAULT) != 0L) {
            return "DEFAULT";
        }
        return "UNKNOWN";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0L) {
            return "unknown";
        }
        double gib = bytes / 1073741824.0D;
        if (gib >= 1.0D) {
            return String.format(Locale.ROOT, "%.1fGiB", gib);
        }
        double mib = bytes / 1048576.0D;
        return String.format(Locale.ROOT, "%.0fMiB", mib);
    }
}
