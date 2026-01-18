package dev.sixik.moonrisegeneratoraccelerator.profiler;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

public class BtsProfilerCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> registerCommand() {
        return Commands.literal("bts_profiler")
                .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("start")
                                .executes(context -> startProfiler(context.getSource())))
                        .then(Commands.literal("stop")
                                .executes(context -> stopProfiler(context.getSource())))
                        .then(Commands.literal("module")
                                .then(Commands.argument("module_name", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            // Предлагаем доступные модули
                                            for (String module : getAvailableModules()) {
                                                builder.suggest(module);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("element", StringArgumentType.string())
                                                .suggests((context, builder) -> {
                                                    // Предлагаем элементы для выбранного модуля
                                                    String module = StringArgumentType.getString(context, "module_name");
                                                    for (String element : getAvailableElements(module)) {
                                                        builder.suggest(element);
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("state", BoolArgumentType.bool())
                                                        .executes(context -> setModuleState(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "module_name"),
                                                                StringArgumentType.getString(context, "element"),
                                                                BoolArgumentType.getBool(context, "state")
                                                        ))
                                                )
                                        )
                                )
                                .executes(context -> {
                                    context.getSource().sendSuccess(() ->
                                            Component.literal(BtsProfilerSettings.getString()), false);
                                    return 1;
                                })
                        )
                        .executes(context -> {
                            context.getSource().sendSuccess(() ->
                                    Component.literal("Profiler status: " + (BtsProfilerUtils.isConnected() ? "running" : "stopped")), false);
                            return 1;
                        })
                .executes(context -> {
                    sendUsage(context.getSource());
                    return 1;
                });
    }

    private static int setModuleState(CommandSourceStack source, String module, String element, boolean state) {
        try {
            boolean success = BtsProfilerSettings.setModuleState(module, element, state);

            if (success) {
                source.sendSuccess(() ->
                                Component.literal("[Java] Module '" + module + "' element '" + element + "' set to: " + state),
                        false);
                return 1;
            } else {
                source.sendFailure(Component.literal("[Java] Failed to set module state!"));
                return 0;
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("[Java] Error: " + e.getMessage()));
            return 0;
        }
    }

    // Методы для получения доступных модулей и элементов
    private static List<String> getAvailableModules() {
        return List.of(
                "server"
        );
    }

    private static List<String> getAvailableElements(String module) {
        // Возвращаем элементы для конкретного модуля
        switch (module) {
            case "server":
                return Arrays.asList("world_generation", "entity_tick", "block_tick", "network_synchronize", "global_tick");
            default:
                return List.of("default_element");
        }
    }

    private static void sendUsage(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6=== BTS Concurrent Profiler ==="), false);
        source.sendSuccess(() -> Component.literal("§e/bts_concurrent profiler start §7- Start profiling"), false);
        source.sendSuccess(() -> Component.literal("§e/bts_concurrent profiler stop §7- Stop profiling"), false);
        source.sendSuccess(() -> Component.literal("§e/bts_concurrent profiler §7- Show status"), false);
        source.sendSuccess(() -> Component.literal("§e/bts_concurrent profiler module <module> <element> <true/false> §7- Configure modules"), false);
        source.sendSuccess(() -> Component.literal("§7Available modules: " + String.join(", ", getAvailableModules())), false);
    }

    // Оригинальные методы остаются без изменений
    private static int startProfiler(CommandSourceStack source) {
        if(BtsProfilerUtils.isConnected()) {
            source.sendFailure(Component.literal("[Java] Profiler already started!"));
            return 0;
        }

        try {
            BtsProfilerUtils.isStarted = true;
            BtsProfilerThread.startThread(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("[C++] TracySocketServer is not started!"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[Java] Profiler started!"), false);
        return 1;
    }

    private static int stopProfiler(CommandSourceStack source) {
        if(!BtsProfilerUtils.isConnected()) {
            source.sendFailure(Component.literal("[Java] Profiler not started!"));
            return 0;
        }

        try {
            BtsProfilerThread.stopThread();
        } catch (Exception e) {
            source.sendFailure(Component.literal("[Java] Profiler not started!"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[Java] Profiler Stop!"), false);
        return 1;
    }
}
