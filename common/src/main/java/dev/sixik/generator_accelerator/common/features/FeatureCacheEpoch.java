package dev.sixik.generator_accelerator.common.features;

import java.util.concurrent.atomic.AtomicInteger;

public final class FeatureCacheEpoch {
    private static final AtomicInteger EPOCH = new AtomicInteger();

    private FeatureCacheEpoch() {
    }

    public static int current() {
        return EPOCH.get();
    }

    public static int bump() {
        return EPOCH.incrementAndGet();
    }
}
