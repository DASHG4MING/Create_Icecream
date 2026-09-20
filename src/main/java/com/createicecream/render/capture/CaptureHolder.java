package com.createicecream.render.capture;

import org.jetbrains.annotations.Nullable;

/** Duck interface added to BlockEntity and Entity: per-pass throttle captures (0 = main view, 1 = shadow pass). */
public interface CaptureHolder {
    @Nullable
    CaptureSlot[] icecream$captureSlots();

    void icecream$setCaptureSlots(@Nullable CaptureSlot[] slots);
}
