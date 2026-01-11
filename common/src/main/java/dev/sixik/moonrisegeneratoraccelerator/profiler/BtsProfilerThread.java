package dev.sixik.moonrisegeneratoraccelerator.profiler;

import dev.sdm.profiler.TracyProfiler;
import dev.sixik.moonrisegeneratoraccelerator.MoonriseGeneratorAccelerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public class BtsProfilerThread extends Thread {

    private static BtsProfilerThread profilerThread;

    public static void startThread() {
        startThread(null);
    }

    public static void startThread(@Nullable CommandSourceStack source) {
        profilerThread = new BtsProfilerThread(source);
        profilerThread.start();
    }

    public static void stopThread() {
        if(profilerThread != null) {
            profilerThread.interrupt();
        }

        if(BtsProfilerUtils.isConnected()) {
            try {
                TracyProfiler.disconnect();
                BtsProfilerUtils.isStarted = false;
            } catch (Exception e) {
                MoonriseGeneratorAccelerator.LOGGER.error(e.getMessage(), e);
            }
        }
    }

    protected BtsProfilerThread(@Nullable CommandSourceStack source) {
        super(() -> {
            if(BtsProfilerUtils.isConnected()) {

                if(source != null) source.sendFailure(Component.literal("Can't start profiler. Profiler already started!"));
                else MoonriseGeneratorAccelerator.LOGGER.error("Can't start profiler. Profiler already started!");
                return;
            }

            try {
                TracyProfiler.startConnection();
            } catch (Exception e) {
                if(source != null) source.sendFailure(Component.literal("Can't start profiler. May be [C++] TracySocketServer not started!"));
                else MoonriseGeneratorAccelerator.LOGGER.error("Can't start profiler. May be [C++] TracySocketServer not started!", e);
            }
        });
    }
}
