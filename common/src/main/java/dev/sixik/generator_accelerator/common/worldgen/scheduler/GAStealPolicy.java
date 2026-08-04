package dev.sixik.generator_accelerator.common.worldgen.scheduler;

public final class GAStealPolicy {
    private final int maxVictimScans;

    public GAStealPolicy(int workerCount) {
        this.maxVictimScans = Math.max(1, Math.min(workerCount - 1, 8));
    }

    public long trySteal(GAWorker worker) {
        GASchedulerRuntime runtime = worker.runtime();
        int count = runtime.workerCount();
        if (count <= 1) {
            return GAWorkHandle.NULL_HANDLE;
        }
        int start = Math.floorMod(worker.index() + worker.stealCursorIncrement(), count);
        for (int scanned = 0; scanned < maxVictimScans; scanned++) {
            int victimIndex = (start + scanned) % count;
            if (victimIndex == worker.index()) {
                continue;
            }
            long stolen = runtime.worker(victimIndex).stealLocal();
            if (stolen != GAWorkHandle.NULL_HANDLE) {
                runtime.metrics().recordSteal(true);
                return stolen;
            }
        }
        runtime.metrics().recordSteal(false);
        return GAWorkHandle.NULL_HANDLE;
    }
}
