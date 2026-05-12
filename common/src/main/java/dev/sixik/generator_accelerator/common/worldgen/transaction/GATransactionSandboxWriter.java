package dev.sixik.generator_accelerator.common.worldgen.transaction;

import java.util.Objects;

public final class GATransactionSandboxWriter {
    private final GATransactionJournal journal;

    public GATransactionSandboxWriter() {
        this(new GATransactionJournal());
    }

    public GATransactionSandboxWriter(GATransactionJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    public GATransactionJournal journal() {
        return journal;
    }

    public boolean setBlock(int x, int y, int z, Object state, int flags) {
        if (!journal.open()) {
            return false;
        }
        journal.appendBlockWrite(x, y, z, state, flags);
        return true;
    }

    public boolean markPostprocess(int x, int y, int z) {
        if (!journal.open()) {
            return false;
        }
        journal.appendPostprocessMark(x, y, z);
        return true;
    }

    public boolean scheduleFluidTick(int x, int y, int z, Object fluid, int delay, int priority) {
        if (!journal.open()) {
            return false;
        }
        journal.appendFluidTick(x, y, z, fluid, delay, priority);
        return true;
    }

    public boolean scheduleBlockTick(int x, int y, int z, Object block, int delay, int priority) {
        if (!journal.open()) {
            return false;
        }
        journal.appendBlockTick(x, y, z, block, delay, priority);
        return true;
    }

    public boolean unsupportedRead(String reason) {
        if (!journal.open()) {
            return false;
        }
        journal.downgrade("unsupported read: " + normalizeReason(reason));
        return true;
    }

    public boolean unsupportedWrite(String reason) {
        if (!journal.open()) {
            return false;
        }
        journal.abort("unsupported write: " + normalizeReason(reason));
        return true;
    }

    public boolean fail(Throwable throwable) {
        if (!journal.open()) {
            return false;
        }
        journal.abort("failure: " + failureReason(throwable));
        return true;
    }

    public boolean seal() {
        if (!journal.open()) {
            return false;
        }
        journal.seal();
        return true;
    }

    public GATransactionSnapshot snapshot() {
        return journal.snapshot();
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        return reason.trim();
    }

    private static String failureReason(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getName();
        }
        return throwable.getClass().getName() + ": " + message;
    }
}
