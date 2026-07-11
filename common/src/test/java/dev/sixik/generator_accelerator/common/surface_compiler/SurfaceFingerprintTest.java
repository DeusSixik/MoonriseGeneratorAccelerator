package dev.sixik.generator_accelerator.common.surface_compiler;

import dev.sixik.generator_accelerator.common.surface_compiler.frontend.SurfaceFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SurfaceFingerprintTest {
    @Test
    void cyclicObjectGraphTerminatesAndIsStable() {
        Object[] root = new Object[1];
        root[0] = root;

        String first = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> SurfaceFingerprint.structuralHash(root));
        String second = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> SurfaceFingerprint.structuralHash(root));

        assertEquals(first, second);
    }

    @Test
    void arbitraryZeroArgumentMethodsAreNeverInvoked() {
        MethodTrap trap = new MethodTrap("safe");

        assertDoesNotThrow(() -> SurfaceFingerprint.structuralHash(trap));
        assertEquals(0, trap.invocations);
    }

    @Test
    void readableStateStillDifferentiatesFingerprints() {
        assertNotEquals(
                SurfaceFingerprint.structuralHash(new FingerprintValue("first")),
                SurfaceFingerprint.structuralHash(new FingerprintValue("second"))
        );
    }

    private record FingerprintValue(String value) {
    }

    private static final class MethodTrap {
        private final String value;
        private int invocations;

        private MethodTrap(String value) {
            this.value = value;
        }

        public Object expandGraph() {
            this.invocations++;
            throw new AssertionError("fingerprinting must not execute arbitrary methods");
        }
    }
}
