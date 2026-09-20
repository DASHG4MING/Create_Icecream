package com.createicecream.render.capture;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.renderer.RenderType;

/**
 * Whether the GPU back-face culls a render type. Replayed captures of such render types can drop faces that
 * point away from the camera on the CPU (the GPU would discard them anyway), which halves what is copied
 * and uploaded. Render types without culling (flat planes, plants, some entity layers) are never touched.
 * <p>
 * Read once per render type by reflection (NeoForge runs with Mojang names); if anything about it fails,
 * the answer is "doesn't cull", i.e. nothing is dropped.
 */
final class RenderTypeCulling {
    private static final Logger LOGGER = LoggerFactory.getLogger("CreateIceCream/RenderTypes");
    private static final IdentityHashMap<RenderType, Boolean> CACHE = new IdentityHashMap<>();
    private static Field stateField, cullField, enabledField;
    private static boolean broken;

    private RenderTypeCulling() {
    }

    /** Render thread. */
    static boolean culls(RenderType type) {
        Boolean cached = CACHE.get(type);
        if (cached == null) {
            cached = lookup(type);
            CACHE.put(type, cached);
        }
        return cached;
    }

    private static boolean lookup(RenderType type) {
        if (broken) {
            return false;
        }
        try {
            Class<?> composite = Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType");
            if (!composite.isInstance(type)) {
                return false;
            }
            if (stateField == null) {
                stateField = composite.getDeclaredField("state");
                stateField.setAccessible(true);
            }
            Object state = stateField.get(type);
            if (cullField == null) {
                cullField = state.getClass().getDeclaredField("cullState");
                cullField.setAccessible(true);
            }
            Object cull = cullField.get(state);
            if (enabledField == null) {
                Class<?> c = cull.getClass();
                while (c != null && enabledField == null) {
                    try {
                        enabledField = c.getDeclaredField("enabled");
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();
                    }
                }
                if (enabledField == null) {
                    throw new NoSuchFieldException("enabled");
                }
                enabledField.setAccessible(true);
            }
            return enabledField.getBoolean(cull);
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("Can't read render type culling state; replays will not drop back faces", t);
            return false;
        }
    }
}
