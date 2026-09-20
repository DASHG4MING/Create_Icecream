/*
 * The vertex loops are derived from CreateBetterFps' SodiumByteBuffer by MoePus
 * (https://github.com/MoePus/CreateBetterFps), MIT License, Copyright (c) 2025 MoePus.
 * See THIRD_PARTY_LICENSE_CreateBetterFps.txt. Modified for Create IceCream: moved out of the buffer into a
 * self-contained kernel (one instance per thread) so models can be built on worker threads.
 */
package com.createicecream.render.fast;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import net.caffeinemc.mods.sodium.api.math.MatrixHelper;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.TemplateMesh;

/**
 * Turns one {@link DrawParams} snapshot into finished vertices at a memory address. Holds only scratch
 * objects, so every thread needs its own instance; the output is identical whichever thread runs it.
 */
final class MeshKernel {
    private final Vector3f float3 = new Vector3f();
    private final Vector3f pos0 = new Vector3f();
    private final Vector3f pos1 = new Vector3f();
    private final Vector3f pos2 = new Vector3f();
    private final Vector3f pos3 = new Vector3f();
    private final Vector2f uv0 = new Vector2f();
    private final Vector2f uv1 = new Vector2f();
    private final Vector2f uv2 = new Vector2f();
    private final Vector2f uv3 = new Vector2f();
    private final SuperByteBuffer.ShiftOutput shiftOutput = new SuperByteBuffer.ShiftOutput();

    static int stride(DrawParams p) {
        if (p.format == IrisTerrainVertex.FORMAT) {
            return IrisTerrainVertex.STRIDE;
        }
        if (p.format == IrisEntityVertex.FORMAT) {
            return IrisEntityVertex.STRIDE;
        }
        return p.format == BlockVertex.FORMAT ? BlockVertex.STRIDE : EntityVertex.STRIDE;
    }

    /** Upper bound of the bytes {@link #write} produces. */
    static long maxBytes(DrawParams p) {
        return (long) p.template.vertexCount() * stride(p);
    }

    /** @return number of vertices written at {@code ptr} */
    int write(DrawParams p, long ptr) {
        return switch (p.mode) {
            case DrawParams.MODE_IRIS -> iris(p, ptr);
            case DrawParams.MODE_IRIS_SHADOW -> irisShadow(p, ptr);
            default -> sodium(p, ptr);
        };
    }

    private static float calculateDiffuse(Vector3fc normal, Vector3fc lightDir0, Vector3fc lightDir1) {
        float light0 = Math.max(0.0f, lightDir0.dot(normal));
        float light1 = Math.max(0.0f, lightDir1.dot(normal));
        return Math.min(1.0f, (light0 + light1) * 0.6f + 0.4f);
    }

