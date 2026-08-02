package dev.sixik.generator_accelerator.common.density.compiler.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DfcRuntimeTelemetryTest {

    @AfterEach
    void resetTelemetry() {
        DfcRuntimeTelemetry.setEnabled(false);
        DfcRuntimeTelemetry.reset();
    }

    @Test
    void disabledTelemetryLeavesCountersUntouched() {
        DfcRuntimeTelemetry.setEnabled(false);

        assertThrows(NullPointerException.class, () -> DfcRuntimeTelemetry.computeExtern(null, null));

        DfcRuntimeTelemetry.Stats stats = DfcRuntimeTelemetry.snapshot();
        assertFalse(stats.enabled());
        assertEquals(0L, stats.externInvokeCalls());
        assertTrue(stats.topExternClasses().isEmpty());
    }

    @Test
    void enabledTelemetryCountsExternsAndCanReset() {
        DfcRuntimeTelemetry.setEnabled(true);

        assertThrows(NullPointerException.class, () -> DfcRuntimeTelemetry.computeExtern(null, null));

        DfcRuntimeTelemetry.Stats stats = DfcRuntimeTelemetry.snapshot();
        assertTrue(stats.enabled());
        assertEquals(1L, stats.externInvokeCalls());

        DfcRuntimeTelemetry.reset();
        assertEquals(0L, DfcRuntimeTelemetry.snapshot().externInvokeCalls());
    }
}
