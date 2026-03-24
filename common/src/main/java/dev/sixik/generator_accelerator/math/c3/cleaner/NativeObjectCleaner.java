package dev.sixik.generator_accelerator.math.c3.cleaner;

import dev.sixik.generator_accelerator.math.c3.NativeNormalNoise;
import dev.sixik.generator_accelerator.math.c3.NativeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;

@Deprecated
public class NativeObjectCleaner {

    private static Logger LOGGER = LoggerFactory.getLogger(NativeObjectCleaner.class);

    private static final Cleaner CLEANER = Cleaner.create();

    public record NativeState(long pointer) implements Runnable {

        @Override
        public void run() {
            if (pointer != 0) {
                LOGGER.debug("GC start delete native ptr");
                NativeNormalNoise.deleteNoise(pointer);
            }
        }
    }

    public static Cleaner.Cleanable create(Object own, long ptr) {
        return CLEANER.register(own, new NativeState(ptr));
    }
}
