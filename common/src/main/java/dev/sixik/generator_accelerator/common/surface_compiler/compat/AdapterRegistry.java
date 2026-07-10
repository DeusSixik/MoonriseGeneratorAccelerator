package dev.sixik.generator_accelerator.common.surface_compiler.compat;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class AdapterRegistry {
    private final Map<String, SurfaceAdapter> adapters = new LinkedHashMap<>();

    public void register(SurfaceAdapter adapter) {
        this.adapters.put(adapter.descriptor().id(), adapter);
        this.adapters.put(adapter.descriptor().ownerClass(), adapter);
    }

    public Optional<SurfaceAdapter> find(String id) {
        return Optional.ofNullable(this.adapters.get(id));
    }

    public Collection<SurfaceAdapter> adapters() {
        return this.adapters.values().stream().distinct().toList();
    }

    public void clear() {
        this.adapters.clear();
    }

    public String versionHash() {
        StringBuilder out = new StringBuilder();
        this.adapters.values().stream()
                .map(SurfaceAdapter::descriptor)
                .distinct()
                .sorted(Comparator.comparing(AdapterDescriptor::id))
                .forEach(descriptor -> out.append(descriptor.id()).append(':')
                        .append(descriptor.version()).append(':')
                        .append(descriptor.safetyClass()).append(':')
                        .append(descriptor.primitiveAbi()).append(':')
                        .append(descriptor.vectorAbi()).append(':')
                        .append(descriptor.vectorWidth()).append(':')
                        .append(descriptor.certificationId()).append(';'));
        return out.toString();
    }
}
