package com.createicecream.culling;

import org.jetbrains.annotations.Nullable;

/** Duck interface on Entity (EntityCaptureMixin): occlusion state, only ever set for Create contraptions. */
public interface ContraptionCullHolder {
    @Nullable
    ContraptionCullState icecream$cullState();

    void icecream$setCullState(ContraptionCullState state);
}
