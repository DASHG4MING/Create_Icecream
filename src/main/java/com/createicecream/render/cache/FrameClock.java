package com.createicecream.render.cache;

/** Render-thread frame counter (incremented once per frame, before the level renders). */
public final class FrameClock {
    public static int frame;

    private FrameClock() {
    }

    public static void onFrame() {
        frame++;
        LevelLightCache.clear();
    }
}
