package dev.sixik.moonrisegeneratoraccelerator.profiler;

import java.util.function.Supplier;

public class BtsProfilerSettings {
    
    public static boolean Global = true;
    public static boolean WorldGeneration = false;
    public static boolean EntityTick = false;
    public static boolean BlockTick = false;
    public static boolean NetworkSynchronize = false;

    public static boolean setModuleState(String module, String element, boolean state) {
        return true;
    }

    public static String getString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("Modules State:").append("\n");
        builder.append("    Server").append("\n");
        builder.append("        WorldGeneration").append(" = ").append(WorldGeneration).append("\n");
        builder.append("        EntityTick").append(" = ").append(EntityTick).append("\n");
        builder.append("        BlockTick").append(" = ").append(BlockTick).append("\n");
        builder.append("        NetworkSynchronize").append(" = ").append(NetworkSynchronize);
        builder.append("        GlobalTick").append(" = ").append(Global);
        return builder.toString();
    }

    public enum Type {
        WorldGen(1 << 1, () -> WorldGeneration),
        EntityTick(1 << 2, () -> BtsProfilerSettings.EntityTick),
        BlockTick(1 << 3, () -> BtsProfilerSettings.BlockTick),
        NetworkSynchronize(1 << 4, () -> BtsProfilerSettings.NetworkSynchronize),
        GlobalTick(1 << 5, () -> Global);

        public final int bits;
        public final Supplier<Boolean> getter;

        Type(int bits, Supplier<Boolean> getter) {
            this.bits = bits;
            this.getter = getter;
        }

        public boolean isActive() {
            return getter.get();
        }

    }
}
