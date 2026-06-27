package dev.sixik.generator_accelerator.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sixik.generator_accelerator.debug.GADebugOverlay;
import dev.sixik.generator_accelerator.debug.GADebugOverlayImGuiBridge;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public final class GeneratorAcceleratorNeoForgeClient {
    private static final String KEY_CATEGORY = "Generator Accelerator";
    private static final String KEY_NAME = "GA Debug Menu";

    private static final KeyMapping DEBUG_KEY = new KeyMapping(
            KEY_NAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            KEY_CATEGORY
    );

    private GeneratorAcceleratorNeoForgeClient() {
    }

    public static void init(IEventBus modBus) {
        if (!GADebugOverlay.isAvailable()) {
            return;
        }

        GADebugOverlayImGuiBridge.tryInstall();

        modBus.addListener(GeneratorAcceleratorNeoForgeClient::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(GeneratorAcceleratorNeoForgeClient::onClientTickPost);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(DEBUG_KEY);
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        while (DEBUG_KEY.consumeClick()) {
            GADebugOverlay.toggle();
        }
    }
}
