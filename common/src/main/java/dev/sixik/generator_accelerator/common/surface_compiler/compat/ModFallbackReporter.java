package dev.sixik.generator_accelerator.common.surface_compiler.compat;

import dev.sixik.generator_accelerator.common.surface_compiler.telemetry.FallbackReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModFallbackReporter {
    private final List<String> events = new ArrayList<>();

    public void report(String fingerprint, FallbackReason reason) {
        this.events.add(fingerprint + ":" + reason);
    }

    public void clear() {
        this.events.clear();
    }

    public List<String> events() {
        return Collections.unmodifiableList(this.events);
    }
}
