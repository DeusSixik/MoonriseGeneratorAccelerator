package dev.sixik.generator_accelerator_benchmark.mixin;

import com.mojang.authlib.GameProfile;
import dev.sixik.generator_accelerator.common.features.pipeline.DecorationPipelineMetrics;
import dev.sixik.generator_accelerator.common.features.vm.FeatureVmMetrics;
import dev.sixik.generator_accelerator.diagnostics.GADiagnostics;
import dev.sixik.generator_accelerator_benchmark.MGABenchmarkPlugin;
import dev.sixik.generator_accelerator_benchmark.MainBenchmark;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInfo;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin extends ReentrantBlockableEventLoop<TickTask>
        implements ServerInfo,
        ChunkIOErrorReporter,
        CommandSource,
        AutoCloseable {

    @Unique private ServerPlayer fakePlayer;
    @Unique private boolean isProfilerStart = false;
    @Unique private boolean benchmarkFinished = false;
    @Unique private int tickCounter = 0;
    @Unique private long benchmarkStartNanos = 0L;
    @Unique private int generatedBatches = 0;
    @Unique private boolean ga$directChunkDriver = true;
    @Unique private int ga$nextBatchCenterChunkX = 0;
    @Unique private int ga$nextBatchCenterChunkZ = 0;
    @Unique private int ga$directChunkTicketRadius = 0;
    @Unique private final List<ChunkPos> ga$activeChunkTickets = new ArrayList<>();
    @Unique private volatile boolean watchdogStarted = false;
    @Unique private volatile boolean serverTickSeen = false;
    @Unique private volatile long lastTickNanos = System.nanoTime();

    private MinecraftServerMixin(String string) {
        super(string);
    }

    @Inject(method = "runServer", at = @At("HEAD"))
    public void sdm$runServer(CallbackInfo ci) {
        if (Boolean.parseBoolean(System.getProperty("ga.benchmark.resetWorld", "true"))) {
            MinecraftServer server = (MinecraftServer) (Object) this;
            Path worldPath = server.getWorldPath(LevelResource.ROOT);
            sdm$deleteWorldFolder(worldPath);
        }
        this.sdm$startWatchdog();
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    public void sdm$tickServer(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        this.serverTickSeen = true;
        this.lastTickNanos = System.nanoTime();

        int startTick = Integer.getInteger("ga.benchmark.startTick", 30);
        int stopTick = Integer.getInteger("ga.benchmark.stopTick", 1500);
        int haltTick = Integer.getInteger("ga.benchmark.haltTick", 2100);
        int ticksBetweenBatches = Integer.getInteger("ga.benchmark.ticksBetweenBatches", 40);
        int renderDistance = Integer.getInteger("ga.benchmark.renderDistance", 16);
        int maxBatches = Integer.getInteger("ga.benchmark.maxBatches", -1);
        int startChunkX = Integer.getInteger("ga.benchmark.startChunkX", 0);
        int startChunkZ = Integer.getInteger("ga.benchmark.startChunkZ", 0);
        boolean useSpark = Boolean.parseBoolean(System.getProperty("ga.benchmark.useSpark", "true"));
        boolean directChunkLoad = Boolean.parseBoolean(System.getProperty("ga.benchmark.directChunkLoad", "true"));

        if (!isProfilerStart && !benchmarkFinished && tickCounter >= startTick && server.overworld() != null) {
            this.ga$directChunkDriver = directChunkLoad;
            this.ga$nextBatchCenterChunkX = startChunkX;
            this.ga$nextBatchCenterChunkZ = startChunkZ;
            this.ga$directChunkTicketRadius = 0;
            this.ga$activeChunkTickets.clear();
            this.fakePlayer = null;
            if (!this.ga$directChunkDriver) {
                this.fakePlayer = this.sdm$makePlayer();
                this.fakePlayer.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
                this.fakePlayer.teleportTo(server.overworld(), 0, 100, 0, 0, 0);
            }

            if (useSpark) {
                var commandSource = server.createCommandSourceStack().withPermission(4);
                server.getCommands().performPrefixedCommand(commandSource, MainBenchmark.START_COMMAND);
            }
            if (DecorationPipelineMetrics.ENABLED) {
                DecorationPipelineMetrics.reset();
            }
            GADiagnostics.resetBaseline();
            if (Boolean.getBoolean("ga.diagnostics.jfr")) {
                GADiagnostics.restartRecording(
                        "ga-benchmark",
                        Boolean.getBoolean("ga.diagnostics.jfr.allocations")
                );
            } else {
                GADiagnostics.startJfrIfEnabled("ga-benchmark");
            }
            this.benchmarkStartNanos = System.nanoTime();

            isProfilerStart = true;
            this.generatedBatches = 0;
            MainBenchmark.log("Benchmark config: startTick=" + startTick
                    + ", stopTick=" + stopTick
                    + ", haltTick=" + haltTick
                    + ", ticksBetweenBatches=" + ticksBetweenBatches
                    + ", renderDistance=" + renderDistance
                    + ", maxBatches=" + maxBatches
                    + ", startChunkX=" + startChunkX
                    + ", startChunkZ=" + startChunkZ
                    + ", useSpark=" + useSpark
                    + ", driver=" + (this.ga$directChunkDriver ? "direct_chunk" : "fake_player"));
        }

        if (isProfilerStart) {
            if (ticksBetweenBatches > 0 && tickCounter % ticksBetweenBatches == 0
                    && (maxBatches < 0 || this.generatedBatches < maxBatches)) {
                if (this.ga$directChunkDriver) {
                    this.sdm$runDirectBatch(server.overworld(), renderDistance);
                } else if (this.fakePlayer != null) {
                    double blocksToMove = renderDistance * 2 * 16;
                    double newX = this.fakePlayer.getX() + blocksToMove;

                    this.fakePlayer.teleportTo(
                            server.overworld(),
                            newX,
                            this.fakePlayer.getY(),
                            this.fakePlayer.getZ(),
                            this.fakePlayer.getYRot(),
                            this.fakePlayer.getXRot()
                    );
                }
                this.generatedBatches++;
            }

            if (this.fakePlayer != null) {
                this.fakePlayer.resetLastActionTime();
            }
        }

        if(!benchmarkFinished && isProfilerStart && (tickCounter >= stopTick || (maxBatches >= 0 && this.generatedBatches >= maxBatches))) {
            if (useSpark) {
                var commandSource = server.createCommandSourceStack().withPermission(4);
                server.getCommands().performPrefixedCommand(commandSource, MainBenchmark.STOP_COMMANd);
            }
            long elapsedMs = (System.nanoTime() - this.benchmarkStartNanos) / 1_000_000L;
            MainBenchmark.log("Benchmark wall time ms: " + elapsedMs);
            MainBenchmark.log("Benchmark generated batches: " + this.generatedBatches);
            if (FeatureVmMetrics.ENABLED) {
                MainBenchmark.log(FeatureVmMetrics.summary());
            }
            if (DecorationPipelineMetrics.ENABLED) {
                MainBenchmark.log(DecorationPipelineMetrics.summary());
            }
            Path diagnosticsPath = GADiagnostics.writeBenchmarkDump(
                    "benchmark-finished",
                    this.sdm$benchmarkDiagnostics(startTick, stopTick, haltTick, ticksBetweenBatches,
                            renderDistance, maxBatches, useSpark, elapsedMs)
            );
            if (diagnosticsPath != null) {
                MainBenchmark.log("Benchmark diagnostics: " + diagnosticsPath.toAbsolutePath());
            }
            isProfilerStart = false;
            benchmarkFinished = true;
            if (this.ga$directChunkDriver) {
                this.sdm$clearDirectBatchTickets(server.overworld());
            }
            if (this.fakePlayer != null) {
                server.getPlayerList().removeAll();
                this.fakePlayer = null;
            }
        }

        if (benchmarkFinished && tickCounter >= haltTick) {
            MainBenchmark.log("Time's up. Sending the /stop command...");
            Runtime.getRuntime().halt(0);
        }

        tickCounter++;
    }

    private ServerPlayer sdm$makePlayer() {
        MinecraftServer server = (MinecraftServer) (Object) this;

        CommonListenerCookie commonListenerCookie = new CommonListenerCookie(new GameProfile(UUID.randomUUID(), "test-mock-player"), 0, new ClientInformation("en_us", 16, ChatVisiblity.FULL, true, 0, Player.DEFAULT_MAIN_HAND, false, false), false);
        ServerPlayer serverPlayer = new ServerPlayer(server, server.overworld(), commonListenerCookie.gameProfile(), commonListenerCookie.clientInformation()){

            @Override
            public boolean isSpectator() {
                return true;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, serverPlayer, commonListenerCookie);
        serverPlayer.connection = new GA$BenchmarkPacketListener(server, connection, serverPlayer, commonListenerCookie);
        return serverPlayer;
    }

    @Unique
    private void sdm$runDirectBatch(ServerLevel level, int renderDistance) {
        int radius = Math.max(1, renderDistance);
        int centerChunkX = this.ga$nextBatchCenterChunkX;
        int centerChunkZ = this.ga$nextBatchCenterChunkZ;
        int ticketRadius = radius + 1;
        ChunkPos center = new ChunkPos(centerChunkX, centerChunkZ);
        this.ga$directChunkTicketRadius = ticketRadius;
        level.getChunkSource().addRegionTicket(TicketType.START, center, ticketRadius, Unit.INSTANCE);
        this.ga$activeChunkTickets.add(center);
        for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
            for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
                level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
            }
        }

        int strideChunks = Math.max(1, renderDistance * 2);
        this.ga$nextBatchCenterChunkX += strideChunks;
    }

    @Unique
    private void sdm$clearDirectBatchTickets(ServerLevel level) {
        if (this.ga$activeChunkTickets.isEmpty()) {
            return;
        }
        for (int i = 0, size = this.ga$activeChunkTickets.size(); i < size; i++) {
            level.getChunkSource().removeRegionTicket(
                    TicketType.START,
                    this.ga$activeChunkTickets.get(i),
                    this.ga$directChunkTicketRadius,
                    Unit.INSTANCE
            );
        }
        this.ga$activeChunkTickets.clear();
        this.ga$directChunkTicketRadius = 0;
    }

    @Unique
    private static final class GA$BenchmarkPacketListener extends ServerGamePacketListenerImpl {
        private GA$BenchmarkPacketListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
            super(server, connection, player, cookie);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
            if (packet instanceof ClientboundCustomPayloadPacket) {
                if (listener != null) {
                    listener.onSuccess();
                }
                return;
            }
            super.send(packet, listener);
        }
    }

    @Unique
    private void sdm$startWatchdog() {
        if (!Boolean.parseBoolean(System.getProperty("ga.benchmark.watchdog", "true"))) {
            return;
        }
        if (this.watchdogStarted) {
            return;
        }
        this.watchdogStarted = true;
        this.lastTickNanos = System.nanoTime();

        int startupWatchdogSeconds = Integer.getInteger("ga.benchmark.startupWatchdogSeconds", 180);
        int tickWatchdogSeconds = Integer.getInteger("ga.benchmark.tickWatchdogSeconds", 120);
        if (startupWatchdogSeconds <= 0 && tickWatchdogSeconds <= 0) {
            return;
        }

        Thread thread = new Thread(() -> {
            while (!this.benchmarkFinished) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }

                long idleSeconds = (System.nanoTime() - this.lastTickNanos) / 1_000_000_000L;
                if (!this.serverTickSeen && startupWatchdogSeconds > 0 && idleSeconds >= startupWatchdogSeconds) {
                    sdm$haltByWatchdog("startup watchdog fired after " + idleSeconds + "s without server tick", 124);
                }
                if (this.serverTickSeen && tickWatchdogSeconds > 0 && idleSeconds >= tickWatchdogSeconds) {
                    sdm$haltByWatchdog("tick watchdog fired after " + idleSeconds + "s without tick progress", 125);
                }
            }
        }, "ga-benchmark-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    @Unique
    private static void sdm$haltByWatchdog(String reason, int exitCode) {
        MainBenchmark.log("Benchmark " + reason + ". Writing thread dump and halting server.");
        sdm$writeThreadDump(reason);
        Path diagnosticsPath = GADiagnostics.writeBenchmarkDump("watchdog-" + reason, Map.of("watchdogReason", reason));
        if (diagnosticsPath != null) {
            MainBenchmark.log("Benchmark watchdog diagnostics: " + diagnosticsPath.toAbsolutePath());
        }
        Runtime.getRuntime().halt(exitCode);
    }

    @Unique
    private Map<String, Object> sdm$benchmarkDiagnostics(
            int startTick,
            int stopTick,
            int haltTick,
            int ticksBetweenBatches,
            int renderDistance,
            int maxBatches,
            boolean useSpark,
            long elapsedMs
    ) {
        Map<String, Object> benchmark = new LinkedHashMap<>();
        benchmark.put("startTick", startTick);
        benchmark.put("stopTick", stopTick);
        benchmark.put("haltTick", haltTick);
        benchmark.put("ticksBetweenBatches", ticksBetweenBatches);
        benchmark.put("renderDistance", renderDistance);
        benchmark.put("maxBatches", maxBatches);
        benchmark.put("useSpark", useSpark);
        benchmark.put("driver", this.ga$directChunkDriver ? "direct_chunk" : "fake_player");
        benchmark.put("elapsedMs", elapsedMs);
        benchmark.put("generatedBatches", this.generatedBatches);
        benchmark.put("tickCounter", this.tickCounter);
        return benchmark;
    }

    @Unique
    private static void sdm$writeThreadDump(String reason) {
        try {
            Path dumpDir = Path.of(System.getProperty("ga.benchmark.dumpDir", "benchmark-dumps"));
            Files.createDirectories(dumpDir);
            String safeReason = reason.replaceAll("[^A-Za-z0-9._-]+", "_");
            Path dumpPath = dumpDir.resolve("watchdog-" + System.currentTimeMillis() + "-" + safeReason + ".txt");
            StringBuilder builder = new StringBuilder(128 * 1024);
            builder.append("Benchmark watchdog: ").append(reason).append('\n');
            builder.append("Tick dump nanos: ").append(System.nanoTime()).append('\n');

            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                builder.append('\n')
                        .append('"').append(thread.getName()).append('"')
                        .append(" id=").append(thread.getId())
                        .append(" state=").append(thread.getState())
                        .append(" daemon=").append(thread.isDaemon())
                        .append('\n');
                for (StackTraceElement element : entry.getValue()) {
                    builder.append("\tat ").append(element).append('\n');
                }
            }

            Files.writeString(dumpPath, builder.toString());
            MainBenchmark.log("Benchmark watchdog thread dump: " + dumpPath.toAbsolutePath());
        } catch (Throwable throwable) {
            MainBenchmark.log("Benchmark watchdog failed to write thread dump: " + throwable);
        }
    }

    @Unique
    private void sdm$deleteWorldFolder(Path path) {
        try {
            if (Files.exists(path)) {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);

                MGABenchmarkPlugin.LOGGER.info("[Auto Test] The world folder has been successfully reset.: {}", path.toAbsolutePath());
            }
        } catch (IOException e) {
            MGABenchmarkPlugin.LOGGER.info("[Auto Test] Failed to delete world folder!", e);
        }
    }
}
