package dev.sixik.generator_accelerator.common.features.pipeline;

final class DecorationReadSnapshotContext {
    private static final ThreadLocal<DecorationReadSnapshot> CURRENT = new ThreadLocal<>();

    private DecorationReadSnapshotContext() {
    }

    static DecorationReadSnapshot current() {
        return CURRENT.get();
    }

    static Scope bind(DecorationReadSnapshot snapshot) {
        DecorationReadSnapshot previous = CURRENT.get();
        if (snapshot == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(snapshot);
        }
        return new Scope(previous);
    }

    static final class Scope implements AutoCloseable {
        private final DecorationReadSnapshot previous;
        private boolean closed;

        private Scope(DecorationReadSnapshot previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
