/*
 * Derived from CreateBetterFps' SodiumByteBuffer by MoePus (https://github.com/MoePus/CreateBetterFps),
 * MIT License, Copyright (c) 2025 MoePus. See THIRD_PARTY_LICENSE_CreateBetterFps.txt.
 *
 * Changes in Create IceCream:
 *  - Iris tangents are computed from the current quad's UVs (the original read the previous quad's UVs).
 *  - The level-light lookup cache is cleared every frame (the original static map was never cleared, so
 *    contraption lighting went stale and the map grew without bound).
 *  - Output caching: a buffer that is drawn with exactly the same transforms and parameters frame after
 *    frame (a parked train, a contraption that isn't moving) records its finished vertices once and then
 *    only copies them, see ModelCache.
 *  - A recording mode without CPU back-face culling, so recorded meshes are valid from any camera position.
 */
package com.createicecream.render.fast;


import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import com.mojang.blaze3d.vertex.VertexConsumer;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import net.caffeinemc.mods.sodium.api.math.MatrixHelper;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.TemplateMesh;
import net.createmod.catnip.theme.Color;
import net.createmod.ponder.mixin.client.accessor.RenderSystemAccessor;


import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.BlockAndTintGetter;
import org.jetbrains.annotations.Nullable;

import com.createicecream.config.OptConfig;
import com.createicecream.render.cache.LevelLightCache;
import org.joml.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.ParametersAreNonnullByDefault;
import java.lang.Math;

