package dev.sixik.generator_accelerator.common.density.compiler.opencl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class DfcOpenClSources {
    private DfcOpenClSources() {
    }

    static String smokeProbeSource() {
        return runtimeSource()
                + "\n" + load("dfc_opencl/dfc_probe.cl");
    }

    static String runtimeSource() {
        return load("dfc_opencl/dfc_math.cl")
                + "\n" + load("dfc_opencl/dfc_slab_vm.cl");
    }

    private static String load(String path) {
        ClassLoader loader = DfcOpenClSources.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing OpenCL resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read OpenCL resource: " + path, e);
        }
    }
}
