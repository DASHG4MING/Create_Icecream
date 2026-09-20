package com.createicecream.render.capture;

/**
 * Set right before a throttled Create BER runs, so the SafeBlockEntityRenderer hook doesn't repeat the cull
 * check the throttle just did. Kept free of Sodium references: the BER hook loads it even without Sodium.
 */
public final class CullHandoff {
    private static boolean prechecked;
    /** For the F3 overlay: proves the dispatcher hooks are really being called (no Sodium classes here). */
    public static volatile boolean berHookSeen, entityHookSeen;

    private CullHandoff() {
    }

    public static void mark() {
        prechecked = true;
    }

    /** @return true (once) if the cull check for the BER that is about to run was already done */
    public static boolean consume() {
        boolean b = prechecked;
        prechecked = false;
        return b;
    }

    public static void clear() {
        prechecked = false;
    }
}
