package com.createicecream.sound;

import java.util.HashMap;

import com.createicecream.config.OptConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Starting a sound makes the render thread wait for the sound engine to hand out a channel. A factory full
 * of steam engines starts dozens of identical boiler sounds every tick (Create plays one per engine), and
 * with Sound Physics Remastered each start is even more expensive: that shows up as micro-stutters.
 * <p>
 * For one-shot sounds from Create and its addons only, this skips starts that can't be heard (farther than
 * {@code sounds.cullDistance}) and more than {@code sounds.maxSamePerTick} copies of the same sound in one
 * tick (they would play on top of each other anyway). Looping and moving sounds are never touched.
 */
public final class SoundLimiter {
    private static final HashMap<ResourceLocation, int[]> COUNTS = new HashMap<>();
    public static long skipped;
    public static volatile long skippedPerSec;

    private SoundLimiter() {
    }

    /** Client tick: new budget. */
    public static void onTick() {
        if (!COUNTS.isEmpty()) {
            COUNTS.clear();
        }
    }

    public static boolean shouldSkip(SoundInstance sound) {
        if (!OptConfig.enabled || !OptConfig.soundLimiter) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || sound instanceof TickableSoundInstance || sound.isLooping() || sound.isRelative()
                || sound.getAttenuation() == SoundInstance.Attenuation.NONE) {
            return false;
        }
        ResourceLocation id = sound.getLocation();
        if (id == null || !id.getNamespace().startsWith("create")) {
            return false;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dx = sound.getX() - cam.x, dy = sound.getY() - cam.y, dz = sound.getZ() - cam.z;
        double max = OptConfig.soundCullDistance;
        if (dx * dx + dy * dy + dz * dz > max * max) {
            skipped++;
            return true;
        }
        int[] count = COUNTS.computeIfAbsent(id, k -> new int[1]);
        if (++count[0] > OptConfig.soundMaxSamePerTick) {
            skipped++;
            return true;
        }
        return false;
    }
}
