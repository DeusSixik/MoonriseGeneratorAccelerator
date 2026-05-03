package dev.sixik.generator_accelerator.math.c3.cleaner;

import dev.sixik.generator_accelerator.math.c3.NativeNormalNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Deprecated
public class NativeObjectCleaner {

    private static Logger LOGGER = LoggerFactory.getLogger(NativeObjectCleaner.class);

    public record NativeState(long pointer) implements Runnable {

        @Override
        public void run() {
            if (pointer != 0) {
                LOGGER.debug("GC start delete native ptr");
                NativeNormalNoise.deleteNoise(pointer);
            }
        }
    }
}
