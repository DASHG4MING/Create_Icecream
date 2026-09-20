package com.createicecream.debug;

import java.util.ArrayList;
import java.util.List;

import com.createicecream.CreateIceCream;
import com.createicecream.compat.ModCompat;
import com.createicecream.config.OptConfig;
import com.createicecream.culling.ContraptionCulling;
import com.createicecream.culling.CullingManager;
import com.createicecream.mixinplugin.IceCreamMixinPlugin;
import com.createicecream.render.cache.CacheStats;
import com.createicecream.render.lod.DetailLod;
import com.createicecream.sound.SoundLimiter;

import dev.engine_room.flywheel.api.backend.BackendManager;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * The statistics panel: a compact, colour-coded box in a screen corner, toggled with its own key (F7 by
 * default). It replaces the long lines this mod used to add to F3, which could run off the screen.
 * Only rows that mean something for the current setup are shown (e.g. "animation reuse" only exists on the
 * shader path), and anything that needs attention is listed at the bottom in yellow / red.
 */
public final class StatsHud {
    public static final KeyMapping TOGGLE_KEY = new KeyMapping("key.createicecream.stats", 296 /* GLFW F7 */,
            "key.categories.createicecream");

    private static final int BG = 0xB0101418;
    private static final int BORDER = 0xFFF2A7C8;   // strawberry
    private static final int TITLE = 0xFFF2A7C8;
    private static final int LABEL = 0xFFA9B1BA;
    private static final int VALUE = 0xFFFFFFFF;
    private static final int DIM = 0xFF6E7781;
    private static final int GOOD = 0xFF7EE081;
    private static final int WARN = 0xFFFFD166;
    private static final int BAD = 0xFFFF6B6B;
    private static final int PAD = 5;
    private static final int GAP = 10;

    public static boolean visible;

    private record Row(String label, String value, int color) {
    }

    private StatsHud() {
    }

    /** Client tick: handle the toggle key. */
    public static void onTick() {
        while (TOGGLE_KEY.consumeClick()) {
            visible = !visible;
        }
    }

    private static String t(String key, Object... args) {
        return I18n.get("createicecream.hud." + key, args);
    }

    /** GUI layer (above everything else). */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible || mc.level == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        List<Row> rows = new ArrayList<>();
        List<Row> notes = new ArrayList<>();
        collect(rows, notes);

        Font font = mc.font;
        String title = "Create IceCream " + CreateIceCream.version();
        String status = notes.stream().anyMatch(r -> r.color == BAD) ? t("status.problem")
                : notes.isEmpty() ? t("status.ok") : t("status.notes");
        int statusColor = notes.stream().anyMatch(r -> r.color == BAD) ? BAD : notes.isEmpty() ? GOOD : WARN;

        int labelW = 0, valueW = 0;
        for (Row r : rows) {
            labelW = Math.max(labelW, font.width(r.label));
            valueW = Math.max(valueW, font.width(r.value));
        }
        int width = Math.max(labelW + GAP + valueW, font.width(title) + GAP + font.width(status));
        for (Row n : notes) {
            width = Math.max(width, font.width(n.value));
        }
        int line = font.lineHeight + 2;
        int height = line * (1 + rows.size()) + (notes.isEmpty() ? 0 : 4 + line * notes.size());
        int boxW = width + PAD * 2;
        int boxH = height + PAD * 2 - 2;

