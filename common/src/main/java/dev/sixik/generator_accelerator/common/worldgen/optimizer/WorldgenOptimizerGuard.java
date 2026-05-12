package dev.sixik.generator_accelerator.common.worldgen.optimizer;

import java.util.Objects;

public record WorldgenOptimizerGuard(
        String name,
        String expectedValue,
        boolean required
) {
    public WorldgenOptimizerGuard {
        name = name == null ? "" : name;
        expectedValue = expectedValue == null ? "" : expectedValue;
    }

    public boolean matches(String actualValue) {
        if (!required && expectedValue.isBlank()) {
            return true;
        }
        return Objects.equals(expectedValue, actualValue == null ? "" : actualValue);
    }
}
