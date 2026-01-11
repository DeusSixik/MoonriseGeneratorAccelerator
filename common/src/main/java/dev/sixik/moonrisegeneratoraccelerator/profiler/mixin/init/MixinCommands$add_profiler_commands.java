package dev.sixik.moonrisegeneratoraccelerator.profiler.mixin.init;

import com.mojang.brigadier.CommandDispatcher;
import dev.sixik.moonrisegeneratoraccelerator.profiler.BtsProfilerCommands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public class MixinCommands$add_profiler_commands {

    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void bts$init(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        this.dispatcher.register(BtsProfilerCommands.registerCommand());
    }
}
