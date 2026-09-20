package com.createicecream.render.cache;

/** Bumped on every resource reload (texture atlas rebuilt): caches that store UVs compare against it. */
public final class ReloadGeneration {
    public static volatile int value;

    private ReloadGeneration() {
    }

    public static void bump() {
        value++;
    }
}
