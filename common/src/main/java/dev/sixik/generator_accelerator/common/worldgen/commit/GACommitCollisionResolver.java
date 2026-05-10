package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic reducer for block-position collisions.
 */
public final class GACommitCollisionResolver {
    private static final Comparator<GACommitCommand<?>> POSITION_THEN_ORDER = Comparator
            .comparing(GACommitCommand<?>::position)
            .thenComparing(GACommitCommand<?>::orderKey);

    private GACommitCollisionResolver() {
    }

    public static <T> GACommitCollisionResult<T> resolve(
            Collection<GACommitCommand<T>> commands,
            GACommitCollisionPolicy policy
    ) {
        if (commands == null) {
            throw new NullPointerException("commands");
        }
        if (policy == null) {
            throw new NullPointerException("policy");
        }

        List<GACommitCommand<T>> ordered = new ArrayList<>(commands);
        ordered.sort(POSITION_THEN_ORDER);

        List<GACommitCommand<T>> accepted = new ArrayList<>();
        List<GACommitCommand<T>> rejected = new ArrayList<>();

        int index = 0;
        while (index < ordered.size()) {
            int next = nextPositionGroup(ordered, index);
            resolveGroup(ordered, index, next, policy, accepted, rejected);
            index = next;
        }

        accepted.sort(Comparator.comparing(GACommitCommand<T>::orderKey));
        rejected.sort(POSITION_THEN_ORDER);
        return new GACommitCollisionResult<>(accepted, rejected);
    }

    private static <T> int nextPositionGroup(List<GACommitCommand<T>> ordered, int start) {
        GABlockPosition position = ordered.get(start).position();
        int index = start + 1;
        while (index < ordered.size() && ordered.get(index).position().equals(position)) {
            index++;
        }
        return index;
    }

    private static <T> void resolveGroup(
            List<GACommitCommand<T>> ordered,
            int start,
            int end,
            GACommitCollisionPolicy policy,
            List<GACommitCommand<T>> accepted,
            List<GACommitCommand<T>> rejected
    ) {
        rejectAmbiguousDuplicateKeys(ordered, start, end);
        if (end - start == 1) {
            accepted.add(ordered.get(start));
            return;
        }

        switch (policy) {
            case FIRST_WRITE_WINS -> {
                accepted.add(ordered.get(start));
                rejected.addAll(ordered.subList(start + 1, end));
            }
            case LATER_WRITE_WINS -> {
                accepted.add(ordered.get(end - 1));
                rejected.addAll(ordered.subList(start, end - 1));
            }
            case REJECT -> rejected.addAll(ordered.subList(start, end));
            default -> throw new IllegalStateException("unhandled collision policy: " + policy);
        }
    }

    private static <T> void rejectAmbiguousDuplicateKeys(List<GACommitCommand<T>> ordered, int start, int end) {
        for (int index = start + 1; index < end; index++) {
            GACommitCommand<T> previous = ordered.get(index - 1);
            GACommitCommand<T> current = ordered.get(index);
            if (previous.orderKey().equals(current.orderKey()) && !Objects.equals(previous.value(), current.value())) {
                throw new IllegalArgumentException("ambiguous duplicate commit key for position " + current.position());
            }
        }
    }
}
