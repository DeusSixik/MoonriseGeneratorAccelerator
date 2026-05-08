package dev.sixik.generator_accelerator.common.treads_profiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GAThreadProfiler {

    private static final Logger LOGGER = LoggerFactory.getLogger("ThreadProfiler");
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();

    private static final ScheduledExecutorService PROFILER_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread t = new Thread(runnable, "GA-Thread-Profiler");
        t.setDaemon(true);
        return t;
    });

    public static void start(int intervalSeconds) {
        LOGGER.info("Starting Thread Profiler (Interval: {} sec)...", intervalSeconds);
        PROFILER_EXECUTOR.scheduleAtFixedRate(GAThreadProfiler::analyzeThreads, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private static void analyzeThreads() {
        ThreadInfo[] threadInfos = THREAD_BEAN.dumpAllThreads(false, false);

        Map<String, Integer> threadGroups = new HashMap<>();
        int totalThreads = threadInfos.length;
        int unnamedPools = 0;

        for (ThreadInfo info : threadInfos) {
            String name = info.getThreadName();

            String prefix = extractPrefix(name);

            if (prefix.equals("pool")) {
                unnamedPools++;
            } else {
                threadGroups.put(prefix, threadGroups.getOrDefault(prefix, 0) + 1);
            }
        }

        List<Map.Entry<String, Integer>> sortedGroups = threadGroups.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .toList();

        LOGGER.info("=== JVM THREADS REPORT (Total threads: {}) ===", totalThreads);

        for (Map.Entry<String, Integer> entry : sortedGroups) {
            int count = entry.getValue();
            if (count >= 5) {
                LOGGER.warn("SUSPICIOUS: Pool '{}' contains {} threads!", entry.getKey(), count);
            } else if (count > 1) {
                LOGGER.info("Pool '{}': {} threads", entry.getKey(), count);
            }
        }

        if (unnamedPools > 0) {
            LOGGER.error("WARNING! Found {} unnamed threads (pool-N-thread-M).", unnamedPools);
            LOGGER.error("Some mod uses default Executors without a ThreadFactory. This is wasting memory!");
        }
        LOGGER.info("==================================================");

    }

    private static String extractPrefix(String threadName) {
        String prefix = threadName.replaceAll("[-_]?\\d+$", "");

        if (prefix.startsWith("pool-") && prefix.contains("-thread")) {
            return "pool";
        }

        return prefix.trim();
    }
}
