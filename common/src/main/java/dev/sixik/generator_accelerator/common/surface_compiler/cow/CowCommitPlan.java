package dev.sixik.generator_accelerator.common.surface_compiler.cow;

import java.util.List;

public record CowCommitPlan(List<CowSectionWriter> dirtySections) {
    public CowCommitPlan {
        dirtySections = List.copyOf(dirtySections);
    }

    public boolean empty() {
        return this.dirtySections.isEmpty();
    }
}
