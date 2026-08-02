package dev.sixik.generator_accelerator.common.worldgen.workspace;

public final class GAChunkWorkspaceContext {
    private static final ThreadLocal<GAChunkWorkspace> CURRENT = new ThreadLocal<>();

    private GAChunkWorkspaceContext() {
    }

    public static GAChunkWorkspace current() {
        return CURRENT.get();
    }

    public static void clearCurrent() {
        CURRENT.remove();
    }

    public static Scope bind(GAChunkWorkspace workspace) {
        GAChunkWorkspace previous = CURRENT.get();
        if (workspace == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(workspace);
        }
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final GAChunkWorkspace previous;
        private boolean closed;

        private Scope(GAChunkWorkspace previous) {
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
