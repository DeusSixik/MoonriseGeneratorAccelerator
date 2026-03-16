package dev.sixik.generator_accelerator_benchmark;

public class MainBenchmark {

    public static String START_COMMAND = "/spark profiler start --thread * --not-combined";

    public static String STOP_COMMANd = "/spark profiler stop --save-to-file";

    public static boolean ACTIVATE = false;

    public static void log(String message) {
        MGABenchmarkPlugin.LOGGER.info("[Auto Test] {}", message);
    }
}
