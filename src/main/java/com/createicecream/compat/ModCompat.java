package com.createicecream.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.fml.ModList;

/**
 * Soft integrations, all resolved reflectively so no compile/runtime dependency is needed.
 */
public final class ModCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("CreateIceCream/Compat");

    private static boolean entityCullingLoaded;
    private static MethodHandle irisIsShadowPass; // bound to IrisApi.getInstance()
    private static MethodHandle irisShaderPackInUse;
    private static volatile boolean fastShadowCheck = true;

    private ModCompat() {
    }

    public static void init() {
        ModList mods = ModList.get();
        entityCullingLoaded = mods.isLoaded("entityculling");
        if (entityCullingLoaded) {
            LOGGER.info("Entity Culling detected: BER occlusion culling defaults to Entity Culling (berCulling = AUTO).");
        }

        if (mods.isLoaded("iris") || mods.isLoaded("oculus")) {
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                irisIsShadowPass = MethodHandles.publicLookup()
                        .findVirtual(api, "isRenderingShadowPass", MethodType.methodType(boolean.class))
                        .bindTo(instance);
                irisShaderPackInUse = MethodHandles.publicLookup()
                        .findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class))
                        .bindTo(instance);
                LOGGER.info("Iris API found: culling is disabled during the shadow pass.");
            } catch (Throwable t) {
                LOGGER.warn("Iris detected but its API could not be resolved; shadow pass detection disabled.", t);
            }
        }
    }

    /** True while an Iris shader pack is active (Flywheel then disables itself). */
    public static boolean isShaderPackInUse() {
        MethodHandle handle = irisShaderPackInUse;
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact();
        } catch (Throwable t) {
            irisShaderPackInUse = null;
            return false;
        }
    }

    public static boolean isEntityCullingLoaded() {
        return entityCullingLoaded;
    }

    /**
     * Block entities hidden from the camera can still cast shadows, so never cull while a shader pack
     * renders its shadow map.
     */
    public static boolean isRenderingShadowPass() {
        if (fastShadowCheck && com.createicecream.render.fast.IrisCompat.IS_IRIS_INSTALLED) {
            try {
                return com.createicecream.render.fast.IrisCompat.isShadowPass(); // plain static field read
            } catch (Throwable t) {
                fastShadowCheck = false; // Iris internals changed: use the public API below from now on
            }
        }
        MethodHandle handle = irisIsShadowPass;
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invokeExact();
        } catch (Throwable t) {
            irisIsShadowPass = null;
            return false;
        }
    }
}
