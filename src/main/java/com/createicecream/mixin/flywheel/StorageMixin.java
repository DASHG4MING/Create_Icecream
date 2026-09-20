package com.createicecream.mixin.flywheel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.createicecream.culling.CullingManager;
import com.createicecream.visual.VisualUpdateGate;

import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.task.functional.ConsumerWithContext;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;

/**
 * Hooks Flywheel 1.0.x's per-level visual storage
 * ({@code dev.engine_room.flywheel.impl.visualization.storage.Storage}).
 * <p>
 * {@code framePlan()} builds, once per level, the plan that calls {@code SimpleDynamicVisual::beginFrame}
 * for every dynamic visual every frame:
 * <pre>ForEachPlan.of(() -> simpleDynamicVisuals, SimpleDynamicVisual::beginFrame)</pre>
 * We wrap that action with {@link VisualUpdateGate}. Targeted by name because {@code impl} is not API;
 * all injections are optional (defaultRequire = 0) so a Flywheel update degrades to "no optimisation"
 * instead of a crash.
 */
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.storage.Storage", remap = false)
public abstract class StorageMixin {

    @ModifyArg(
            method = "framePlan",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/engine_room/flywheel/lib/task/ForEachPlan;of(Ldev/engine_room/flywheel/lib/task/functional/SupplierWithContext$Ignored;Ldev/engine_room/flywheel/lib/task/functional/ConsumerWithContext;)Ldev/engine_room/flywheel/lib/task/ForEachPlan;"
            ),
            index = 1,
            remap = false
    )
    private ConsumerWithContext<SimpleDynamicVisual, DynamicVisual.Context> icecream$gateFrameUpdates(
            ConsumerWithContext<SimpleDynamicVisual, DynamicVisual.Context> original) {
        return VisualUpdateGate.wrap(original);
    }

    /** A new visual was created for this object: let it run a few real frames before gating. */
    @Inject(method = "add", at = @At("HEAD"), remap = false)
    private void icecream$onAdd(VisualizationContext visualizationContext, Object obj, float partialTick, CallbackInfo ci) {
        CullingManager.onVisualChanged(obj);
    }

    /** Render origin moved: Flywheel recreates every visual without going through {@code add}. */
    @Inject(method = "recreateAll", at = @At("HEAD"), remap = false)
    private void icecream$onRecreateAll(VisualizationContext visualizationContext, float partialTick, CallbackInfo ci) {
        VisualUpdateGate.forceAll(CullingManager.FORCE_FRAMES);
    }

    /** Block state / data changed and the visual was told to update: same treatment. */
    @Inject(method = "update", at = @At("HEAD"), remap = false)
    private void icecream$onUpdate(Object obj, float partialTick, CallbackInfo ci) {
        CullingManager.onVisualChanged(obj);
    }
}
