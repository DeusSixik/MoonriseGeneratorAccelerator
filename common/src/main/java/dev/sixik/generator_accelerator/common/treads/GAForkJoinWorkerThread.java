package dev.sixik.generator_accelerator.common.treads;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

public class GAForkJoinWorkerThread extends ForkJoinWorkerThread implements GAFastLocalHolder {

    public final Object[] fastLocals = new Object[GAThread.FAST_THREAD_LOCAL_SIZE];

    protected GAForkJoinWorkerThread(ForkJoinPool pool) {
        super(pool);
    }

    @Override
    public Object[] gaFastLocals() {
        return fastLocals;
    }

}
