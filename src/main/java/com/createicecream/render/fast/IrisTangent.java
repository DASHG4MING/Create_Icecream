package com.createicecream.render.fast;

/** Keeps the Iris class reference out of IceSuperByteBuffer's constant pool paths used without Iris. */
final class IrisTangent {
    private IrisTangent() {
    }

    static int compute(float nx, float ny, float nz,
                       float x0, float y0, float z0, float u0, float v0,
                       float x1, float y1, float z1, float u1, float v1,
                       float x2, float y2, float z2, float u2, float v2) {
        return net.irisshaders.iris.vertices.NormalHelper.computeTangent(nx, ny, nz,
                x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2);
    }
}
