package dev.sixik.generator_accelerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

public final class GeneratorAccelerator {
    public static final String MOD_ID = "generator_accelerator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ForkJoinPool CUSTOM_POOL = new ForkJoinPool(
            Math.min(0x7fff, Runtime.getRuntime().availableProcessors()),
            new ForkJoinPool.ForkJoinWorkerThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger();

                @Override
                public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
                    ForkJoinWorkerThread worker =
                            ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);

                    worker.setName("GA-NOISE-TREAD-" + counter.incrementAndGet());
                    return worker;
                }
            },
            null,
            false
    );
    public static Platform platform = null;

    public static void init(Platform platform) {
        GeneratorAccelerator.platform = platform;
    }

    public static void tryLoadNatives() {
        if(GeneratorAcceleratorNatives.isLoaded()) return;
        GeneratorAcceleratorNatives.initialize();
    }


    public enum Platform {
        FABRIC,
        FORGE,
        NEOFORGE
    }
}