        float scale = OptConfig.hudScale;
        int screenW = (int) (g.guiWidth() / scale);
        int screenH = (int) (g.guiHeight() / scale);
        int margin = 4;
        int x, y;
        switch (OptConfig.hudPosition) {
            case TOP_RIGHT -> {
                x = screenW - boxW - margin;
                y = margin;
            }
            case BOTTOM_LEFT -> {
                x = margin;
                y = screenH - boxH - margin - 40; // stay clear of the chat input / hotbar row
            }
            case BOTTOM_RIGHT -> {
                x = screenW - boxW - margin;
                y = screenH - boxH - margin - 40;
            }
            default -> {
                x = margin;
                y = margin;
            }
        }

        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);
        g.fill(x, y, x + boxW, y + boxH, BG);
        g.fill(x, y, x + boxW, y + 1, BORDER);

        int cy = y + PAD;
        g.drawString(font, title, x + PAD, cy, TITLE, true);
        g.drawString(font, status, x + boxW - PAD - font.width(status), cy, statusColor, true);
        cy += line;
        for (Row r : rows) {
            g.drawString(font, r.label, x + PAD, cy, LABEL, false);
            g.drawString(font, r.value, x + PAD + labelW + GAP, cy, r.color, true);
            cy += line;
        }
        if (!notes.isEmpty()) {
            cy += 2;
            g.fill(x + PAD, cy - 2, x + boxW - PAD, cy - 1, 0x40FFFFFF);
            cy += 2;
            for (Row n : notes) {
                g.drawString(font, n.value, x + PAD, cy, n.color, true);
                cy += line;
            }
        }
        g.pose().popPose();
    }

    private static void collect(List<Row> rows, List<Row> notes) {
        if (!OptConfig.enabled) {
            notes.add(new Row("", t("note.disabled"), WARN));
            return;
        }
        int frames = Math.max(1, OptStats.framesLastSecond);
        boolean flywheel = flywheelOn();
        boolean shaders = ModCompat.isShaderPackInUse();
        boolean sodium = IceCreamMixinPlugin.isSodiumPresent();

        rows.add(new Row(t("row.path"), flywheel ? t("path.flywheel") : shaders ? t("path.shaders") : t("path.cpu"),
                flywheel ? DIM : GOOD));
        rows.add(new Row(t("row.preset"), OptConfig.preset.name(), VALUE));

        String walls = CullingManager.berCullingActive()
                ? t("val.walls", OptStats.berCulledPerSec / frames)
                : ModCompat.isEntityCullingLoaded() ? t("val.wallsEntityCulling") : t("val.off");
        rows.add(new Row(t("row.machines"), t("val.machines", OptStats.berRenderedPerSec / frames,
                OptStats.berFrustumPerSec / frames) + " · " + walls, VALUE));

        if (flywheel) {
            rows.add(new Row(t("row.visuals"), t("val.visuals", OptStats.visualUpdatedPerSec / frames,
                    (OptStats.visualOccludedPerSec + OptStats.visualFrustumPerSec + OptStats.visualDistancePerSec) / frames),
                    VALUE));
        } else if (sodium) {
            rows.add(new Row(t("row.animation"), t("val.animation", CacheStats.throttleReplaysPerSec,
                    CacheStats.throttleCapturesPerSec), value(CacheStats.throttleReplaysPerSec)));
        }
        if (sodium && !flywheel) {
            rows.add(new Row(t("row.cache"), t("val.cache", CacheStats.hitsPerSec, CacheStats.trackHitsPerSec,
                    CacheStats.BYTES.get() / (1024.0 * 1024.0)), value(CacheStats.hitsPerSec + CacheStats.trackHitsPerSec)));
            rows.add(new Row(t("row.threads"), SodiumPart.threads(), VALUE));
        }
        rows.add(new Row(t("row.trains"), t("val.trains", ContraptionCulling.occludedCount, ContraptionCulling.trackedCount),
                value(ContraptionCulling.occludedCount)));
        rows.add(new Row(t("row.details"), t("val.details", DetailLod.skippedItemsPerSec, DetailLod.skippedTextsPerSec),
                value(DetailLod.skippedItemsPerSec + DetailLod.skippedTextsPerSec)));
        if (shaders) {
            rows.add(new Row(t("row.shadows"), t("val.shadows", OptStats.shadowBerSkippedPerSec / frames,
                    OptStats.shadowContraptionSkippedPerSec / frames),
                    value(OptStats.shadowBerSkippedPerSec + OptStats.shadowContraptionSkippedPerSec)));
        }
        rows.add(new Row(t("row.sounds"), t("val.sounds", SoundLimiter.skippedPerSec), value(SoundLimiter.skippedPerSec)));
        rows.add(new Row(t("row.tracer"), t("val.tracer", OptStats.lastPassMs, OptStats.tracked), VALUE));
        int applied = IceCreamMixinPlugin.APPLIED.size();
        int expected = IceCreamMixinPlugin.expectedCount();
        rows.add(new Row(t("row.hooks"), applied >= expected ? t("val.hooksOk", applied)
                : t("val.hooksWaiting", applied, expected - applied), applied >= expected ? GOOD : VALUE));

        // things that need attention
        if (!sodium) {
            notes.add(new Row("", t("note.noSodium"), WARN));
        } else if (!"on".equals(IceCreamMixinPlugin.fastRendererStatus)) {
            notes.add(new Row("", t("note.fastRenderer", IceCreamMixinPlugin.fastRendererStatus), BAD));
        } else if (!OptConfig.fastRenderer) {
            notes.add(new Row("", t("note.fastRendererOff"), WARN));
        }
        if (sodium && SodiumPart.broken()) {
            notes.add(new Row("", t("note.threadsFellBack"), WARN));
        }
        if (flywheel) {
            notes.add(new Row("", t("note.flywheel"), DIM));
        }
    }

    private static int value(long v) {
        return v > 0 ? VALUE : DIM;
    }

    private static boolean flywheelOn() {
        try {
            return BackendManager.isBackendOn();
        } catch (Throwable t) {
            return false;
        }
    }

    /** One-line summary for the F3 screen. */
    public static String f3Line() {
        String key = TOGGLE_KEY.getTranslatedKeyMessage().getString();
        return "Create IceCream " + CreateIceCream.version() + ": " + (OptConfig.enabled ? OptConfig.preset.name() : "OFF")
                + " · " + t("f3.hint", key);
    }

    /** Keeps Sodium-dependent classes from loading without Sodium. */
    private static final class SodiumPart {
        static String threads() {
            if (com.createicecream.render.fast.ParallelMeshes.isBroken()) {
                return t("val.threadsFellBack");
            }
            int n = com.createicecream.render.fast.ParallelMeshes.threads;
            return n == 0 ? t("val.off") : t("val.threads", n, com.createicecream.render.fast.ParallelMeshes.jobsPerSec);
        }

        static boolean broken() {
            return com.createicecream.render.fast.ParallelMeshes.isBroken();
        }
    }
}
