package com.createicecream.render.lod;

import com.createicecream.config.OptConfig;

/**
 * Distance level of detail for Create machines and contraptions: beyond {@code lod.detailDistance} the
 * small extras a renderer draws with vanilla item / text rendering (items on belts, depots, deployers,
 * arms, basins; text on display boards, clipboards, signs on trains) are skipped. They are a few pixels at
 * that distance but each one goes through the full item model pipeline. The machines themselves, fluids
 * and everything else are untouched.
 * <p>
 * The flag is only set while one of Create's renderers is running (render thread), so the GUI, the hand,
 * item frames and every other mod are never affected.
 */
public final class DetailLod {
    public static boolean skipDetails;
    public static long skippedItems, skippedTexts;
    public static volatile long skippedItemsPerSec, skippedTextsPerSec;

    private DetailLod() {
    }

    /** Whether something at this squared distance from the camera draws without details. */
    public static boolean farEnough(double distSq, boolean shadowPass) {
        if (!OptConfig.enabled || !OptConfig.lodEnabled) {
            return false;
        }
        double max = shadowPass ? OptConfig.lodShadowDetailDistanceSq : OptConfig.lodDetailDistanceSq;
        return max > 0 && distSq > max;
    }

    public static void onSecond() {
        skippedItemsPerSec = skippedItems;
        skippedTextsPerSec = skippedTexts;
        skippedItems = skippedTexts = 0;
    }
}
