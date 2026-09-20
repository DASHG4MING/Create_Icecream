/*
 * Derived from CreateBetterFps by MoePus (https://github.com/MoePus/CreateBetterFps), MIT License,
 * Copyright (c) 2025 MoePus. See THIRD_PARTY_LICENSE_CreateBetterFps.txt. Modified for Create IceCream.
 */
package com.createicecream.render.fast;

import java.lang.reflect.Field;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexFormat;

import dev.engine_room.flywheel.lib.util.ShadersModHelper;

/**
 * All direct references to Iris classes live behind {@link #IS_IRIS_INSTALLED} checks, so this class (and
 * the renderer) load fine without Iris.
 */
public final class IrisCompat {
    private IrisCompat() {
    }

    public static final boolean IS_IRIS_INSTALLED = ShadersModHelper.IS_IRIS_LOADED;

    static VertexFormat GetTerrainVertexFormat() {
        return irisFormat("TERRAIN");
    }

    static VertexFormat GetEntityVertexFormat() {
        return irisFormat("ENTITY");
    }

    private static VertexFormat irisFormat(String name) {
        try {
            Class<?> irisVertexFormats = Class.forName("net.irisshaders.iris.vertices.IrisVertexFormats");
            Field field = irisVertexFormats.getDeclaredField(name);
            return (VertexFormat) field.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static int packFrame = -1;
    private static boolean packInUse;

    /** Whether a shader pack is active; asked once per frame (render thread). */
    public static boolean shaderPackInUse() {
        int frame = com.createicecream.render.cache.FrameClock.frame;
        if (frame != packFrame) {
            packFrame = frame;
            packInUse = ShadersModHelper.isShaderPackInUse();
        }
        return packInUse;
    }

    /** True while Iris renders its shadow map. Safe to call without Iris. */
    public static boolean isShadowPass() {
        return IS_IRIS_INSTALLED && Iris.shadowActive();
    }

    public static Matrix4f getShadowMV() {
        return Iris.shadowModelView();
    }

    /** Isolated so the Iris classes are only resolved when Iris is present. */
    private static final class Iris {
        static boolean shadowActive() {
            return net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE;
        }

        static Matrix4f shadowModelView() {
            return net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW;
        }
    }
}
