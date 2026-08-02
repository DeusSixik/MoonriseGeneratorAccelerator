package dev.sixik.generator_accelerator_benchmark;

public class MainBenchmark {

    public static final String START_COMMAND = "/spark profiler start --thread * --not-combined";

    public static final String STOP_COMMAND = "/spark profiler stop --save-to-file";

    public static void log(String message) {
        MGABenchmarkPlugin.LOGGER.info("[Auto Test] {}", message);
    }
}
