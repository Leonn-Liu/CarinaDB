#pragma once
#include <vector>
#include <cstdint>

// 对应 com.jiashi.db.engine.index.hnsw.HnswNode
namespace carina::engine::index::hnsw {

struct HnswNode {
    int id;
    std::vector<float> vector;
    int maxLevel;
    std::vector<std::vector<int>> neighbors; // 多层邻接表

    HnswNode(int id_, std::vector<float> vector_, int maxLevel_, int /*maxM*/, int /*maxM0*/)
        : id(id_), vector(std::move(vector_)), maxLevel(maxLevel_), neighbors(maxLevel_ + 1) {}
};

} // namespace carina::engine::index::hnsw
