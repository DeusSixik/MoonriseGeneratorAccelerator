package dev.sixik.generator_accelerator.common.worldgen.commit;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable final replay view for accepted commands after collision resolution.
 */
public record GACommitFinalizePlan<T>(
        GACommitPlan<T> plan,
        List<GACommitCommand<T>> replayCommands,
        GACommitBatchStats stats
) {
    public GACommitFinalizePlan {
        if (plan == null) {
            throw new NullPointerException("plan");
        }
        replayCommands = replayCommands == null ? List.of() : List.copyOf(replayCommands);
        if (stats == null) {
            throw new NullPointerException("stats");
        }
    }

    public static <T> GACommitFinalizePlan<T> of(GACommitPlan<T> plan) {
        if (plan == null) {
            throw new NullPointerException("plan");
        }
        List<GACommitCommand<T>> replayCommands = new ObjectArrayList<>(plan.resolved().accepted());
        replayCommands.sort(Comparator.comparing(GACommitCommand<T>::orderKey));
        return new GACommitFinalizePlan<>(plan, replayCommands, plan.resolved().stats());
    }
}