    private int calcColorSodium(DrawParams p, int quadColor, int unshadedDiffuse, boolean applyDiffuse, boolean shaded, float nx, float ny, float nz) {
        int vertexColor = p.vertexColor;
        int r = ((((quadColor) & 0xFF) * ((vertexColor) & 0xFF)) + 0xFF) >>> 8;
        int g = ((((quadColor >>> 8) & 0xFF) * ((vertexColor >>> 8) & 0xFF)) + 0xFF) >>> 8;
        int b = ((((quadColor >>> 16) & 0xFF) * ((vertexColor >>> 16) & 0xFF)) + 0xFF) >>> 8;
        int a = ((((quadColor >>> 24) & 0xFF) * ((vertexColor >>> 24) & 0xFF)) + 0xFF) >>> 8;
        if (applyDiffuse) {
            float3.set(nx, ny, nz);
            int factor = shaded ? (int) (255.0F * calculateDiffuse(float3, p.lightDir0, p.lightDir1)) : unshadedDiffuse;
            r = (r * factor + 255) >>> 8;
            g = (g * factor + 255) >>> 8;
            b = (b * factor + 255) >>> 8;
        }
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int calcColorIris(int quadColor, int vertexColor) {
        int r = ((((quadColor) & 0xFF) * ((vertexColor) & 0xFF)) + 0xFF) >>> 8;
        int g = ((((quadColor >>> 8) & 0xFF) * ((vertexColor >>> 8) & 0xFF)) + 0xFF) >>> 8;
        int b = ((((quadColor >>> 16) & 0xFF) * ((vertexColor >>> 16) & 0xFF)) + 0xFF) >>> 8;
        int a = ((((quadColor >>> 24) & 0xFF) * ((vertexColor >>> 24) & 0xFF)) + 0xFF) >>> 8;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int packTangent(float x, float y, float z, float w) {
        return enc(x) | (enc(y) << 8) | (enc(z) << 16) | (enc(w) << 24);
    }

    private static int enc(float c) {
        return (int) (Math.max(-1f, Math.min(1f, c)) * 127.0f) & 0xFF;
    }

    /** Same back-face test as CreateBetterFps: the quad's winding, oriented by its normal, against the camera at 0,0,0. */
    private boolean backFacing(float nx, float ny, float nz) {
        float ex = pos1.x - pos0.x, ey = pos1.y - pos0.y, ez = pos1.z - pos0.z;
        float fx = pos2.x - pos0.x, fy = pos2.y - pos0.y, fz = pos2.z - pos0.z;
        float cx = ey * fz - ez * fy;
        float cy = ez * fx - ex * fz;
        float cz = ex * fy - ey * fx;
        if (nx * cx + ny * cy + nz * cz < 0) {
            cx = -cx;
            cy = -cy;
            cz = -cz;
        }
        return cx * (pos0.x + pos2.x) + cy * (pos0.y + pos2.y) + cz * (pos0.z + pos2.z) > 0;
    }

    private void uvs(DrawParams p, TemplateMesh t, int i) {
        SuperByteBuffer.SpriteShiftFunc shift = p.spriteShift;
        if (shift != null) {
            shift.shift(t.u(i), t.v(i), shiftOutput);
            uv0.set(shiftOutput.u, shiftOutput.v);
            shift.shift(t.u(i + 1), t.v(i + 1), shiftOutput);
            uv1.set(shiftOutput.u, shiftOutput.v);
            shift.shift(t.u(i + 2), t.v(i + 2), shiftOutput);
            uv2.set(shiftOutput.u, shiftOutput.v);
            shift.shift(t.u(i + 3), t.v(i + 3), shiftOutput);
            uv3.set(shiftOutput.u, shiftOutput.v);
        } else {
            uv0.set(t.u(i), t.v(i));
            uv1.set(t.u(i + 1), t.v(i + 1));
            uv2.set(t.u(i + 2), t.v(i + 2));
            uv3.set(t.u(i + 3), t.v(i + 3));
        }
    }

    private int levelLight(DrawParams p, TemplateMesh t, int v, int light) {
        float3.set(((t.x(v) - .5f) * 15 / 16f) + .5f, (t.y(v) - .5f) * 15 / 16f + .5f, (t.z(v) - .5f) * 15 / 16f + .5f)
                .mulPosition(p.localTransforms);
        if (p.lightTransform != null) {
            float3.mulPosition(p.lightTransform);
        }
        return SuperByteBuffer.maxLight(light, IceSuperByteBuffer.getLight(p.level, float3));
    }

    // ---------------------------------------------------------------- Iris (shader pack), main view

    private int iris(DrawParams p, long ptr) {
        TemplateMesh t = p.template;
        Matrix4f modelMat = p.modelMat;
        Matrix3f normalMat = p.normalMat;
        boolean isTerrain = p.format == IrisTerrainVertex.FORMAT;
        float[] lt = p.localTangents;
        float s = p.tangentInvScale;
        float t00 = modelMat.m00() * s, t01 = modelMat.m01() * s, t02 = modelMat.m02() * s;
        float t10 = modelMat.m10() * s, t11 = modelMat.m11() * s, t12 = modelMat.m12() * s;
        float t20 = modelMat.m20() * s, t21 = modelMat.m21() * s, t22 = modelMat.m22() * s;
        int written = 0;

        int vertexCount = t.vertexCount();
        for (int i = 0; i < vertexCount; i += 4) {
            int packedNormal = t.normal(i);
            float ux = NormI8.unpackX(packedNormal), uy = NormI8.unpackY(packedNormal), uz = NormI8.unpackZ(packedNormal);
            float nx = MatrixHelper.transformNormalX(normalMat, ux, uy, uz);
            float ny = MatrixHelper.transformNormalY(normalMat, ux, uy, uz);
            float nz = MatrixHelper.transformNormalZ(normalMat, ux, uy, uz);

            pos0.set(t.x(i), t.y(i), t.z(i)).mulPosition(modelMat);
            pos1.set(t.x(i + 1), t.y(i + 1), t.z(i + 1)).mulPosition(modelMat);
            pos2.set(t.x(i + 2), t.y(i + 2), t.z(i + 2)).mulPosition(modelMat);
            if (p.cull && backFacing(nx, ny, nz)) {
                continue;
            }
            pos3.set(t.x(i + 3), t.y(i + 3), t.z(i + 3)).mulPosition(modelMat);

            int normal = NormI8.pack(nx, ny, nz);
            uvs(p, t, i);
            float midU = (uv0.x + uv1.x + uv2.x + uv3.x) / 4;
            float midV = (uv0.y + uv1.y + uv2.y + uv3.y) / 4;

            int tangent;
            if (lt != null) {
                float lx = lt[i], ly = lt[i + 1], lz = lt[i + 2];
                tangent = packTangent(t00 * lx + t10 * ly + t20 * lz, t01 * lx + t11 * ly + t21 * lz,
                        t02 * lx + t12 * ly + t22 * lz, lt[i + 3]);
            } else {
                tangent = IrisTangent.compute(nx, ny, nz, pos0.x, pos0.y, pos0.z, uv0.x, uv0.y,
                        pos1.x, pos1.y, pos1.z, uv1.x, uv1.y, pos2.x, pos2.y, pos2.z, uv2.x, uv2.y);
            }

            int color0 = calcColorIris(t.color(i), p.vertexColor);
            int color1 = calcColorIris(t.color(i + 1), p.vertexColor);
            int color2 = calcColorIris(t.color(i + 2), p.vertexColor);
            int color3 = calcColorIris(t.color(i + 3), p.vertexColor);

            int light0 = t.light(i), light1 = t.light(i + 1), light2 = t.light(i + 2), light3 = t.light(i + 3);
            if (p.hasCustomLight) {
                light0 = SuperByteBuffer.maxLight(light0, p.packedLight);
                light1 = SuperByteBuffer.maxLight(light1, p.packedLight);
                light2 = SuperByteBuffer.maxLight(light2, p.packedLight);
                light3 = SuperByteBuffer.maxLight(light3, p.packedLight);
            }
            if (p.useLevelLight) {
                light0 = levelLight(p, t, i, light0);
                light1 = levelLight(p, t, i + 1, light1);
                light2 = levelLight(p, t, i + 2, light2);
                light3 = levelLight(p, t, i + 3, light3);
            }

            if (isTerrain) {
                int st = IrisTerrainVertex.STRIDE;
                IrisTerrainVertex.write(ptr, pos0.x, pos0.y, pos0.z, color0, uv0.x, uv0.y, midU, midV, light0, normal, tangent, p.entityId, p.blockEntityId);
                IrisTerrainVertex.write(ptr + st, pos1.x, pos1.y, pos1.z, color1, uv1.x, uv1.y, midU, midV, light1, normal, tangent, p.entityId, p.blockEntityId);
                IrisTerrainVertex.write(ptr + 2L * st, pos2.x, pos2.y, pos2.z, color2, uv2.x, uv2.y, midU, midV, light2, normal, tangent, p.entityId, p.blockEntityId);
                IrisTerrainVertex.write(ptr + 3L * st, pos3.x, pos3.y, pos3.z, color3, uv3.x, uv3.y, midU, midV, light3, normal, tangent, p.entityId, p.blockEntityId);
                ptr += 4L * st;
            } else {
                int o0, o1, o2, o3;
                if (p.hasCustomOverlay) {
                    o0 = o1 = o2 = o3 = p.overlay;
                } else {
                    o0 = t.overlay(i);
                    o1 = t.overlay(i + 1);
                    o2 = t.overlay(i + 2);
                    o3 = t.overlay(i + 3);
                }
                int st = IrisEntityVertex.STRIDE;
                IrisEntityVertex.write(ptr, pos0.x, pos0.y, pos0.z, color0, uv0.x, uv0.y, midU, midV, o0, light0, normal, tangent, p.entityId, p.blockEntityId, p.itemId);
                IrisEntityVertex.write(ptr + st, pos1.x, pos1.y, pos1.z, color1, uv1.x, uv1.y, midU, midV, o1, light1, normal, tangent, p.entityId, p.blockEntityId, p.itemId);
                IrisEntityVertex.write(ptr + 2L * st, pos2.x, pos2.y, pos2.z, color2, uv2.x, uv2.y, midU, midV, o2, light2, normal, tangent, p.entityId, p.blockEntityId, p.itemId);
                IrisEntityVertex.write(ptr + 3L * st, pos3.x, pos3.y, pos3.z, color3, uv3.x, uv3.y, midU, midV, o3, light3, normal, tangent, p.entityId, p.blockEntityId, p.itemId);
                ptr += 4L * st;
            }
            written += 4;
        }
        return written;
    }

    // ---------------------------------------------------------------- Iris shadow pass

    private int irisShadow(DrawParams p, long ptr) {
        TemplateMesh t = p.template;
        Matrix4f modelMat = p.modelMat;
        Matrix3f normalMat = p.normalMat;
        Matrix4f sun = p.sunMat;
        boolean isTerrain = p.format == IrisTerrainVertex.FORMAT;
        int written = 0;

        int vertexCount = t.vertexCount();
        for (int i = 0; i < vertexCount; i += 4) {
            int packedNormal = t.normal(i);
            float ux = NormI8.unpackX(packedNormal), uy = NormI8.unpackY(packedNormal), uz = NormI8.unpackZ(packedNormal);
            float nx = MatrixHelper.transformNormalX(normalMat, ux, uy, uz);
            float ny = MatrixHelper.transformNormalY(normalMat, ux, uy, uz);
            float nz = MatrixHelper.transformNormalZ(normalMat, ux, uy, uz);
            if (p.cullAgainstSun && nx * sun.m02() + ny * sun.m12() + nz * sun.m22() >= 0) {
                continue; // optional sun-facing culling
            }
            pos0.set(t.x(i), t.y(i), t.z(i)).mulPosition(modelMat);
            pos1.set(t.x(i + 1), t.y(i + 1), t.z(i + 1)).mulPosition(modelMat);
            pos2.set(t.x(i + 2), t.y(i + 2), t.z(i + 2)).mulPosition(modelMat);
            pos3.set(t.x(i + 3), t.y(i + 3), t.z(i + 3)).mulPosition(modelMat);
            int normal = NormI8.pack(nx, ny, nz);
            uvs(p, t, i);

            if (isTerrain) {
                int st = IrisTerrainVertex.STRIDE;
                IrisTerrainVertex.write(ptr, pos0.x, pos0.y, pos0.z, 0xffffffff, uv0.x, uv0.y, 0.5f, 0.5f, 0xf000f0, normal, 0xffffffff, p.entityId, p.blockEntityId);
                IrisTerrainVertex.write(ptr + st, pos1.x, pos1.y, pos1.z, 0xffffffff, uv1.x, uv1.y, 0.5f, 0.5f, 0xf000f0, normal, 0xffffffff, p.entityId, p.blockEntityId);
                IrisTerrainVertex.write(ptr + 2L * st, pos2.x, pos2.y, pos2.z, 0xffffffff, uv2.x, uv2.y, 0.5f, 0.5f, 0xf000f0, normal, 0xffffffff, p.entityId, p.blockEntityId);
                IrisTerrainVertex.write(ptr + 3L * st, pos3.x, pos3.y, pos3.z, 0xffffffff, uv3.x, uv3.y, 0.5f, 0.5f, 0xf000f0, normal, 0xffffffff, p.entityId, p.blockEntityId);
                ptr += 4L * st;
            } else {
                int st = IrisEntityVertex.STRIDE;
                IrisEntityVertex.write(ptr, pos0.x, pos0.y, pos0.z, 0xffffffff, uv0.x, uv0.y, 0.5f, 0.5f, 0xffffffff, 0xf000f0, normal, 0xffffffff, p.entityId, p.blockEntityId, p.itemId);
                IrisEntityVertex.write(ptr + st, pos1.x, pos1.y, pos1.z, 0xffffffff, uv1.x, uv1.y, 0.5f, 0.5f, 0xffffffff, 0xf000f0, normal, 0xffffffff, p.entityId, p.blockEntityId, p.itemId);
                IrisEntityVertex.write(ptr + 2L * st, pos2.x, pos2.y, pos2.z, 0xffffffff, uv2.x, uv2.y, 0.5f, 0.5f, 0xffffffff, 0xf000f0, normal, 0xffffffff, p.entityId, p.blockEntityId, p.itemId);
                IrisEntityVertex.write(ptr + 3L * st, pos3.x, pos3.y, pos3.z, 0xffffffff, uv3.x, uv3.y, 0.5f, 0.5f, 0xffffffff, 0xf000f0, normal, 0xffffffff, p.entityId, p.blockEntityId, p.itemId);
                ptr += 4L * st;
            }
            written += 4;
        }
        return written;
    }

    // ---------------------------------------------------------------- Sodium without shaders (vanilla formats)

    private int sodium(DrawParams p, long ptr) {
        TemplateMesh t = p.template;
        Matrix4f modelMat = p.modelMat;
        Matrix3f normalMat = p.normalMat;
        int[] swaps = p.shadeSwapVertices;

        boolean shaded = true;
        int shadeSwapIndex = 0;
        int nextShadeSwapVertex = shadeSwapIndex < swaps.length ? swaps[shadeSwapIndex] : Integer.MAX_VALUE;
        int unshadedDiffuse = 255;
        boolean applyDiffuse = !p.disableDiffuse;
        if (applyDiffuse && swaps.length > 0) {
            // Pretend unshaded faces always point up to get the correct max diffuse value for the current level.
            float3.set(0, p.invertFakeDiffuseNormal ? -1 : 1, 0);
            unshadedDiffuse = (int) (255 * calculateDiffuse(float3, p.lightDir0, p.lightDir1));
        }
        boolean isBlock = p.format == BlockVertex.FORMAT;
        int written = 0;

        int vertexCount = t.vertexCount();
        for (int i = 0; i < vertexCount; i += 4) {
            if (i >= nextShadeSwapVertex) {
                shaded = !shaded;
                shadeSwapIndex++;
                nextShadeSwapVertex = shadeSwapIndex < swaps.length ? swaps[shadeSwapIndex] : Integer.MAX_VALUE;
            }
            int packedNormal = t.normal(i);
            float ux = NormI8.unpackX(packedNormal), uy = NormI8.unpackY(packedNormal), uz = NormI8.unpackZ(packedNormal);
            float nx = MatrixHelper.transformNormalX(normalMat, ux, uy, uz);
            float ny = MatrixHelper.transformNormalY(normalMat, ux, uy, uz);
            float nz = MatrixHelper.transformNormalZ(normalMat, ux, uy, uz);

            pos0.set(t.x(i), t.y(i), t.z(i)).mulPosition(modelMat);
            pos1.set(t.x(i + 1), t.y(i + 1), t.z(i + 1)).mulPosition(modelMat);
            pos2.set(t.x(i + 2), t.y(i + 2), t.z(i + 2)).mulPosition(modelMat);
            if (p.cull && backFacing(nx, ny, nz)) {
                continue;
            }
            int normal = NormI8.pack(nx, ny, nz);
            pos3.set(t.x(i + 3), t.y(i + 3), t.z(i + 3)).mulPosition(modelMat);
            uvs(p, t, i);

            int color0 = calcColorSodium(p, t.color(i), unshadedDiffuse, applyDiffuse, shaded, nx, ny, nz);
            int color1 = calcColorSodium(p, t.color(i + 1), unshadedDiffuse, applyDiffuse, shaded, nx, ny, nz);
            int color2 = calcColorSodium(p, t.color(i + 2), unshadedDiffuse, applyDiffuse, shaded, nx, ny, nz);
            int color3 = calcColorSodium(p, t.color(i + 3), unshadedDiffuse, applyDiffuse, shaded, nx, ny, nz);

            int light0 = t.light(i), light1 = t.light(i + 1), light2 = t.light(i + 2), light3 = t.light(i + 3);
            if (p.hasCustomLight) {
                light0 = SuperByteBuffer.maxLight(light0, p.packedLight);
                light1 = SuperByteBuffer.maxLight(light1, p.packedLight);
                light2 = SuperByteBuffer.maxLight(light2, p.packedLight);
                light3 = SuperByteBuffer.maxLight(light3, p.packedLight);
            }
            if (p.useLevelLight) {
                light0 = levelLight(p, t, i, light0);
                light1 = levelLight(p, t, i + 1, light1);
                light2 = levelLight(p, t, i + 2, light2);
                light3 = levelLight(p, t, i + 3, light3);
            }

            if (isBlock) {
                int st = BlockVertex.STRIDE;
                BlockVertex.write(ptr, pos0.x, pos0.y, pos0.z, color0, uv0.x, uv0.y, light0, normal);
                BlockVertex.write(ptr + st, pos1.x, pos1.y, pos1.z, color1, uv1.x, uv1.y, light1, normal);
                BlockVertex.write(ptr + 2L * st, pos2.x, pos2.y, pos2.z, color2, uv2.x, uv2.y, light2, normal);
                BlockVertex.write(ptr + 3L * st, pos3.x, pos3.y, pos3.z, color3, uv3.x, uv3.y, light3, normal);
                ptr += 4L * st;
            } else {
                int o0, o1, o2, o3;
                if (p.hasCustomOverlay) {
                    o0 = o1 = o2 = o3 = p.overlay;
                } else {
                    o0 = t.overlay(i);
                    o1 = t.overlay(i + 1);
                    o2 = t.overlay(i + 2);
                    o3 = t.overlay(i + 3);
                }
                int st = EntityVertex.STRIDE;
                EntityVertex.write(ptr, pos0.x, pos0.y, pos0.z, color0, uv0.x, uv0.y, o0, light0, normal);
                EntityVertex.write(ptr + st, pos1.x, pos1.y, pos1.z, color1, uv1.x, uv1.y, o1, light1, normal);
                EntityVertex.write(ptr + 2L * st, pos2.x, pos2.y, pos2.z, color2, uv2.x, uv2.y, o2, light2, normal);
                EntityVertex.write(ptr + 3L * st, pos3.x, pos3.y, pos3.z, color3, uv3.x, uv3.y, o3, light3, normal);
                ptr += 4L * st;
            }
            written += 4;
        }
        return written;
    }
}
