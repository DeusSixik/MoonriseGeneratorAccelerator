package dev.sixik.generator_accelerator.common.surface_compiler.compat;

import java.util.Objects;

public record AdapterDescriptor(
        String id,
        String ownerClass,
        AdapterSafetyClass safetyClass,
        String version,
        boolean primitiveAbi,
        boolean vectorAbi,
        int vectorWidth,
        String certificationId
) {
    public AdapterDescriptor(String id, String ownerClass, AdapterSafetyClass safetyClass, String version, boolean primitiveAbi) {
        this(id, ownerClass, safetyClass, version, primitiveAbi, false, 0, "");
    }

}
