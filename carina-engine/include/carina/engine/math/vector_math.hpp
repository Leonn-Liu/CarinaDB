#pragma once
#include <vector>
#include <stdexcept>

// 对应 com.jiashi.db.engine.math.VectorMath。
// Java 版用 incubator Vector API 手写 SIMD 路径 (dotProductSimd/l2DistanceSquareSimd)。
// C++ 版这里保留同样的函数命名和标量语义，但不手写平台相关的 SIMD intrinsics——
// 用 -O3 -march=native 让编译器自动向量化这几个简单的归约循环，正确性与 Java 标量版
// 完全一致；如果之后要追求手写 SIMD 的极致性能，可以在这里换成 <immintrin.h>。
namespace carina::engine::math {

class VectorMath {
public:
    static float dotProduct(const std::vector<float>& v1, const std::vector<float>& v2) {
        if (v1.size() != v2.size()) throw std::invalid_argument("Vector dimensions must match");
        float res = 0.0f;
        for (size_t i = 0; i < v1.size(); i++) res += v1[i] * v2[i];
        return res;
    }

    static float l2DistanceSquare(const std::vector<float>& v1, const std::vector<float>& v2) {
        if (v1.size() != v2.size()) throw std::invalid_argument("Vector dimensions must match");
        float res = 0.0f;
        for (size_t i = 0; i < v1.size(); i++) {
            float diff = v1[i] - v2[i];
            res += diff * diff;
        }
        return res;
    }
};

} // namespace carina::engine::math
