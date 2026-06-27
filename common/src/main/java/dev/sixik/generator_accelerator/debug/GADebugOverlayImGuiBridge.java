package dev.sixik.generator_accelerator.debug;

import dev.sixik.generator_accelerator.GeneratorAccelerator;
import foundry.imgui.api.ImGuiMCEvents;

public final class GADebugOverlayImGuiBridge {
    private static boolean installed;

    private GADebugOverlayImGuiBridge() {
    }

    public static void tryInstall() {
        if (installed || !GeneratorAccelerator.isDevMode()) {
            return;
        }
        if (!isClassPresent("foundry.imgui.api.ImGuiMCEvents")) {
            GeneratorAccelerator.LOGGER.debug("MCImGui is not present, GA debug overlay hook skipped.");
            return;
        }

        ImGuiMCEvents.INSTANCE.postRenderImGuiEvent(GADebugOverlay::render);
        installed = true;
        GeneratorAccelerator.LOGGER.info("Installed GA debug overlay ImGui hook.");
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, GADebugOverlayImGuiBridge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
