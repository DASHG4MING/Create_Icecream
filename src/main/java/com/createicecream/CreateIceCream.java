package com.createicecream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.createicecream.compat.ModCompat;
import com.createicecream.config.OptConfig;
import com.createicecream.culling.CullingManager;
import com.createicecream.debug.DebugOverlay;
import com.createicecream.debug.StatsHud;
import com.createicecream.render.cache.ReloadGeneration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import com.createicecream.debug.OptStats;
import com.createicecream.render.cache.CacheStats;
import com.createicecream.render.cache.FrameClock;
import com.createicecream.visual.KineticRenderTypeCache;
import com.createicecream.visual.VisualUpdateGate;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import com.createicecream.culling.ContraptionCulling;
import com.createicecream.mixinplugin.IceCreamMixinPlugin;
import com.createicecream.render.lod.DetailLod;
import com.createicecream.sound.SoundLimiter;

@Mod(value = CreateIceCream.MOD_ID, dist = Dist.CLIENT)
public final class CreateIceCream {
    public static final String MOD_ID = "createicecream";
    public static final Logger LOGGER = LoggerFactory.getLogger("CreateIceCream");

    private static String version = "?";

    /** The mod's version, for the statistics panel. */
    public static String version() {
        return version;
    }

    public CreateIceCream(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.CLIENT, OptConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modBus.addListener(CreateIceCream::onClientSetup);
        modBus.addListener(CreateIceCream::onConfigLoad);
        modBus.addListener(CreateIceCream::onConfigReload);
        modBus.addListener(CreateIceCream::onRegisterKeys);
        modBus.addListener(CreateIceCream::onRegisterGuiLayers);
        modBus.addListener(CreateIceCream::onRegisterReloadListeners);

        NeoForge.EVENT_BUS.addListener(CreateIceCream::onRenderFramePre);
        NeoForge.EVENT_BUS.addListener(CreateIceCream::onRenderFramePost);
        NeoForge.EVENT_BUS.addListener(CreateIceCream::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(DebugOverlay::onDebugText);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(StatsHud.TOGGLE_KEY);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MOD_ID, "stats"), StatsHud::render);
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            // new texture atlas: cached meshes that contain UVs must be rebuilt
            ReloadGeneration.bump();
            KineticRenderTypeCache.clear();
        });
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        ModCompat.init();
        LOGGER.info("Create IceCream ready (BER culling mode: {}, Entity Culling present: {})",
                OptConfig.berCullingMode, ModCompat.isEntityCullingLoaded());
    }

    private static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == OptConfig.SPEC) {
            OptConfig.bake();
        }
    }

    private static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == OptConfig.SPEC) {
            OptConfig.bake();
            KineticRenderTypeCache.clear();
            CullingManager.onConfigChanged();
        }
    }

    private static void onRenderFramePre(RenderFrameEvent.Pre event) {
        OptStats.onFrame();
        FrameClock.onFrame();
        CacheStats.onFrame();
        CullingManager.onRenderFrame();
        VisualUpdateGate.onRenderFrame();
        long now = System.nanoTime();
        if (now - lastSecond >= 1_000_000_000L) {
            lastSecond = now;
            DetailLod.onSecond();
            SoundLimiter.skippedPerSec = SoundLimiter.skipped;
            SoundLimiter.skipped = 0;
            ContraptionCulling.culledPerSec = ContraptionCulling.culled;
            ContraptionCulling.culled = 0;
            if (IceCreamMixinPlugin.isSodiumPresent()) {
                SodiumOnly.onSecond();
            }
        }
    }

    private static long lastSecond = System.nanoTime();

    private static void onRenderFramePost(RenderFrameEvent.Post event) {
        if (IceCreamMixinPlugin.isSodiumPresent()) {
            SodiumOnly.endFrame();
        }
    }

    /** Keeps classes that reference Sodium from being loaded when Sodium isn't installed. */
    private static final class SodiumOnly {
        static void onSecond() {
            com.createicecream.render.fast.ParallelMeshes.onSecond();
        }

        static void endFrame() {
            com.createicecream.render.fast.ParallelMeshes.endFrame();
        }
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        CullingManager.onClientTick();
        SoundLimiter.onTick();
        StatsHud.onTick();
    }
}
