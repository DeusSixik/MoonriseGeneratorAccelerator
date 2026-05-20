package dev.sixik.generator_accelerator.common.density.compiler;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DensityFunctionCompilerOpenClCommandTest {
    @Test
    void finalDensityWaveBenchCommandsAcceptCellsArgument() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        DensityFunctionCompiler.registerCommands(dispatcher);

        assertNotNull(openClCommand(dispatcher, "compiledfinaldensitywavesbench").getChild("cells"));
        assertNotNull(openClCommand(dispatcher, "compiledfinaldensitywavescompactbench").getChild("cells"));
        assertNotNull(openClCommand(dispatcher, "compiledfinaldensitywavefusedbench").getChild("cells"));
        assertNotNull(openClCommand(dispatcher, "compiledfinaldensityallwavesfusedbench").getChild("cells"));
    }

    @Test
    void finalDensityWaveCheckCommandsAcceptCellsArgument() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        DensityFunctionCompiler.registerCommands(dispatcher);

        assertNotNull(openClCommand(dispatcher, "compiledfinaldensitywavefusedcheck").getChild("cells"));
        assertNotNull(openClCommand(dispatcher, "compiledfinaldensityhybridcheck").getChild("cells"));
        assertNotNull(openClCommand(dispatcher, "compiledfinaldensityallwavesfusedcheck").getChild("cells"));
    }

    @Test
    void finalDensityWaveReadbackBenchCommandsAcceptCellsArgument() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        DensityFunctionCompiler.registerCommands(dispatcher);

        assertNotNull(openClCommand(dispatcher, "compiledfinaldensitywavefusedreadbench").getChild("cells"));
        assertNotNull(openClCommand(dispatcher, "compiledfinaldensityallwavesfusedreadbench").getChild("cells"));
    }

    @Test
    void finalDensityHybridBenchCommandsAcceptCellsArgument() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        DensityFunctionCompiler.registerCommands(dispatcher);

        assertNotNull(openClCommand(dispatcher, "compiledfinaldensitywavefusedhybridbench").getChild("cells"));
        assertNotNull(openClCommand(dispatcher, "compiledfinaldensityallwavesfusedhybridbench").getChild("cells"));
    }

    @Test
    void finalDensityHybridBenchCommandsAreDiagnosticOnlyAndCappedAtOneCell() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        DensityFunctionCompiler.registerCommands(dispatcher);

        assertEquals(1, integerArgument(openClCommand(dispatcher,
                "compiledfinaldensitywavefusedhybridbench").getChild("cells")).getMaximum());
        assertEquals(1, integerArgument(openClCommand(dispatcher,
                "compiledfinaldensityallwavesfusedhybridbench").getChild("cells")).getMaximum());
    }

    @Test
    void finalDensityAllWavesOutputCommandsAcceptCellsArgument() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        DensityFunctionCompiler.registerCommands(dispatcher);

        assertNotNull(openClCommand(dispatcher, "compiledfinaldensityallwavesoutputcheck").getChild("cells"));
        assertNotNull(openClCommand(dispatcher, "compiledfinaldensityallwavesoutputbench").getChild("cells"));
    }

    private static CommandNode<CommandSourceStack> openClCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                                                 String name) {
        return dispatcher.getRoot()
                .getChild("dfc")
                .getChild("opencl")
                .getChild(name);
    }

    private static IntegerArgumentType integerArgument(CommandNode<CommandSourceStack> node) {
        return (IntegerArgumentType) ((ArgumentCommandNode<CommandSourceStack, ?>) node).getType();
    }
}
