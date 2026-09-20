package com.createicecream.render.fast;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.VertexFormat;

import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.TemplateMesh;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * Everything {@link MeshKernel} needs to turn one model draw into finished vertices, copied out of the
 * (shared, mutable) {@link IceSuperByteBuffer} at draw time. Because it is a snapshot, the vertices can be
 * built later on another thread while the buffer is already reused for the next draw.
 */
final class DrawParams {
    static final int MODE_IRIS = 0;
    static final int MODE_IRIS_SHADOW = 1;
    static final int MODE_SODIUM = 2;

    TemplateMesh template;
    int[] shadeSwapVertices;
    boolean invertFakeDiffuseNormal;

    final Matrix4f modelMat = new Matrix4f();
    final Matrix3f normalMat = new Matrix3f();
    /** The model's own transform; only used to place level-light samples. */
    final Matrix4f localTransforms = new Matrix4f();

    int vertexColor;
    boolean disableDiffuse;
    @Nullable
    SuperByteBuffer.SpriteShiftFunc spriteShift;
    boolean hasCustomOverlay;
    int overlay;
    boolean hasCustomLight;
    int packedLight;

    /** Level light reads the world, so such draws are always built on the render thread. */
    boolean useLevelLight;
    @Nullable
    BlockAndTintGetter level;
    @Nullable
    Matrix4f lightTransform;

    VertexFormat format;
    int mode;
    /** CPU back-face culling against the camera (main view only). */
    boolean cull;
    boolean cullAgainstSun;
    final Matrix4f sunMat = new Matrix4f();
    final Vector3f lightDir0 = new Vector3f();
    final Vector3f lightDir1 = new Vector3f();

    /** Model-space Iris tangents + the matrix scale to rotate them with, or null / 0 to compute per quad. */
    @Nullable
    float[] localTangents;
    float tangentInvScale;

    short entityId;
    short blockEntityId;
    short itemId;

    void copyFrom(DrawParams o) {
        template = o.template;
        shadeSwapVertices = o.shadeSwapVertices;
        invertFakeDiffuseNormal = o.invertFakeDiffuseNormal;
        modelMat.set(o.modelMat);
        normalMat.set(o.normalMat);
        localTransforms.set(o.localTransforms);
        vertexColor = o.vertexColor;
        disableDiffuse = o.disableDiffuse;
        spriteShift = o.spriteShift;
        hasCustomOverlay = o.hasCustomOverlay;
        overlay = o.overlay;
        hasCustomLight = o.hasCustomLight;
        packedLight = o.packedLight;
        useLevelLight = o.useLevelLight;
        level = o.level;
        lightTransform = o.lightTransform;
        format = o.format;
        mode = o.mode;
        cull = o.cull;
        cullAgainstSun = o.cullAgainstSun;
        sunMat.set(o.sunMat);
        lightDir0.set(o.lightDir0);
        lightDir1.set(o.lightDir1);
        localTangents = o.localTangents;
        tangentInvScale = o.tangentInvScale;
        entityId = o.entityId;
        blockEntityId = o.blockEntityId;
        itemId = o.itemId;
    }

    /** Drop references so pooled params don't keep models or levels alive. */
    void clearRefs() {
        template = null;
        shadeSwapVertices = null;
        spriteShift = null;
        level = null;
        lightTransform = null;
        localTangents = null;
        format = null;
    }
}
