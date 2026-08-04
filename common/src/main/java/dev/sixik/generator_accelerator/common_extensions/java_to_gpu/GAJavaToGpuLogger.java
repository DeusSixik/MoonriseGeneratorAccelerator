package dev.sixik.generator_accelerator.common_extensions.java_to_gpu;

import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLogRecord;
import net.sixik.ga_utils.javatogpu.runtime.GpuRuntimeLogService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class GAJavaToGpuLogger implements GpuRuntimeLogService {
    public static final String INFO_LOG_PROPERTY = "ga.dfc.gpu.runtimeInfoLog";
    private static final Logger LOGGER = LogManager.getLogger("GA-JavaToGpu");

    @Override
    public void log(GpuRuntimeLogRecord record) {
        String message = record.fields().isEmpty()
                ? record.message()
                : record.message() + " " + record.fields();

        switch (record.level()) {
            case TRACE -> LOGGER.trace(message, record.throwable());
            case DEBUG -> LOGGER.debug(message, record.throwable());
            case INFO -> {
                if (Boolean.getBoolean(INFO_LOG_PROPERTY)) {
                    LOGGER.info(message, record.throwable());
                } else {
                    LOGGER.debug(message, record.throwable());
                }
            }
            case WARN -> LOGGER.warn(message, record.throwable());
            case ERROR -> LOGGER.error(message, record.throwable());
        }
    }

    @Override
    public String extensionId() {
        return "ga.log4j-runtime-log";
    }
}
