package dev.sixik.generator_accelerator.common.surface_compiler.callout;

import dev.sixik.generator_accelerator.common.surface_compiler.compat.AdapterSafetyClass;

public record SurfacePredicateDescriptor(String id, AdapterSafetyClass safetyClass, boolean primitiveOnly, boolean ordered) {
}
