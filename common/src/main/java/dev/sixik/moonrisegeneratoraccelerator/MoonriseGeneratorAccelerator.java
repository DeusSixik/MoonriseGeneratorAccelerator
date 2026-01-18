package dev.sixik.moonrisegeneratoraccelerator;

import com.google.common.collect.ImmutableList;
import com.mojang.logging.LogUtils;
import dev.sdm.profiler.network.TcpClient;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.slf4j.Logger;

public final class MoonriseGeneratorAccelerator {
    public static final String MOD_ID = "moonrise_generator_accelerator";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static void init() {
        // Write common init code here.
        TcpClient.MessageConsumer = LOGGER::info;
        ImmutableList.Builder<NoiseChunk.BlockStateFiller> builder = new ImmutableList.Builder<>();
        builder.add(st -> null);
    }
}
