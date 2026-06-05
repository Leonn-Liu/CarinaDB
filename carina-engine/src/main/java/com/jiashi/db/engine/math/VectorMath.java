package com.jiashi.db.engine.math;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public class VectorMath {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    // ==========================================
    // 点积 (Dot Product) - 余弦相似度的底层替换
    // ==========================================

    /**
     * 未优化版本：纯标量 (Scalar) 计算
     */
    public static float dotProductScalar(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }
        float res = 0f;
        for (int i = 0; i < v1.length; i++) {
            res += v1[i] * v2[i];
        }
        return res;
    }

    /**
     * 优化版本：SIMD 向量化计算
     */
    public static float dotProductSimd(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }

        int i = 0;
        int upperBound = SPECIES.loopBound(v1.length);
        FloatVector sum = FloatVector.zero(SPECIES);

        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector a = FloatVector.fromArray(SPECIES, v1, i);
            FloatVector b = FloatVector.fromArray(SPECIES, v2, i);
            sum = sum.add(a.mul(b));
        }

        float res = sum.reduceLanes(VectorOperators.ADD);

        for (; i < v1.length; i++) {
            res += v1[i] * v2[i];
        }

        return res;
    }

    // ==========================================
    // 欧式距离平方 (L2 Distance Squared)
    // ==========================================

    /**
     * 未优化版本：纯标量 (Scalar) 计算
     */
    public static float l2DistanceSquareScalar(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }
        float res = 0f;
        for (int i = 0; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            res += diff * diff;
        }
        return res;
    }

    /**
     * 优化版本：SIMD 向量化计算
     */
    public static float l2DistanceSquareSimd(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }

        int i = 0;
        int upperBound = SPECIES.loopBound(v1.length);
        FloatVector sum = FloatVector.zero(SPECIES);

        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector a = FloatVector.fromArray(SPECIES, v1, i);
            FloatVector b = FloatVector.fromArray(SPECIES, v2, i);
            FloatVector diff = a.sub(b);
            sum = sum.add(diff.mul(diff));
        }

        float res = sum.reduceLanes(VectorOperators.ADD);

        for (; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            res += diff * diff;
        }

        return res;
    }
}