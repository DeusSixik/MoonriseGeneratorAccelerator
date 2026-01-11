package dev.sixik.moonrisegeneratoraccelerator.profiler;

import dev.sdm.profiler.TracyProfiler;
import dev.sdm.profiler.network.TcpClient;

import java.net.Socket;

public class BtsProfilerUtils {

    public static boolean isStarted = false;

    public static void startZone(String name, boolean active) {
        if(active) startZone(name);
    }

    public static void endZone(String name, boolean active) {
        if(active) endZone(name);
    }

    public static void startZone(String name, BtsProfilerSettings.Type type) {
        if(isStarted && type.isActive()) TracyProfiler.startZone(name);
    }

    public static void endZone(String name, BtsProfilerSettings.Type type) {
        if(isStarted && type.isActive())
            TracyProfiler.endZone(name);
    }

    public static void startZone(String name) {
        if(isStarted)
            TracyProfiler.startZone(name);
    }

    public static void endZone(String name) {
        if(isStarted)
            TracyProfiler.endZone(name);
    }

    public static boolean isConnected() {
        final Socket socket = TcpClient.getSocket();

        return socket != null && socket.isConnected() && !socket.isClosed();
    }
}
