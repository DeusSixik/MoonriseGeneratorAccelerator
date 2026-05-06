package dev.sixik.generator_accelerator_benchmark.mixin;

import com.mojang.authlib.GameProfile;
import dev.sixik.generator_accelerator.common.features.vm.FeatureVmMetrics;
import dev.sixik.generator_accelerator_benchmark.MGABenchmarkPlugin;
import dev.sixik.generator_accelerator_benchmark.MainBenchmark;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInfo;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
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
import java.util.Comparator;
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
    @Unique private int tickCounter = 0;
    @Unique private long benchmarkStartNanos = 0L;

    private MinecraftServerMixin(String string) {
        super(string);
    }

    @Inject(method = "runServer", at = @At("HEAD"))
    public void sdm$runServer(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        sdm$deleteWorldFolder(worldPath);
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    public void sdm$tickServer(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;


        if (!isProfilerStart && tickCounter == 30) {
            var commandSource = server.createCommandSourceStack().withPermission(4);
            server.getCommands().performPrefixedCommand(commandSource, MainBenchmark.START_COMMAND);
            this.benchmarkStartNanos = System.nanoTime();

            this.fakePlayer = this.sdm$makePlayer();
            fakePlayer.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
            this.fakePlayer.teleportTo(server.overworld(), 0, 100, 0, 0, 0);

            isProfilerStart = true;
        }

        if (this.fakePlayer != null && isProfilerStart) {
            int ticksBetweenBatches = 40; // Каждые 2 секунды

            if (tickCounter % ticksBetweenBatches == 0) {
                int renderDistance = 16;
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

            this.fakePlayer.resetLastActionTime();
        }

        if(tickCounter == 1500) {
            var commandSource = server.createCommandSourceStack().withPermission(4);
            server.getCommands().performPrefixedCommand(commandSource, MainBenchmark.STOP_COMMANd);
            long elapsedMs = (System.nanoTime() - this.benchmarkStartNanos) / 1_000_000L;
            MainBenchmark.log("Benchmark wall time ms: " + elapsedMs);
            if (FeatureVmMetrics.ENABLED) {
                MainBenchmark.log(FeatureVmMetrics.summary());
            }
            isProfilerStart = false;
            server.getPlayerList().removeAll();
        }

        if (tickCounter == 2100) {
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
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, serverPlayer, commonListenerCookie);
        return serverPlayer;
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
