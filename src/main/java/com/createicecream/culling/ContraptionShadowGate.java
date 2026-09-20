package com.createicecream.culling;

import com.createicecream.compat.ModCompat;
import com.createicecream.config.OptConfig;
import com.createicecream.debug.OptStats;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** Decides whether a Create contraption entity is left out of the shader shadow map. */
public final class ContraptionShadowGate {
    private static final String CONTRAPTION_CLASS = "com.simibubi.create.content.contraptions.AbstractContraptionEntity";
    private static Class<?> contraptionClass;
    private static boolean lookedUp;

    private ContraptionShadowGate() {
    }

    public static boolean shouldSkip(Entity entity) {
        if (!OptConfig.enabled || OptConfig.shadowMaxDistanceSq <= 0) {
            return false;
        }
        Class<?> type = contraption();
        if (type == null || !type.isInstance(entity) || !ModCompat.isRenderingShadowPass()) {
            return false;
        }
        // Distance to the nearest point of the contraption's culling box, so a long train passing close by
        // keeps its shadow even if its origin is far away.
        AABB box = entity.getBoundingBoxForCulling();
        if (!CullingManager.isBeyondShadowDistance(box)) {
            return false;
        }
        OptStats.SHADOW_CONTRAPTION_SKIPPED.increment();
        return true;
    }

    /** True for Create contraption entities (trains, elevators, bearings, minecart contraptions...). */
    public static boolean isContraption(Entity entity) {
        Class<?> type = contraption();
        return type != null && type.isInstance(entity);
    }

    private static Class<?> contraption() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                contraptionClass = Class.forName(CONTRAPTION_CLASS, false, ContraptionShadowGate.class.getClassLoader());
            } catch (Throwable t) {
                contraptionClass = null;
            }
        }
        return contraptionClass;
    }
}
