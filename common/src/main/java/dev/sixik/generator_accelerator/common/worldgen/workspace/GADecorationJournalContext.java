package dev.sixik.generator_accelerator.common.worldgen.workspace;

public final class GADecorationJournalContext {
    private static final ThreadLocal<GADecorationWriteJournal> CURRENT = new ThreadLocal<>();

    private GADecorationJournalContext() {
    }

    public static GADecorationWriteJournal current() {
        return CURRENT.get();
    }

    public static Scope bind(GADecorationWriteJournal journal) {
        GADecorationWriteJournal previous = CURRENT.get();
        CURRENT.set(journal);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final GADecorationWriteJournal previous;
        private boolean closed;

        private Scope(GADecorationWriteJournal previous) {
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
