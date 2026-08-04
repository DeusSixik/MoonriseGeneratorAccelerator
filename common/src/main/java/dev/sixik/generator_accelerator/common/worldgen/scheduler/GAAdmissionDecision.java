package dev.sixik.generator_accelerator.common.worldgen.scheduler;

import java.util.Locale;

public record GAAdmissionDecision(GAAdmissionDecision.Kind kind, GAMetrics.FallbackReason reason, GATaskClass taskClass, String detail) {
    public enum Kind {
        ADMIT_FULL,
        ADMIT_WORKSPACE,
        ADMIT_BOUNDARY,
        ADMIT_LEGACY,
        REJECT_V2
    }

    public boolean admitted() {
        return kind == Kind.ADMIT_FULL || kind == Kind.ADMIT_WORKSPACE || kind == Kind.ADMIT_BOUNDARY;
    }

    public static GAAdmissionDecision full(GATaskClass taskClass, String detail) {
        return new GAAdmissionDecision(Kind.ADMIT_FULL, null, taskClass, detail);
    }

    public static GAAdmissionDecision workspace(String detail) {
        return new GAAdmissionDecision(Kind.ADMIT_WORKSPACE, null, GATaskClass.CPU_WORKSPACE, detail);
    }

    public static GAAdmissionDecision boundary(String detail) {
        return new GAAdmissionDecision(Kind.ADMIT_BOUNDARY, null, GATaskClass.BOUNDARY, detail);
    }

    public static GAAdmissionDecision legacy(GAMetrics.FallbackReason reason, String detail) {
        return new GAAdmissionDecision(Kind.ADMIT_LEGACY, reason, GATaskClass.SERIAL_LEGACY, detail);
    }

    public static GAAdmissionDecision reject(GAMetrics.FallbackReason reason, String detail) {
        return new GAAdmissionDecision(Kind.REJECT_V2, reason, GATaskClass.SERIAL_LEGACY, detail);
    }

    public String jsonName() {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