@SuppressWarnings("unchecked")
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class IceSuperByteBuffer implements SuperByteBuffer {
    /** True while ModelCache/TrackMeshCache record: no CPU back-face culling, no nested caching. */
    public static boolean RECORDING = false;

    /**
     * Set by ModelCache for models it may cache: they are then always drawn without CPU back-face culling,
     * exactly like their recorded meshes. Otherwise a model that alternates between replayed and freshly
     * drawn frames (a parked train whose pose jitters by one float bit) would show different faces on
     * alternate frames, which flickers with shader packs that disable GPU face culling.
     */
    static boolean NO_CPU_CULL = false;

    /**
     * True while {@code RenderThrottle} captures a whole block entity / contraption into a reusable mesh. The
     * capture is replayed from other camera positions, so nothing may be culled against the current camera.
     */
    public static boolean CAPTURING = false;

    /** Per-pass output cache (0 = main view, 1 = shader shadow pass); allocated on first use. */
    @Nullable
    ModelCache.Slot[] cacheSlots;

    private final TemplateMesh template;
    /** Iris tangents of the untransformed template, 4 floats (x, y, z, handedness) per quad; built on first use. */
    @Nullable
    private float[] localTangents;
    private final int[] shadeSwapVertices;

    // Vertex Position and Normals
    private final PoseStack transforms = new PoseStack();
    private final boolean invertFakeDiffuseNormal;

    // Vertex Coloring
    private int vertexColor; // aabbggrr
    private boolean disableDiffuse;

    // Vertex Texture Coords
    @Nullable
    private SpriteShiftFunc spriteShiftFunc;

    // Vertex Overlay
    private boolean hasCustomOverlay;
    private int overlay;

    // Vertex Light
    private boolean hasCustomLight;
    private int packedLight;
    private boolean useLevelLight;
    @Nullable
    private BlockAndTintGetter levelWithLight;
    @Nullable
    private Matrix4f lightTransform;

    // Reused objects
    private static final Matrix4f modelMat = new Matrix4f();
    private final Matrix3f normalMat = new Matrix3f();
    private final ShiftOutput shiftOutput = new ShiftOutput();
    private static final Vector3f lightDir0 = new Vector3f();
    private static final Vector3f lightDir1 = new Vector3f();
    private static final Vector3f float3 = new Vector3f();
    private static final Vector3f pos0 = new Vector3f();

    // Render-thread vertex building (MeshKernel); worker threads have their own kernels, see ParallelMeshes
    private static final MeshKernel KERNEL = new MeshKernel();
    private static final DrawParams PARAMS = new DrawParams();
    private static long syncBuffer;
    private static long syncCapacity;

    public IceSuperByteBuffer(TemplateMesh template, int[] shadeSwapVertices, boolean invertFakeDiffuseNormal) {
        this.template = template;
        this.shadeSwapVertices = shadeSwapVertices;
        this.invertFakeDiffuseNormal = invertFakeDiffuseNormal;
        reset();
    }

    public IceSuperByteBuffer(TemplateMesh template, int[] shadeSwapVertices) {
        this(template, shadeSwapVertices, false);
    }

    public IceSuperByteBuffer(TemplateMesh template) {
        this(template, new int[0]);
    }

    public SuperByteBuffer reset() {
        while (!transforms.clear())
            transforms.popPose();
        transforms.pushPose();

        vertexColor = 0xffffffff;
        disableDiffuse = false;
        spriteShiftFunc = null;
        hasCustomOverlay = false;
        overlay = OverlayTexture.NO_OVERLAY;
        hasCustomLight = false;
        packedLight = 0;
        useLevelLight = false;
        levelWithLight = null;
        lightTransform = null;
        return this;
    }

    public boolean isEmpty() {
        return template.isEmpty();
    }

    public PoseStack getTransforms() {
        return transforms;
    }

    @Override
    public SuperByteBuffer scale(float factorX, float factorY, float factorZ) {
        transforms.scale(factorX, factorY, factorZ);
        return this;
    }

    @Override
    public SuperByteBuffer rotate(Quaternionfc quaternion) {
        var last = transforms.last();
        last.pose().rotate(quaternion);
        last.normal().rotate(quaternion);
        return this;
    }

    @Override
    public SuperByteBuffer translate(float x, float y, float z) {
        transforms.translate(x, y, z);
        return this;
    }

    @Override
    public SuperByteBuffer mulPose(Matrix4fc pose) {
        transforms.last()
                .pose()
                .mul(pose);
        return this;
    }

    @Override
    public SuperByteBuffer mulNormal(Matrix3fc normal) {
        transforms.last()
                .normal()
                .mul(normal);
        return this;
    }

    @Override
    public SuperByteBuffer pushPose() {
        transforms.pushPose();
        return this;
    }

    @Override
    public SuperByteBuffer popPose() {
        transforms.popPose();
        return this;
    }

    public SuperByteBuffer color(float r, float g, float b, float a) {
        color((int) (r * 255.0f), (int) (g * 255.0f), (int) (b * 255.0f), (int) (a * 255.0f));
        return this;
    }

    public SuperByteBuffer color(int r, int g, int b, int a) {
        this.vertexColor = (a & 0xff) << 24 | (b & 0xff) << 16 | (g & 0xff) << 8 | (r & 0xff);
        return this;
    }

    public SuperByteBuffer color(int color) {
        this.vertexColor = 0xff000000 | ((color & 0xFF) << 16) | ((color & 0xFF00)) | ((color & 0xFF0000) >>> 16);
        return this;
    }

    public SuperByteBuffer color(Color c) {
        return color(c.getRGB());
    }

    public SuperByteBuffer disableDiffuse() {
        disableDiffuse = true;
        return this;
    }

    public SuperByteBuffer shiftUV(SpriteShiftEntry entry) {
        spriteShiftFunc = (u, v, output) -> {
            output.accept(entry.getTargetU(u), entry.getTargetV(v));
        };
        return this;
    }

    public SuperByteBuffer shiftUVScrolling(SpriteShiftEntry entry, float scrollV) {
        return shiftUVScrolling(entry, 0, scrollV);
    }

    public SuperByteBuffer shiftUVScrolling(SpriteShiftEntry entry, float scrollU, float scrollV) {
        spriteShiftFunc = (u, v, output) -> {
            float targetU = u - entry.getOriginal()
                    .getU0() + entry.getTarget()
                    .getU0()
                    + scrollU;
            float targetV = v - entry.getOriginal()
                    .getV0() + entry.getTarget()
                    .getV0()
                    + scrollV;
            output.accept(targetU, targetV);
        };
        return this;
    }

    public SuperByteBuffer shiftUVtoSheet(SpriteShiftEntry entry, float uTarget, float vTarget, int sheetSize) {
        spriteShiftFunc = (u, v, output) -> {
            float targetU = entry.getTarget()
                    .getU((SpriteShiftEntry.getUnInterpolatedU(entry.getOriginal(), u) / sheetSize) + uTarget);
            float targetV = entry.getTarget()
                    .getV((SpriteShiftEntry.getUnInterpolatedV(entry.getOriginal(), v) / sheetSize) + vTarget);
            output.accept(targetU, targetV);
        };
        return this;
    }

    public SuperByteBuffer overlay(int overlay) {
        hasCustomOverlay = true;
        this.overlay = overlay;
        return this;
    }

    public SuperByteBuffer light(int packedLight) {
        hasCustomLight = true;
        this.packedLight = packedLight;
        return this;
    }

    @Override
    public SuperByteBuffer useLevelLight(BlockAndTintGetter level) {
        useLevelLight = true;
        levelWithLight = level;
        return this;
    }

    @Override
    public SuperByteBuffer useLevelLight(BlockAndTintGetter level, Matrix4f lightTransform) {
        useLevelLight = true;
        levelWithLight = level;
        this.lightTransform = lightTransform;
        return this;
    }

    // Adapted from minecraft:shaders/include/light.glsl
    private static float calculateDiffuse(Vector3fc normal, Vector3fc lightDir0, Vector3fc lightDir1) {
        float light0 = Math.max(0.0f, lightDir0.dot(normal));
        float light1 = Math.max(0.0f, lightDir1.dot(normal));
        return Math.min(1.0f, (light0 + light1) * 0.6f + 0.4f);
    }

    public static int getLight(BlockAndTintGetter world, Vector3f lightPos) {
        return LevelLightCache.get(world, lightPos.x(), lightPos.y(), lightPos.z());
    }

    public int getLight(Vector3f lightPos) {
        return getLight(levelWithLight, lightTransform == null ? lightPos:lightPos.mulPosition(lightTransform));
    }

    private static boolean isPerspectiveProjection() {
        return RenderSystem.getProjectionMatrix().m33() == 0;
    }

    /**
     * True when a freshly drawn model would get CPU back-face culling right now (main view, perspective,
     * not recording/capturing). Cache replays use it to cull exactly the same quads.
     */
    public static boolean replayShouldCull() {
        return !RECORDING && !CAPTURING && !IrisCompat.isShadowPass() && isPerspectiveProjection();
    }

    private float[] localTangents() {
        float[] t = localTangents;
        if (t == null) {
            int quads = template.vertexCount() / 4;
            t = new float[quads * 4];
            for (int q = 0; q < quads; q++) {
                int i = q * 4;
                int n = template.normal(i);
                int packed = IrisTangent.compute(NormI8.unpackX(n), NormI8.unpackY(n), NormI8.unpackZ(n),
                        template.x(i), template.y(i), template.z(i), template.u(i), template.v(i),
                        template.x(i + 1), template.y(i + 1), template.z(i + 1), template.u(i + 1), template.v(i + 1),
                        template.x(i + 2), template.y(i + 2), template.z(i + 2), template.u(i + 2), template.v(i + 2));
                t[i] = (byte) packed / 127f;
                t[i + 1] = (byte) (packed >> 8) / 127f;
                t[i + 2] = (byte) (packed >> 16) / 127f;
                t[i + 3] = (byte) (packed >> 24) / 127f;
            }
            localTangents = t;
        }
        return t;
    }

    /**
     * @return 1/scale if the 3x3 part of {@code m} is a rotation times a positive uniform scale (then tangents
     * can simply be rotated), else 0
     */
    private static float similarityInvScale(Matrix4f m) {
        float l0 = m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02();
        float l1 = m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12();
        float l2 = m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22();
        float eps = 1e-3f * l0;
        if (l0 < 1e-8f || Math.abs(l1 - l0) > eps || Math.abs(l2 - l0) > eps) {
            return 0f;
        }
        float d01 = m.m00() * m.m10() + m.m01() * m.m11() + m.m02() * m.m12();
        float d02 = m.m00() * m.m20() + m.m01() * m.m21() + m.m02() * m.m22();
        float d12 = m.m10() * m.m20() + m.m11() * m.m21() + m.m12() * m.m22();
        if (Math.abs(d01) > eps || Math.abs(d02) > eps || Math.abs(d12) > eps || m.determinant3x3() <= 0f) {
            return 0f;
        }
        return (float) (1.0 / Math.sqrt(l0));
    }

    public void defaultRenderInto(PoseStack input, VertexConsumer builder) {
        Matrix4f modelMat = this.modelMat.set(input.last()
                .pose());
        Matrix4f localTransforms = transforms.last()
                .pose();
        modelMat.mul(localTransforms);

        Matrix3f normalMat = this.normalMat.set(input.last()
                .normal());
        Matrix3f localNormalTransforms = transforms.last()
                .normal();
        normalMat.mul(localNormalTransforms);

        ShiftOutput shiftOutput = this.shiftOutput;

        boolean applyDiffuse = !disableDiffuse && !ShadersModHelper.isShaderPackInUse();
        boolean shaded = true;
        int shadeSwapIndex = 0;
        int nextShadeSwapVertex = shadeSwapIndex < shadeSwapVertices.length ? shadeSwapVertices[shadeSwapIndex]:-1;
        int unshadedDiffuse = 255;
        if (applyDiffuse) {
            lightDir0.set(RenderSystemAccessor.catnip$getShaderLightDirections()[0]).normalize();
            lightDir1.set(RenderSystemAccessor.catnip$getShaderLightDirections()[1]).normalize();
            if (shadeSwapVertices.length > 0) {
                // Pretend unshaded faces always point up to get the correct max diffuse value for the current level.
                float3.set(0, invertFakeDiffuseNormal ? -1:1, 0);
                // Don't apply the normal matrix since that would cause upside down objects to be dark.
                unshadedDiffuse = (int) (255 * calculateDiffuse(float3, lightDir0, lightDir1));
            }
        }

        int vertexCount = template.vertexCount();
        for (int i = 0; i < vertexCount; i++) {
            if (i == nextShadeSwapVertex) {
                shaded = !shaded;
                shadeSwapIndex++;
                nextShadeSwapVertex = shadeSwapIndex < shadeSwapVertices.length ? shadeSwapVertices[shadeSwapIndex]:-1;
            }

            float x = template.x(i);
            float y = template.y(i);
            float z = template.z(i);
            pos0.set(x, y, z);
            pos0.mulPosition(modelMat);

            int light = template.light(i);
            if (hasCustomLight) {
                light = SuperByteBuffer.maxLight(light, packedLight);
            }
            if (useLevelLight) {
                // same sample point as the fast path: in the model's own space, then the light transform
                float3.set(((x - .5f) * 15 / 16f) + .5f, (y - .5f) * 15 / 16f + .5f, (z - .5f) * 15 / 16f + .5f)
                        .mulPosition(localTransforms);
                light = SuperByteBuffer.maxLight(light, getLight(float3));
            }

            int packedNormal = template.normal(i);
            float normalX = ((byte) (packedNormal & 0xFF)) / 127.0f;
            float normalY = ((byte) ((packedNormal >>> 8) & 0xFF)) / 127.0f;
            float normalZ = ((byte) ((packedNormal >>> 16) & 0xFF)) / 127.0f;
            float3.set(normalX, normalY, normalZ);
            float3.mul(normalMat);

            int quadColor = template.color(i);
            int r = ((((quadColor) & 0xFF) * ((vertexColor) & 0xFF)) + 0xFF) >>> 8;
            int g = ((((quadColor >>> 8) & 0xFF) * ((vertexColor >>> 8) & 0xFF)) + 0xFF) >>> 8;
            int b = ((((quadColor >>> 16) & 0xFF) * ((vertexColor >>> 16) & 0xFF)) + 0xFF) >>> 8;
            int a = ((((quadColor >>> 24) & 0xFF) * ((vertexColor >>> 24) & 0xFF)) + 0xFF) >>> 8;
            if (applyDiffuse) {
                int factor = shaded ? (int) (255.0F * calculateDiffuse(float3, lightDir0, lightDir1)):unshadedDiffuse;
                r = (r * factor + 255) >>> 8;
                g = (g * factor + 255) >>> 8;
                b = (b * factor + 255) >>> 8;
            }
            int color = (a << 24) | (r << 16) | (g << 8) | b;

            float u = template.u(i);
            float v = template.v(i);
            if (spriteShiftFunc != null) {
                spriteShiftFunc.shift(u, v, shiftOutput);
                u = shiftOutput.u;
                v = shiftOutput.v;
            }

            int overlay;
            if (hasCustomOverlay) {
                overlay = this.overlay;
            } else {
                overlay = template.overlay(i);
            }

            builder.addVertex(pos0.x, pos0.y, pos0.z).setColor(color).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(float3.x, float3.y, float3.z);
        }
    }

    /**
     * Snapshots everything needed to build this draw's vertices. The only place that reads this buffer's
     * mutable state for the fast path; the vertex loops live in {@link MeshKernel}.
     *
     * @return false if the format is not one the fast path writes
     */
    private boolean fillParams(DrawParams p, PoseStack input, VertexFormat format) {
        boolean iris = format == IrisTerrainVertex.FORMAT || format == IrisEntityVertex.FORMAT;
        boolean vanilla = format == BlockVertex.FORMAT || format == EntityVertex.FORMAT;
        if (!iris && !vanilla) {
            return false;
        }
        boolean shadow = iris && IrisCompat.isShadowPass();
        p.mode = iris ? (shadow ? DrawParams.MODE_IRIS_SHADOW : DrawParams.MODE_IRIS) : DrawParams.MODE_SODIUM;
        p.format = format;
        p.template = template;
        p.shadeSwapVertices = shadeSwapVertices;
        p.invertFakeDiffuseNormal = invertFakeDiffuseNormal;

        PoseStack.Pose local = transforms.last();
        p.modelMat.set(input.last().pose()).mul(local.pose());
        if (shadow) {
            p.normalMat.set(local.normal()); // as CreateBetterFps: the shadow pass uses the model's own normals
        } else {
            p.normalMat.set(input.last().normal()).mul(local.normal());
        }
        p.localTransforms.set(local.pose());

        p.vertexColor = vertexColor;
        p.disableDiffuse = disableDiffuse;
        p.spriteShift = spriteShiftFunc;
        p.hasCustomOverlay = hasCustomOverlay;
        p.overlay = overlay;
        p.hasCustomLight = hasCustomLight;
        p.packedLight = packedLight;
        p.useLevelLight = useLevelLight;
        p.level = levelWithLight;
        p.lightTransform = lightTransform;

        p.cull = !shadow && !RECORDING && !NO_CPU_CULL && !CAPTURING && isPerspectiveProjection();
        p.cullAgainstSun = shadow && !RECORDING && !CAPTURING && OptConfig.shadowCpuFaceCulling;
        if (p.cullAgainstSun) {
            p.sunMat.set(IrisCompat.getShadowMV());
        }
        if (p.mode == DrawParams.MODE_SODIUM && !p.disableDiffuse && IrisCompat.shaderPackInUse()) {
            p.disableDiffuse = true; // like Create: shader packs do their own shading
        }
        if (p.mode == DrawParams.MODE_SODIUM && !p.disableDiffuse) {
            p.lightDir0.set(RenderSystemAccessor.catnip$getShaderLightDirections()[0]).normalize();
            p.lightDir1.set(RenderSystemAccessor.catnip$getShaderLightDirections()[1]).normalize();
        }
        if (p.mode == DrawParams.MODE_IRIS) {
            // Iris tangents: computed once in model space and only rotated per frame (they were ~5% of a frame)
            float s = spriteShiftFunc == null ? similarityInvScale(p.modelMat) : 0f;
            p.tangentInvScale = s;
            p.localTangents = s != 0f ? localTangents() : null;
        } else {
            p.tangentInvScale = 0f;
            p.localTangents = null;
        }
        if (iris) {
            IrisEntityVertex.captureIds(p);
        }
        return true;
    }

    public boolean renderIntoSodium(PoseStack input, VertexConsumer builder) {
        VertexBufferWriter writer = VertexBufferWriter.tryOf(builder);
        if (writer == null) return false;
        VertexFormat format = FastFormats.of(builder);
        if (format == null) return false;
        DrawParams p = PARAMS;
        if (!fillParams(p, input, format)) {
            return false;
        }
        try {
            // Draws that don't read the world are built on worker threads and appended to the buffer right
            // before it is used (BufferBuilder.build); see ParallelMeshes.
            if (!useLevelLight && !RECORDING && !CAPTURING && builder instanceof com.mojang.blaze3d.vertex.BufferBuilder target
                    && ParallelMeshes.trySubmit(p, target)) {
                return true;
            }
            long need = MeshKernel.maxBytes(p);
            if (need > syncCapacity) {
                long cap = Math.max(need, Math.max(64 * 1024, syncCapacity * 2));
                long addr = MemoryUtil.nmemRealloc(syncBuffer, cap);
                if (addr == 0L) {
                    throw new OutOfMemoryError("IceSuperByteBuffer");
                }
                syncBuffer = addr;
                syncCapacity = cap;
            }
            int n = KERNEL.write(p, syncBuffer);
            ParallelMeshes.push(writer, syncBuffer, n, format);
            return true;
        } finally {
            p.clearRefs();
        }
    }

    @Override
    public void renderInto(PoseStack input, VertexConsumer builder) {
        try {
            if (builder == com.createicecream.render.capture.NullSink.CONSUMER) {
                return; // replayed contraption frame: the renderer runs for its side effects only
            }
            if (!OptConfig.enabled || !OptConfig.fastRenderer) {
                // runtime kill switch: Create's own per-vertex path (identical output to vanilla Create)
                defaultRenderInto(input, builder);
                return;
            }
            if (!RECORDING && ModelCache.tryRender(this, input, builder)) {
                return;
            }
            if (!renderIntoSodium(input, builder)) {
                defaultRenderInto(input, builder);
            }
        } finally {
            NO_CPU_CULL = false;
            reset();
        }
    }

    // ---- state accessors for ModelCache (same package) ----

    boolean hasSpriteShift() {
        return spriteShiftFunc != null;
    }

    int vertexColor() {
        return vertexColor;
    }

    boolean diffuseDisabled() {
        return disableDiffuse;
    }

    boolean customOverlay() {
        return hasCustomOverlay;
    }

    int overlayValue() {
        return overlay;
    }

    boolean customLight() {
        return hasCustomLight;
    }

    int packedLightValue() {
        return packedLight;
    }

    boolean usesLevelLight() {
        return useLevelLight;
    }

    @Nullable
    BlockAndTintGetter lightLevel() {
        return levelWithLight;
    }

    @Nullable
    Matrix4f lightTransformMatrix() {
        return lightTransform;
    }
}
