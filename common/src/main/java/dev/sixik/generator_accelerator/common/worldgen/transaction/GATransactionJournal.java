package dev.sixik.generator_accelerator.common.worldgen.transaction;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.List;

public final class GATransactionJournal {
    private final List<GAJournalEntry> entries;
    private GATransactionState state;
    private String reason;
    private long nextSequence;

    public GATransactionJournal() {
        this(64);
    }

    public GATransactionJournal(int initialCapacity) {
        this.entries = new ObjectArrayList<>(Math.max(0, initialCapacity));
        this.state = GATransactionState.OPEN;
    }

    public GATransactionState state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    public int size() {
        return entries.size();
    }

    public boolean open() {
        return state == GATransactionState.OPEN;
    }

    public GABlockMutation appendBlockWrite(int x, int y, int z, Object state, int flags) {
        requireOpen();
        GABlockMutation mutation = new GABlockMutation(x, y, z, state, flags, nextSequence++);
        entries.add(new GAJournalEntry.BlockWrite(mutation));
        return mutation;
    }

    public GAJournalEntry.PostprocessMark appendPostprocessMark(int x, int y, int z) {
        requireOpen();
        GAJournalEntry.PostprocessMark mark = new GAJournalEntry.PostprocessMark(x, y, z, nextSequence++);
        entries.add(mark);
        return mark;
    }

    public GAJournalEntry.FluidTick appendFluidTick(int x, int y, int z, Object fluid, int delay, int priority) {
        requireOpen();
        GAJournalEntry.FluidTick tick = new GAJournalEntry.FluidTick(x, y, z, fluid, delay, priority, nextSequence++);
        entries.add(tick);
        return tick;
    }

    public GAJournalEntry.BlockTick appendBlockTick(int x, int y, int z, Object block, int delay, int priority) {
        requireOpen();
        GAJournalEntry.BlockTick tick = new GAJournalEntry.BlockTick(x, y, z, block, delay, priority, nextSequence++);
        entries.add(tick);
        return tick;
    }

    public void abort(String reason) {
        requireOpen();
        this.state = GATransactionState.ABORTED;
        this.reason = requireReason(reason);
    }

    public void downgrade(String reason) {
        requireOpen();
        this.state = GATransactionState.DOWNGRADED;
        this.reason = requireReason(reason);
    }

    public void seal() {
        requireOpen();
        this.state = GATransactionState.SEALED;
    }

    public GATransactionSnapshot snapshot() {
        return new GATransactionSnapshot(state, reason, entries, nextSequence);
    }

    public void clear() {
        entries.clear();
        state = GATransactionState.OPEN;
        reason = null;
        nextSequence = 0L;
    }

    private void requireOpen() {
        if (state != GATransactionState.OPEN) {
            throw new IllegalStateException("transaction is " + state);
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return reason;
    }
}
