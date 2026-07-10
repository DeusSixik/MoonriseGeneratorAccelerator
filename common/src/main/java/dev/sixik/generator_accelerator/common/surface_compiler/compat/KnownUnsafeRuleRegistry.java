package dev.sixik.generator_accelerator.common.surface_compiler.compat;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

public final class KnownUnsafeRuleRegistry {
    private final Set<String> classNames = ConcurrentHashMap.newKeySet();

    public void add(String className) {
        this.classNames.add(className);
    }

    public boolean contains(String className) {
        return this.classNames.contains(className);
    }

    public void clear() {
        this.classNames.clear();
    }

    public String versionHash() {
        return this.classNames.stream().sorted().collect(Collectors.joining(";"));
    }
}
