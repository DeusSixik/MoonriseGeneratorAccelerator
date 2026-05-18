package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DfcOpenClDeviceEnumerator {
    private DfcOpenClDeviceEnumerator() {
    }

    record Candidate(long platform, long device, DfcOpenClDeviceInfo info) {
    }

    static List<DfcOpenClDeviceInfo> enumerate() {
        return enumerateCandidates().stream()
                .map(Candidate::info)
                .toList();
    }

    static List<Candidate> enumerateCandidates() {
        long deviceMask = deviceTypeMask();
        if (deviceMask == 0L) {
            return List.of();
        }

        ensureOpenClCreated();
        List<Candidate> devices = new ArrayList<>();
        String filter = DfcOpenClConfig.deviceFilter();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer platformCount = stack.mallocInt(1);
            check(CL10.clGetPlatformIDs((PointerBuffer) null, platformCount), "clGetPlatformIDs(count)");
            int platformTotal = platformCount.get(0);
            if (platformTotal <= 0) {
                return List.of();
            }

            PointerBuffer platforms = stack.mallocPointer(platformTotal);
            check(CL10.clGetPlatformIDs(platforms, (IntBuffer) null), "clGetPlatformIDs(list)");

            for (int platformIndex = 0; platformIndex < platformTotal; platformIndex++) {
                long platform = platforms.get(platformIndex);
                enumeratePlatform(devices, platformIndex, platform, deviceMask, filter);
            }
        }

        return List.copyOf(devices);
    }

    private static void ensureOpenClCreated() {
        try {
            CL.create();
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("already been created")) {
                throw e;
            }
        }
    }

    private static void enumeratePlatform(List<Candidate> out, int platformIndex,
                                          long platform, long deviceMask, String filter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer deviceCount = stack.mallocInt(1);
            int countError = CL10.clGetDeviceIDs(platform, deviceMask, null, deviceCount);
            if (countError == CL10.CL_DEVICE_NOT_FOUND) {
                return;
            }
            check(countError, "clGetDeviceIDs(count)");

            int deviceTotal = deviceCount.get(0);
            if (deviceTotal <= 0) {
                return;
            }

            PointerBuffer devices = stack.mallocPointer(deviceTotal);
            check(CL10.clGetDeviceIDs(platform, deviceMask, devices, (IntBuffer) null), "clGetDeviceIDs(list)");

            String platformName = getPlatformString(platform, CL10.CL_PLATFORM_NAME);
            String platformVendor = getPlatformString(platform, CL10.CL_PLATFORM_VENDOR);
            String platformVersion = getPlatformString(platform, CL10.CL_PLATFORM_VERSION);

            for (int deviceIndex = 0; deviceIndex < deviceTotal; deviceIndex++) {
                long device = devices.get(deviceIndex);
                DfcOpenClDeviceInfo info = readDeviceInfo(
                        platformIndex,
                        deviceIndex,
                        platform,
                        device,
                        platformName,
                        platformVendor,
                        platformVersion);
                if (!info.matchesFilter(filter)) {
                    continue;
                }
                if (!info.openCl12Supported()) {
                    continue;
                }
                if (DfcOpenClConfig.requireFp64() && !info.fp64Supported()) {
                    continue;
                }
                out.add(new Candidate(platform, device, info));
            }
        }
    }

    private static DfcOpenClDeviceInfo readDeviceInfo(int platformIndex, int deviceIndex,
                                                      long platform, long device,
                                                      String platformName, String platformVendor,
                                                      String platformVersion) {
        String deviceName = getDeviceString(device, CL10.CL_DEVICE_NAME);
        String deviceVendor = getDeviceString(device, CL10.CL_DEVICE_VENDOR);
        String deviceVersion = getDeviceString(device, CL10.CL_DEVICE_VERSION);
        String driverVersion = getDeviceString(device, CL10.CL_DRIVER_VERSION);
        String extensions = getDeviceString(device, CL10.CL_DEVICE_EXTENSIONS).toLowerCase(Locale.ROOT);
        long deviceType = getDeviceLong(device, CL10.CL_DEVICE_TYPE);
        long globalMem = getDeviceLong(device, CL10.CL_DEVICE_GLOBAL_MEM_SIZE);
        int computeUnits = getDeviceInt(device, CL10.CL_DEVICE_MAX_COMPUTE_UNITS);
        boolean fp64 = containsExtension(extensions, "cl_khr_fp64") || containsExtension(extensions, "cl_amd_fp64");
        boolean openCl12 = isAtLeastOpenCl12(deviceVersion);

        return new DfcOpenClDeviceInfo(
                platformIndex,
                deviceIndex,
                platformName,
                platformVendor,
                platformVersion,
                deviceName,
                deviceVendor,
                deviceVersion,
                driverVersion,
                deviceType,
                globalMem,
                computeUnits,
                openCl12,
                fp64);
    }

    private static long deviceTypeMask() {
        long mask = 0L;
        if (DfcOpenClConfig.allowCpuDevices()) {
            mask |= CL10.CL_DEVICE_TYPE_CPU;
        }
        if (DfcOpenClConfig.allowGpuDevices()) {
            mask |= CL10.CL_DEVICE_TYPE_GPU;
        }
        if (DfcOpenClConfig.allowAcceleratorDevices()) {
            mask |= CL10.CL_DEVICE_TYPE_ACCELERATOR;
        }
        return mask;
    }

    private static String getPlatformString(long platform, int paramName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            check(CL10.clGetPlatformInfo(platform, paramName, (ByteBuffer) null, size), "clGetPlatformInfo(size)");
            ByteBuffer data = stack.malloc(Math.toIntExact(size.get(0)));
            check(CL10.clGetPlatformInfo(platform, paramName, data, null), "clGetPlatformInfo(data)");
            return decodeNullTerminatedUtf8(data);
        }
    }

    private static String getDeviceString(long device, int paramName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            check(CL10.clGetDeviceInfo(device, paramName, (ByteBuffer) null, size), "clGetDeviceInfo(size)");
            ByteBuffer data = stack.malloc(Math.toIntExact(size.get(0)));
            check(CL10.clGetDeviceInfo(device, paramName, data, null), "clGetDeviceInfo(data)");
            return decodeNullTerminatedUtf8(data);
        }
    }

    private static int getDeviceInt(long device, int paramName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.mallocInt(1);
            check(CL10.clGetDeviceInfo(device, paramName, value, null), "clGetDeviceInfo(int)");
            return value.get(0);
        }
    }

    private static long getDeviceLong(long device, int paramName) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer value = stack.mallocLong(1);
            check(CL10.clGetDeviceInfo(device, paramName, value, null), "clGetDeviceInfo(long)");
            return value.get(0);
        }
    }

    private static String decodeNullTerminatedUtf8(ByteBuffer data) {
        int length = data.remaining();
        while (length > 0 && data.get(length - 1) == 0) {
            length--;
        }
        byte[] bytes = new byte[length];
        data.position(0);
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean containsExtension(String extensions, String extension) {
        if (extensions == null || extensions.isBlank()) {
            return false;
        }
        String needle = extension.toLowerCase(Locale.ROOT);
        for (String token : extensions.split("\\s+")) {
            if (needle.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAtLeastOpenCl12(String version) {
        if (version == null) {
            return false;
        }
        String lower = version.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("opencl ");
        if (marker < 0) {
            return false;
        }
        String[] parts = lower.substring(marker + "opencl ".length()).split("[^0-9]+", 3);
        if (parts.length < 2) {
            return false;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major > 1 || (major == 1 && minor >= 2);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void check(int error, String op) {
        if (error != CL10.CL_SUCCESS) {
            throw new IllegalStateException(op + " failed with OpenCL error " + error);
        }
    }
}
