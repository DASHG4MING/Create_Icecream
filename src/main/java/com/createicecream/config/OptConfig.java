package com.createicecream.config;

import java.util.List;
import java.util.Set;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config. Values are copied into plain static fields ({@link #bake()}) so that the render thread,
 * Flywheel's worker threads and the culling thread can read them without touching the config system.
 */
public final class OptConfig {
    public static final ModConfigSpec SPEC;

    /** One-click profiles. Anything but CUSTOM overrides the distance / framerate settings below. */
    public enum Preset {
        /** Animations smooth up to far away; only far details and shadows are simplified. */
        QUALITY,
        /** The defaults: full smoothness close by, reduced far away. */
        BALANCED,
        /** Maximum FPS: lower animation rates, shorter detail and shadow distances. */
        PERFORMANCE,
        /** Use exactly the values set below. */
        CUSTOM
    }

    public enum HudPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public enum BerCullingMode {
        /** Cull, unless Entity Culling is installed (it already culls every BER through the vanilla dispatcher). */
        AUTO,
        ON,
        OFF
    }

    // ---- spec entries ----
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.EnumValue<Preset> PRESET;
    private static final ModConfigSpec.EnumValue<HudPosition> HUD_POSITION;
    private static final ModConfigSpec.DoubleValue HUD_SCALE;

    private static final ModConfigSpec.BooleanValue OCCLUSION_ENABLED;
    private static final ModConfigSpec.EnumValue<BerCullingMode> OCCLUSION_BER_MODE;
    private static final ModConfigSpec.BooleanValue OCCLUSION_VISUALS;
    private static final ModConfigSpec.IntValue OCCLUSION_MAX_DISTANCE;
    private static final ModConfigSpec.IntValue OCCLUSION_MAX_BOX_SIZE;
    private static final ModConfigSpec.DoubleValue OCCLUSION_BOX_PADDING;
    private static final ModConfigSpec.IntValue OCCLUSION_HYSTERESIS_MS;
    private static final ModConfigSpec.IntValue OCCLUSION_THREAD_SLEEP_MS;
    private static final ModConfigSpec.BooleanValue OCCLUSION_LEAVES_OPAQUE;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> OCCLUSION_OFFSCREEN_ALLOW;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> OCCLUSION_BLACKLIST;
    private static final ModConfigSpec.BooleanValue BER_FRUSTUM;
    private static final ModConfigSpec.BooleanValue OCCLUDE_CONTRAPTIONS;
    private static final ModConfigSpec.BooleanValue LOD_ENABLED;
    private static final ModConfigSpec.IntValue LOD_DETAIL_DISTANCE;
    private static final ModConfigSpec.IntValue LOD_SHADOW_DETAIL_DISTANCE;
    private static final ModConfigSpec.BooleanValue SOUND_LIMITER;
    private static final ModConfigSpec.IntValue SOUND_CULL_DISTANCE;
    private static final ModConfigSpec.IntValue SOUND_MAX_SAME;
    private static final ModConfigSpec.IntValue WORKER_THREADS;

    private static final ModConfigSpec.BooleanValue ANIM_ENABLED;
    private static final ModConfigSpec.DoubleValue ANIM_FULL_RATE_DISTANCE;
    private static final ModConfigSpec.DoubleValue ANIM_MID_DISTANCE;
    private static final ModConfigSpec.IntValue ANIM_MID_FPS;
    private static final ModConfigSpec.IntValue ANIM_FAR_FPS;
    private static final ModConfigSpec.BooleanValue ANIM_THROTTLE_BERS;
    private static final ModConfigSpec.BooleanValue ANIM_THROTTLE_CONTRAPTIONS;
    private static final ModConfigSpec.BooleanValue FRUSTUM_ENABLED;
    private static final ModConfigSpec.DoubleValue FRUSTUM_PADDING;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> MANAGED_PACKAGES;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> NO_FRUSTUM_CLASSES;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> UNGATED_CLASSES;

    private static final ModConfigSpec.IntValue SHADOW_MAX_DISTANCE;
    private static final ModConfigSpec.IntValue SHADOW_ANIM_FPS;
    private static final ModConfigSpec.BooleanValue FINITE_TRACK_BOUNDS;

    private static final ModConfigSpec.BooleanValue MODEL_CACHE;
    private static final ModConfigSpec.BooleanValue TRACK_CACHE;
    private static final ModConfigSpec.IntValue MODEL_CACHE_MAX_MB;
    private static final ModConfigSpec.IntValue MODEL_CACHE_LIGHT_REFRESH_MS;
    private static final ModConfigSpec.BooleanValue MODEL_CACHE_SHADOW_PASS;
    private static final ModConfigSpec.DoubleValue MODEL_CACHE_LIGHT_STEP;
    private static final ModConfigSpec.BooleanValue FAST_RENDERER;
    private static final ModConfigSpec.BooleanValue SHADOW_CPU_CULLING;

    private static final ModConfigSpec.BooleanValue RENDER_TYPE_CACHE;
    private static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;

    // ---- baked values (read from any thread) ----
    public static volatile boolean enabled = true;
    public static volatile Preset preset = Preset.BALANCED;
    public static volatile HudPosition hudPosition = HudPosition.TOP_LEFT;
    public static volatile float hudScale = 1.0f;

    public static volatile boolean occlusionEnabled = true;
    public static volatile BerCullingMode berCullingMode = BerCullingMode.AUTO;
    public static volatile boolean occlusionForVisuals = true;
    public static volatile double occlusionMaxDistanceSq = 64 * 64;
    public static volatile double occlusionMaxBoxSize = 16;
    public static volatile double occlusionBoxPadding = 0.25;
    public static volatile long occlusionHysteresisNanos = 1_000_000_000L;
    public static volatile int occlusionThreadSleepMs = 10;
    public static volatile boolean leavesOpaque = false;
    public static volatile Set<String> offscreenAllow = Set.of();
    public static volatile Set<String> occlusionBlacklist = Set.of();
    public static volatile boolean berFrustumCulling = true;
    public static volatile boolean occludeContraptions = true;
    public static volatile boolean lodEnabled = true;
    public static volatile double lodDetailDistanceSq = 48 * 48;
    public static volatile double lodShadowDetailDistanceSq = 24 * 24;
    public static volatile boolean soundLimiter = true;
    public static volatile double soundCullDistance = 48;
    public static volatile int soundMaxSamePerTick = 4;
    public static volatile int workerThreads = -1;

    public static volatile boolean animEnabled = true;
    public static volatile double animFullRateDistanceSq = 10 * 10;
    public static volatile double animMidDistanceSq = 32 * 32;
    public static volatile int animMidFps = 30;
    public static volatile int animFarFps = 15;
    public static volatile boolean animThrottleBers = true;
    public static volatile boolean animThrottleContraptions = true;
    public static volatile boolean frustumEnabled = true;
    public static volatile double frustumPadding = 1.0;
    public static volatile List<String> managedPackages = List.of("com.simibubi.create.");
    public static volatile Set<String> noFrustumClasses = Set.of();
    public static volatile Set<String> ungatedClasses = Set.of();

    public static volatile double shadowMaxDistanceSq = 32 * 32; // 0 = unlimited
    public static volatile int shadowAnimFps = 30; // 0 = off
    public static volatile boolean finiteTrackBounds = true;

    public static volatile boolean modelCache = true;
    public static volatile boolean trackCache = true;
    public static volatile long modelCacheMaxBytes = 256L * 1024 * 1024;
    public static volatile long modelCacheLightRefreshNanos = 1_000_000_000L;
    public static volatile boolean modelCacheInShadowPass = false;
    public static volatile float modelCacheLightStep = 1.5f;
    public static volatile boolean fastRenderer = true;
    public static volatile boolean shadowCpuFaceCulling = false;

    public static volatile boolean renderTypeCache = true;
    public static volatile boolean debugOverlay = true;

    /** Incremented on every bake so caches derived from config can invalidate themselves. */
    public static volatile int generation = 0;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        ENABLED = b.comment("Master switch. When false the mod does nothing (all hooks become pass-through).")
                .define("enabled", true);
        PRESET = b.comment("Quick setup. QUALITY / BALANCED / PERFORMANCE set the animation framerates and the detail, shadow and",
                        "sound distances for you. Choose CUSTOM to use your own values from the sections below.")
                .defineEnum("preset", Preset.BALANCED);

        b.comment("Statistics panel (toggle with the key set in Controls, F7 by default).").push("hud");
        HUD_POSITION = b.comment("Screen corner of the statistics panel.")
                .defineEnum("position", HudPosition.TOP_LEFT);
        HUD_SCALE = b.comment("Size of the statistics panel.")
                .defineInRange("scale", 1.0, 0.5, 2.0);
        b.pop();

        b.comment("Asynchronous occlusion culling for Create block entities.",
                "Visibility is ray-traced against opaque blocks on a background thread (similar to Entity Culling),",
                "so the render thread only reads a boolean.").push("occlusion");
        OCCLUSION_ENABLED = b.comment("Enable the background occlusion tracer.")
                .translation("createicecream.configuration.occlusion.enabled")
                .define("enabled", true);
        OCCLUSION_BER_MODE = b.comment("Skip Create block entity renderers (BERs) that are hidden behind blocks.",
                        "BERs draw items on belts/depots, fluids, filters, text, and ALL kinetic geometry when Flywheel is",
                        "off (e.g. with a shader pack). AUTO = ON unless Entity Culling is installed, because Entity Culling",
                        "already culls every BER and running both would trace the same blocks twice.")
                .defineEnum("berCulling", BerCullingMode.AUTO);
        OCCLUSION_VISUALS = b.comment("Skip per-frame updates of Create's CPU-animated Flywheel visuals while they are occluded.",
                        "The instanced geometry itself stays on the GPU (Flywheel's indirect backend already Hi-Z culls it).")
                .define("gateVisualUpdates", true);
        OCCLUSION_MAX_DISTANCE = b.comment("Only trace block entities closer than this many blocks (vanilla BE render distance is 64).")
                .defineInRange("maxDistance", 64, 8, 256);
        OCCLUSION_MAX_BOX_SIZE = b.comment("Render bounding boxes larger than this (in any axis) are never culled.")
                .defineInRange("maxBoxSize", 16, 1, 64);
        OCCLUSION_BOX_PADDING = b.comment("Extra margin (blocks) added around each render box before tracing. Larger = fewer pop-ins, less culling.")
                .defineInRange("boxPadding", 0.25, 0.0, 2.0);
        OCCLUSION_HYSTERESIS_MS = b.comment("Once seen, a block entity stays un-culled for at least this long (prevents flicker).")
                .defineInRange("visibleHoldMs", 1000, 0, 10000);
        OCCLUSION_THREAD_SLEEP_MS = b.comment("Sleep between tracing passes of the culling thread, in milliseconds.")
                .defineInRange("threadSleepMs", 10, 1, 250);
        OCCLUSION_LEAVES_OPAQUE = b.comment("Treat leaves as opaque occluders (more culling in forests, may cause pop-in with fancy leaves).")
                .define("leavesAreOpaque", false);
        OCCLUSION_OFFSCREEN_ALLOW = b.comment("Block entity types whose renderer says 'render off-screen' but which declare a correct render",
                        "bounding box, so they are safe to frustum-cull and occlusion-cull. Everything else with",
                        "shouldRenderOffScreen=true (weighted ejectors, elevator pulleys, schematicannons, other mods) is never culled.")
                .defineListAllowEmpty("trustedRenderBoxes",
                        List.of("create:mechanical_press", "create:mechanical_mixer", "create:mechanical_arm",
                                "create:belt", "create:chain_conveyor", "create:fluid_tank", "create:creative_fluid_tank",
                                "create:track", "create:track_station", "create:rope_pulley", "create:hose_pulley",
                                "create:flap_display"),
                        () -> "create:", o -> o instanceof String);
        BER_FRUSTUM = b.comment("Skip Create block entity renderers that are outside the camera's view.",
                        "Vanilla/NeoForge already do this, but Sodium's loop for 'render off-screen' block entities doesn't,",
                        "so with Sodium every Create press, belt, arm, chain conveyor, tank and curved track is drawn every",
                        "frame even when it is behind you.")
                .define("berFrustumCulling", true);
        OCCLUDE_CONTRAPTIONS = b.comment("Also hide trains and other Create contraptions that are completely behind terrain (tunnels, hills,",
                        "walls). Their renderer still runs so couplings stay attached; only the drawing is skipped. Never applied to",
                        "shadows. Needs Sodium.")
                .define("contraptions", true);
        OCCLUSION_BLACKLIST = b.comment("Block entity types that must never be occlusion-culled.")
                .defineListAllowEmpty("blacklist", List.of(), () -> "create:", o -> o instanceof String);
        b.pop();

        b.comment("Dynamic animation framerate. Without shaders it gates Create's CPU-animated Flywheel visuals; with a shader",
                "pack (Flywheel off) it throttles Create's block entity renderers and contraptions instead (see below).",
                "Rotating shafts/gears/cogs are animated by Flywheel's vertex shader and cost no CPU per frame,",
                "so they are unaffected and keep full smoothness. This targets visuals that recompute transforms",
                "every frame on the CPU: presses, mixers, deployers, arms, funnels, tunnels, gauges, steam engines,",
                "bearings, pulleys, valves, packagers, frogports, etc.").push("animation");
        ANIM_ENABLED = b.comment("Enable the distance-based adaptive animation framerate.")
                .translation("createicecream.configuration.animation.enabled")
                .define("enabled", true);
        ANIM_FULL_RATE_DISTANCE = b.comment("Within this distance (blocks) visuals update every frame.")
                .defineInRange("fullRateDistance", 10.0, 0.0, 256.0);
        ANIM_MID_DISTANCE = b.comment("Between fullRateDistance and this distance, animate at 'midFps'; beyond it, at 'farFps'.")
                .defineInRange("midDistance", 32.0, 0.0, 512.0);
        ANIM_MID_FPS = b.comment("Target animation framerate for the middle band. Never throttles below the actual game FPS.")
                .defineInRange("midFps", 30, 1, 240);
        ANIM_FAR_FPS = b.comment("Target animation framerate for the far band.")
                .defineInRange("farFps", 15, 1, 240);
        ANIM_THROTTLE_BERS = b.comment("Also apply the animation framerate to Create block entity renderers (the path used with shader",
                        "packs, where Flywheel is off). Between two updates the last drawn frame is re-used as a finished mesh,",
                        "so a throttled machine costs almost nothing. Needs Sodium.")
                .define("throttleBlockEntityRenderers", true);
        ANIM_THROTTLE_CONTRAPTIONS = b.comment("Also apply the animation framerate to trains and other contraptions. They keep moving smoothly;",
                        "only their internal animation (wheels, turning on curves, moving parts) updates at the lower rate. Needs Sodium.")
                .define("throttleContraptions", true);
        FRUSTUM_ENABLED = b.comment("Skip per-frame updates of visuals that are outside the camera frustum (only Blaze Burners do this in Create 6).")
                .define("frustumGating", true);
        FRUSTUM_PADDING = b.comment("Extra radius (blocks) around a visual's render box when testing against the frustum.")
                .defineInRange("frustumPadding", 1.0, 0.0, 8.0);
        MANAGED_PACKAGES = b.comment("Visual classes whose name starts with one of these prefixes are managed. Add Create addons here",
                        "(e.g. \"com.example.createaddon.\") once you have verified they behave well.")
                .defineListAllowEmpty("managedPackages", List.of("com.simibubi.create."), () -> "com.", o -> o instanceof String);
        NO_FRUSTUM_CLASSES = b.comment("Visual classes (fully-qualified; superclasses match too) whose geometry reaches far outside",
                        "their block and must never be frustum-gated or occlusion-gated.")
                .defineListAllowEmpty("noFrustumGating", List.of(
                        "com.simibubi.create.content.contraptions.pulley.AbstractPulleyVisual",
                        "com.simibubi.create.content.contraptions.elevator.ElevatorPulleyVisual",
                        "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorVisual",
                        "com.simibubi.create.content.logistics.packagePort.frogport.FrogportVisual",
                        "com.simibubi.create.content.trains.bogey.BogeyBlockEntityVisual"
                ), () -> "com.", o -> o instanceof String);
        UNGATED_CLASSES = b.comment("Visual classes that are never gated at all (always updated every frame).")
                .defineListAllowEmpty("neverGate", List.of(), () -> "com.", o -> o instanceof String);
        b.pop();

        b.comment("Shader packs (Iris) render the whole scene a second time from the sun for the shadow map.",
                "With Flywheel off (always the case with shaders) every Create machine, train and curved track is",
                "re-drawn on the CPU for that pass too.").push("shadows");
        SHADOW_MAX_DISTANCE = b.comment("Create block entities and contraptions (trains, elevators, bearings...) farther than this many",
                        "blocks from the camera are left out of the shader shadow map. They are still drawn normally;",
                        "only their shadow disappears. 0 = draw all shadows (vanilla behaviour).")
                .defineInRange("shadowDistance", 32, 0, 512);
        SHADOW_ANIM_FPS = b.comment("Animation framerate of Create machines and trains inside the shadow map, at any distance. Shadows",
                        "are low-resolution and soft, so wheels and moving parts updating e.g. 30 times per second is not",
                        "visible there, but it halves or quarters the cost of the shadow pass. Trains still move smoothly.",
                        "0 = off. Needs Sodium and 'animation.enabled'.")
                .defineInRange("animationFps", 30, 0, 240);
        b.pop();

        b.push("trains");
        FINITE_TRACK_BOUNDS = b.comment("Create gives curved track block entities an INFINITE render box, so every curve within 192",
                        "blocks is re-drawn every frame (and in the shadow pass) even when it is behind you. This computes",
                        "the real box from the curve's geometry so normal frustum culling can skip it. Only matters when",
                        "Flywheel is off (shader packs).")
                .define("finiteTrackBounds", true);
        b.pop();

        b.comment("Fast Create renderer + model cache (needs Sodium; replaces CreateBetterFps).",
                "With a shader pack (Flywheel off), Create rebuilds every model vertex by vertex each frame. Models that",
                "don't change between frames - curved tracks, parked trains, contraptions that aren't moving - are",
                "recorded once and then only copied. Anything that moves or changes is detected and drawn normally.").push("modelCache");
        FAST_RENDERER = b.comment("Use the fast (Sodium) writer for Create models. Turn off to get Create's original per-vertex",
                        "rendering (slower) - useful to check whether a visual glitch comes from this mod. Also disables the caches.")
                .define("fastRenderer", true);
        SHADOW_CPU_CULLING = b.comment("Skip faces pointing towards the sun in the shader shadow pass (CreateBetterFps did this).",
                        "Saves some work, but with shader packs that also cull on the GPU, contraptions and trains lose their shadow.")
                .define("shadowFaceCulling", false);
        MODEL_CACHE = b.comment("Cache finished vertices of models drawn identically frame after frame (contraptions, parked trains).")
                .define("contraptionCache", true);
        TRACK_CACHE = b.comment("Cache whole curved-track meshes (rails, ties, girders).")
                .define("trackCache", true);
        MODEL_CACHE_MAX_MB = b.comment("Stop recording new meshes when the cache uses more than this much memory (MB).")
                .defineInRange("maxMemoryMB", 256, 16, 4096);
        MODEL_CACHE_LIGHT_REFRESH_MS = b.comment("Contraptions lit by the level are re-recorded this often so light changes show up (ms).")
                .defineInRange("lightRefreshMs", 1000, 100, 60000);
        MODEL_CACHE_SHADOW_PASS = b.comment("Also use the cache in the shader shadow pass. Faster, but recorded meshes keep faces the normal",
                        "renderer would cull against the sun, so shadows can look slightly different and flicker on",
                        "trains/contraptions that switch between cached and normal drawing. Off by default.")
                .define("cacheInShadowPass", false);
        WORKER_THREADS = b.comment("Build Create model vertices on this many background threads instead of only the render thread.",
                        "-1 = automatic (about half your CPU cores, at most 4), 0 = off. Needs Sodium.")
                .defineInRange("workerThreads", -1, -1, 8);
        MODEL_CACHE_LIGHT_STEP = b.comment("A moving contraption (e.g. a train on straight track) keeps its cached mesh and is only re-recorded",
                        "for new light values after moving this many blocks. 0 = re-record every frame while moving (old behaviour).")
                .defineInRange("movingLightStep", 1.5, 0.0, 16.0);
        b.pop();

        b.comment("Level of detail: small extras of far-away Create machines and trains.").push("lod");
        LOD_ENABLED = b.comment("Skip items (on belts, depots, deployers, arms, basins...) and text (display boards, clipboards) drawn",
                        "by far-away Create machines and trains. They are a few pixels at that distance. Needs Sodium.")
                .translation("createicecream.configuration.lod.enabled")
                .define("enabled", true);
        LOD_DETAIL_DISTANCE = b.comment("Distance (blocks) beyond which those details are skipped. 0 = never.")
                .defineInRange("detailDistance", 48, 0, 512);
        LOD_SHADOW_DETAIL_DISTANCE = b.comment("Same for the shader shadow map, where small details are invisible much sooner. 0 = never.")
                .defineInRange("shadowDetailDistance", 24, 0, 512);
        b.pop();

        b.comment("Create sounds. Each sound start makes the game wait for the sound engine; a factory full of steam",
                "engines starts dozens of identical boiler sounds per tick, which causes micro-stutters.").push("sounds");
        SOUND_LIMITER = b.comment("Skip one-shot Create sounds that are too far to hear, and extra copies of the same sound in one tick.",
                        "Looping and moving sounds are never touched.")
                .define("limiter", true);
        SOUND_CULL_DISTANCE = b.comment("One-shot Create sounds farther than this (blocks) are not started.")
                .defineInRange("cullDistance", 48, 8, 256);
        SOUND_MAX_SAME = b.comment("At most this many copies of the same Create sound start per tick (the rest would overlap).")
                .defineInRange("maxSamePerTick", 4, 1, 64);
        b.pop();

        b.push("misc");
        RENDER_TYPE_CACHE = b.comment("Cache the chunk render type Create's KineticBlockEntityRenderer looks up for every block entity every",
                        "frame (it allocates a new RandomSource each time). Only matters when Flywheel is off / shaders are on.")
                .define("kineticRenderTypeCache", true);
        DEBUG_OVERLAY = b.comment("Show a one-line Create IceCream status on the F3 screen (details are in the statistics panel).")
                .define("debugOverlay", true);
        b.pop();

        SPEC = b.build();
    }

    private OptConfig() {
    }

    public static void bake() {
        enabled = ENABLED.get();

        occlusionEnabled = OCCLUSION_ENABLED.get();
        berCullingMode = OCCLUSION_BER_MODE.get();
        occlusionForVisuals = OCCLUSION_VISUALS.get();
        double maxDist = OCCLUSION_MAX_DISTANCE.get();
        occlusionMaxDistanceSq = maxDist * maxDist;
        occlusionMaxBoxSize = OCCLUSION_MAX_BOX_SIZE.get();
        occlusionBoxPadding = OCCLUSION_BOX_PADDING.get();
        occlusionHysteresisNanos = OCCLUSION_HYSTERESIS_MS.get() * 1_000_000L;
        occlusionThreadSleepMs = OCCLUSION_THREAD_SLEEP_MS.get();
        leavesOpaque = OCCLUSION_LEAVES_OPAQUE.get();
        offscreenAllow = Set.copyOf(OCCLUSION_OFFSCREEN_ALLOW.get());
        occlusionBlacklist = Set.copyOf(OCCLUSION_BLACKLIST.get());
        berFrustumCulling = BER_FRUSTUM.get();
        occludeContraptions = OCCLUDE_CONTRAPTIONS.get();
        double lod = LOD_DETAIL_DISTANCE.get();
        double lodShadow = LOD_SHADOW_DETAIL_DISTANCE.get();
        lodEnabled = LOD_ENABLED.get();
        lodDetailDistanceSq = lod * lod;
        lodShadowDetailDistanceSq = lodShadow * lodShadow;
        soundLimiter = SOUND_LIMITER.get();
        soundCullDistance = SOUND_CULL_DISTANCE.get();
        soundMaxSamePerTick = SOUND_MAX_SAME.get();
        workerThreads = WORKER_THREADS.get();

        animEnabled = ANIM_ENABLED.get();
        double full = ANIM_FULL_RATE_DISTANCE.get();
        double mid = Math.max(full, ANIM_MID_DISTANCE.get());
        animFullRateDistanceSq = full * full;
        animMidDistanceSq = mid * mid;
        animMidFps = ANIM_MID_FPS.get();
        animFarFps = ANIM_FAR_FPS.get();
        animThrottleBers = ANIM_THROTTLE_BERS.get();
        animThrottleContraptions = ANIM_THROTTLE_CONTRAPTIONS.get();
        frustumEnabled = FRUSTUM_ENABLED.get();
        frustumPadding = FRUSTUM_PADDING.get();
        managedPackages = List.copyOf(MANAGED_PACKAGES.get());
        noFrustumClasses = Set.copyOf(NO_FRUSTUM_CLASSES.get());
        ungatedClasses = Set.copyOf(UNGATED_CLASSES.get());

        double shadowMax = SHADOW_MAX_DISTANCE.get();
        shadowMaxDistanceSq = shadowMax * shadowMax;
        shadowAnimFps = SHADOW_ANIM_FPS.get();
        finiteTrackBounds = FINITE_TRACK_BOUNDS.get();

        modelCache = MODEL_CACHE.get();
        trackCache = TRACK_CACHE.get();
        modelCacheMaxBytes = MODEL_CACHE_MAX_MB.get() * 1024L * 1024L;
        modelCacheLightRefreshNanos = MODEL_CACHE_LIGHT_REFRESH_MS.get() * 1_000_000L;
        modelCacheInShadowPass = MODEL_CACHE_SHADOW_PASS.get();
        modelCacheLightStep = MODEL_CACHE_LIGHT_STEP.get().floatValue();
        fastRenderer = FAST_RENDERER.get();
        shadowCpuFaceCulling = SHADOW_CPU_CULLING.get();

        renderTypeCache = RENDER_TYPE_CACHE.get();
        debugOverlay = DEBUG_OVERLAY.get();

        preset = PRESET.get();
        hudPosition = HUD_POSITION.get();
        hudScale = HUD_SCALE.get().floatValue();
        applyPreset(preset);

        generation++;
    }

    /** Overrides the distance / framerate values with a profile (the config file keeps the user's own values). */
    private static void applyPreset(Preset p) {
        switch (p) {
            case QUALITY -> {
                animFullRateDistanceSq = 24 * 24;
                animMidDistanceSq = 64 * 64;
                animMidFps = 60;
                animFarFps = 30;
                shadowAnimFps = 60;
                lodDetailDistanceSq = 96 * 96;
                lodShadowDetailDistanceSq = 48 * 48;
                shadowMaxDistanceSq = 64 * 64;
                soundCullDistance = 64;
            }
            case BALANCED -> {
                animFullRateDistanceSq = 10 * 10;
                animMidDistanceSq = 32 * 32;
                animMidFps = 30;
                animFarFps = 15;
                shadowAnimFps = 30;
                lodDetailDistanceSq = 48 * 48;
                lodShadowDetailDistanceSq = 24 * 24;
                shadowMaxDistanceSq = 32 * 32;
                soundCullDistance = 48;
            }
            case PERFORMANCE -> {
                animFullRateDistanceSq = 8 * 8;
                animMidDistanceSq = 24 * 24;
                animMidFps = 20;
                animFarFps = 10;
                shadowAnimFps = 20;
                lodDetailDistanceSq = 32 * 32;
                lodShadowDetailDistanceSq = 16 * 16;
                shadowMaxDistanceSq = 24 * 24;
                soundCullDistance = 32;
            }
            case CUSTOM -> {
            }
        }
    }
}
