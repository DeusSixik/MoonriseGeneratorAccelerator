package dev.sixik.generator_accelerator.diagnostics;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GADiagnosticsCommands {
    private GADiagnosticsCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ga")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("diagnostics")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("start")
                                .executes(context -> start(context.getSource())))
                        .then(Commands.literal("reset")
                                .executes(context -> reset(context.getSource())))
                        .then(Commands.literal("dump")
                                .executes(context -> dump(context.getSource(), false)))
                        .then(Commands.literal("stop")
                                .executes(context -> dump(context.getSource(), true)))
                        .then(Commands.literal("folder")
                                .executes(context -> folder(context.getSource())))));
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(GADiagnostics.statusLine()), false);
        Path activeJfr = GADiagnostics.activeJfrPath();
        if (activeJfr != null) {
            source.sendSuccess(() -> Component.literal("GA diagnostics active JFR: " + activeJfr.toAbsolutePath()), false);
        }
        return GADiagnostics.isRecordingActive() ? 1 : 0;
    }

    private static int start(CommandSourceStack source) {
        Path path = GADiagnostics.restartRecording("command-start", true);
        if (path == null) {
            source.sendFailure(Component.literal("GA diagnostics failed to start JFR. Check log."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("GA diagnostics JFR active: " + path.toAbsolutePath()), false);
        return 1;
    }

    private static int reset(CommandSourceStack source) {
        GADiagnostics.resetAllMetrics();
        Path path = GADiagnostics.restartRecording("command-reset", true);
        source.sendSuccess(() -> Component.literal("GA diagnostics counters reset."), false);
        if (path != null) {
            source.sendSuccess(() -> Component.literal("GA diagnostics JFR restarted: " + path.toAbsolutePath()), false);
        }
        return 1;
    }

    private static int dump(CommandSourceStack source, boolean stop) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("commandSource", source.getTextName());
        extra.put("stopped", stop);

        GADiagnostics.DumpResult result = stop
                ? GADiagnostics.writeStopBundle("command-stop", extra)
                : GADiagnostics.writeSnapshotBundle("command-dump", extra);
        if (result.jsonPath() == null && result.jfrPath() == null && result.zipPath() == null) {
            source.sendFailure(Component.literal("GA diagnostics dump failed. Check log."));
            return 0;
        }
        if (result.zipPath() != null) {
            source.sendSuccess(() -> Component.literal("GA diagnostics bundle: " + result.zipPath().toAbsolutePath()), false);
        }
        if (result.jsonPath() != null) {
            source.sendSuccess(() -> Component.literal("GA diagnostics JSON: " + result.jsonPath().toAbsolutePath()), false);
        }
        if (result.jfrPath() != null) {
            source.sendSuccess(() -> Component.literal("GA diagnostics JFR: " + result.jfrPath().toAbsolutePath()), false);
        }
        if (stop) {
            source.sendSuccess(() -> Component.literal("GA diagnostics recording stopped. Use /ga diagnostics start to resume."), false);
        }
        return 1;
    }

    private static int folder(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("GA diagnostics folder: "
                + GADiagnostics.dumpDirectory().toAbsolutePath()), false);
        return 1;
    }
}
