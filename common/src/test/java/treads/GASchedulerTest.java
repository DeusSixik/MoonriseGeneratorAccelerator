package treads;

import dev.sixik.generator_accelerator.common.treads.GAFastLocalHolder;
import dev.sixik.generator_accelerator.common.treads.GAScheduler;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GASchedulerTest {
    @Test
    void noiseLaneUsesGaFastLocalWorker() throws Exception {
        String result = GAScheduler.supplyAsync(GAScheduler.Lane.NOISE, () ->
                Thread.currentThread().getName() + ":" + (Thread.currentThread() instanceof GAFastLocalHolder)
        ).get(10, TimeUnit.SECONDS);

        assertTrue(result.startsWith("GA-NOISE-"), result);
        assertTrue(result.endsWith(":true"), result);

        Map<String, Object> snapshot = GAScheduler.snapshot();
        assertTrue(snapshot.containsKey("lanes"));
    }
}
