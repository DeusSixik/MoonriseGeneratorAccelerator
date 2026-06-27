package dev.sixik.generator_accelerator.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sixik.generator_accelerator.debug.GADebugOverlay;
import dev.sixik.generator_accelerator.debug.GADebugOverlayImGuiBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class GeneratorAcceleratorFabricClient implements ClientModInitializer {
    private static final String KEY_CATEGORY = "Generator Accelerator";
    private static final String KEY_NAME = "GA Debug Menu";

    @Override
    public void onInitializeClient() {
        if (!GADebugOverlay.isAvailable()) {
            return;
        }

        GADebugOverlayImGuiBridge.tryInstall();

        KeyMapping debugKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_NAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (debugKey.consumeClick()) {
                GADebugOverlay.toggle();
            }
        });
    }
}
