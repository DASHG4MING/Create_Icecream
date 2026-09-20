package com.createicecream.mixinplugin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.loading.LoadingModList;

/**
 * Records which mixins were applied (for the F3 overlay), and turns the fast renderer + model cache off
 * when Sodium is missing (they write through Sodium's vertex API) or CreateBetterFps is installed (it
 * replaces the same Create classes).
 */
public class IceCreamMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("CreateIceCream/Mixin");
    public static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    /** Mixins this plugin allowed (what should eventually be in {@link #APPLIED}). */
    public static final Set<String> EXPECTED = ConcurrentHashMap.newKeySet();
    /**
     * Mixins whose target class is only loaded once it's first used in a world (a train, a curved track, a
     * Flywheel visual...). Until then they are "waiting", not missing.
     */
    public static final Set<String> LAZY = Set.of("TrackRendererMixin", "BezierConnectionMixin", "TrackBlockEntityMixin",
            "StorageMixin", "AbstractBlockEntityVisualAccessor", "KineticBlockEntityRendererMixin",
            "SafeBlockEntityRendererMixin", "SuperBufferFactoryMixin", "SuperByteBufferBuilderMixin",
            "ShadedBlockSbbBuilderMixin", "FontLodMixin", "ItemRendererLodMixin", "SoundEngineMixin");
    public static volatile String fastRendererStatus = "?";

    private boolean fastRendererAllowed;
    private static boolean sodiumPresent;

    @Override
    public void onLoad(String mixinPackage) {
        boolean sodium = isLoaded("sodium");
        boolean cbf = isLoaded("createbetterfps");
        fastRendererAllowed = sodium && !cbf;
        sodiumPresent = sodium;
        if (!sodium) {
            fastRendererStatus = "off (needs Sodium)";
        } else if (cbf) {
            fastRendererStatus = "off (CreateBetterFps installed - remove it)";
        } else {
            fastRendererStatus = "on";
        }
        LOGGER.info("Fast Create renderer + model cache: {}", fastRendererStatus);
    }

    private static boolean isLoaded(String modId) {
        try {
            return LoadingModList.get().getModFileById(modId) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean apply = decide(mixinClassName);
        if (apply) {
            EXPECTED.add(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
        }
        return apply;
    }

    private boolean decide(String mixinClassName) {
        if (mixinClassName.contains(".fastsbb.") || mixinClassName.endsWith(".TrackRendererMixin")) {
            return fastRendererAllowed;
        }
        if (mixinClassName.endsWith(".BlockEntityRenderDispatcherMixin") || mixinClassName.endsWith(".EntityRenderDispatcherMixin")
                || mixinClassName.endsWith(".BufferBuilderBuildMixin")) {
            return sodiumPresent; // the render throttle copies meshes through Sodium's vertex writer
        }
        return true;
    }

    public static boolean isSodiumPresent() {
        return sodiumPresent;
    }

    public static int expectedCount() {
        // 14 always + 4 fast renderer (Sodium, no CreateBetterFps) + 3 throttle/threads (Sodium)
        return 14 + (fastRendererStatus.equals("on") ? 4 : 0) + (sodiumPresent ? 3 : 0);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        APPLIED.add(simple);
        LOGGER.debug("Applied {} to {}", simple, targetClassName);
    }
}
