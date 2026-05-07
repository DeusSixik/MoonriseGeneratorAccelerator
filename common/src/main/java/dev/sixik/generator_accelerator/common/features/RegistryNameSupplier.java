package dev.sixik.generator_accelerator.common.features;

import net.minecraft.core.Registry;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class RegistryNameSupplier implements Supplier<String> {
    private Registry registry;
    private Object value;

    public RegistryNameSupplier set(Registry<?> registry, Object value) {
        this.registry = registry;
        this.value = value;
        return this;
    }

    public void clear() {
        this.registry = null;
        this.value = null;
    }

    @Override
    public String get() {
        if (this.registry == null || this.value == null) {
            return Objects.toString(this.value);
        }
        Optional key = this.registry.getResourceKey(this.value);
        if (key.isPresent()) {
            return key.get().toString();
        }
        return this.value.toString();
    }
}
