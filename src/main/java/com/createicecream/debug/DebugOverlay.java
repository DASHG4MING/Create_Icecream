package com.createicecream.debug;

import com.createicecream.config.OptConfig;

import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/** One short line on the right side of F3; the details live in the statistics panel ({@link StatsHud}). */
public final class DebugOverlay {
    private DebugOverlay() {
    }

    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!OptConfig.debugOverlay) {
            return;
        }
        event.getRight().add("");
        event.getRight().add(StatsHud.f3Line());
    }
}
