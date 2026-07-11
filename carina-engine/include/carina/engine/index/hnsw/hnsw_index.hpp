#pragma once
#include "carina/engine/index/hnsw/hnsw_node.hpp"
#include "carina/engine/math/vector_math.hpp"
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <queue>
#include <algorithm>
#include <cmath>
#include <random>
#include <mutex>

// 对应 com.jiashi.db.engine.index.hnsw.HnswIndex
namespace carina::engine::index::hnsw {

class HnswIndex {
public:
    struct NodeDistance {
        int nodeId;
        float distance;
    };

    // @param maxM 默认连接数 (通常为 16 或 32)
    // @param efConstruction 建图探索范围 (通常为 100 到 200)
    HnswIndex(int maxM, int efConstruction)
        : maxM_(maxM), maxM0_(maxM * 2), mL_(1.0 / std::log(static_cast<double>(maxM))),
          efConstruction_(efConstruction) {}

    // 单层最近邻搜索
    std::vector<NodeDistance> searchLayer(const std::vector<float>& query, int ep, int ef, int lc) const {
        std::unordered_set<int> visited;
        visited.insert(ep);

        auto minCmp = [](const NodeDistance& a, const NodeDistance& b) { return a.distance > b.distance; };
        auto maxCmp = [](const NodeDistance& a, const NodeDistance& b) { return a.distance < b.distance; };
        std::priority_queue<NodeDistance, std::vector<NodeDistance>, decltype(minCmp)> candidates(minCmp);
        std::priority_queue<NodeDistance, std::vector<NodeDistance>, decltype(maxCmp)> results(maxCmp);

        const HnswNode& epNode = nodes_.at(ep);
        float initialDist = carina::engine::math::VectorMath::l2DistanceSquare(query, epNode.vector);
        candidates.push({ep, initialDist});
        results.push({ep, initialDist});

        while (!candidates.empty()) {
            NodeDistance current = candidates.top();
            candidates.pop();

            const NodeDistance& furthestResult = results.top();
            if (current.distance > furthestResult.distance) break;

            const HnswNode& currNode = nodes_.at(current.nodeId);
            if (lc < static_cast<int>(currNode.neighbors.size())) {
                for (int neighborId : currNode.neighbors[lc]) {
                    if (visited.find(neighborId) == visited.end()) {
                        visited.insert(neighborId);
                        const HnswNode& neighborNode = nodes_.at(neighborId);
                        float neighborDist = carina::engine::math::VectorMath::l2DistanceSquare(query, neighborNode.vector);

                        if (static_cast<int>(results.size()) < ef || neighborDist < results.top().distance) {
                            candidates.push({neighborId, neighborDist});
                            results.push({neighborId, neighborDist});
                            if (static_cast<int>(results.size()) > ef) results.pop();
                        }
                    }
                }
            }
        }

        std::vector<NodeDistance> out;
        while (!results.empty()) { out.push_back(results.top()); results.pop(); }
        return out; // 与 Java 的 results (大顶堆) 语义一致：无序、只保证是 ef 个最近候选
    }

    void insert(int id, const std::vector<float>& vector) {
        int targetLevel = randomLevel();

        if (nodes_.empty()) {
            enterPointId_ = id;
            maxLayer_ = targetLevel;
            nodes_.emplace(id, HnswNode(id, vector, targetLevel, maxM_, maxM0_));
            return;
        }

        // 必须在连边之前注册，理由同 Java 版注释：邻居溢出裁剪按 id 反查 nodes_。
        nodes_.emplace(id, HnswNode(id, vector, targetLevel, maxM_, maxM0_));
        HnswNode* newNodePtr = &nodes_.at(id);

        int currObj = enterPointId_;
        int currMaxLayer = maxLayer_;
        for (int lc = currMaxLayer; lc > targetLevel; lc--) {
            auto nearest = searchLayer(vector, currObj, 1, lc);
            currObj = nearest.front().nodeId;
        }

        int minLayer = std::min(currMaxLayer, targetLevel);
        for (int lc = minLayer; lc >= 0; lc--) {
            auto candidates = searchLayer(vector, currObj, efConstruction_, lc);
            int layerMaxM = (lc == 0) ? maxM0_ : maxM_;
            std::vector<int> selectNeighbors = selectHeuristic(candidates, layerMaxM);

            int nextObj = selectNeighbors.empty() ? currObj : selectNeighbors.front();

            for (int neighborId : selectNeighbors) {
                newNodePtr->neighbors[lc].push_back(neighborId);
                HnswNode& neighborNode = nodes_.at(neighborId);
                neighborNode.neighbors[lc].push_back(id);
                if (static_cast<int>(neighborNode.neighbors[lc].size()) > layerMaxM) {
                    std::vector<NodeDistance> neighborCandidates;
                    for (int nbrId : neighborNode.neighbors[lc]) {
                        float dist = carina::engine::math::VectorMath::l2DistanceSquare(neighborNode.vector, nodes_.at(nbrId).vector);
                        neighborCandidates.push_back({nbrId, dist});
                    }
                    std::vector<int> pruned = selectHeuristic(neighborCandidates, layerMaxM);
                    neighborNode.neighbors[lc] = std::move(pruned);
                }
            }
            currObj = nextObj;
        }
        if (targetLevel > maxLayer_) {
            maxLayer_ = targetLevel;
            enterPointId_ = id;
        }
    }

    std::vector<NodeDistance> search(const std::vector<float>& query, int k) const {
        if (nodes_.empty()) return {};

        int currObj = enterPointId_;
        for (int lc = maxLayer_; lc >= 1; lc--) {
            auto nearest = searchLayer(query, currObj, 1, lc);
            currObj = nearest.front().nodeId;
        }

        auto candidates = searchLayer(query, currObj, efConstruction_, 0);
        std::sort(candidates.begin(), candidates.end(),
                  [](const NodeDistance& a, const NodeDistance& b) { return a.distance < b.distance; });
        if (static_cast<int>(candidates.size()) > k) candidates.resize(k);
        return candidates;
    }

private:
    // 启发式裁剪：候选按距离升序处理，保留互不冗余的邻居
    std::vector<int> selectHeuristic(std::vector<NodeDistance> candidates, int layerMaxM) const {
        std::vector<int> selected;
        selected.reserve(layerMaxM);
        std::sort(candidates.begin(), candidates.end(),
                  [](const NodeDistance& a, const NodeDistance& b) { return a.distance < b.distance; });

        for (const auto& candidate : candidates) {
            if (static_cast<int>(selected.size()) >= layerMaxM) break;
            const std::vector<float>& candidateVector = nodes_.at(candidate.nodeId).vector;
            bool keep = true;
            for (int selId : selected) {
                const std::vector<float>& selVector = nodes_.at(selId).vector;
                float distToSelect = carina::engine::math::VectorMath::l2DistanceSquare(candidateVector, selVector);
                if (distToSelect <= candidate.distance) { keep = false; break; }
            }
            if (keep) selected.push_back(candidate.nodeId);
        }
        return selected;
    }

    int randomLevel() const {
        static thread_local std::mt19937 rng(std::random_device{}());
        static thread_local std::uniform_real_distribution<double> dist(0.0, 1.0);
        double r = -std::log(dist(rng)) * mL_;
        return static_cast<int>(r);
    }

    std::unordered_map<int, HnswNode> nodes_;
    int enterPointId_ = -1;
    int maxLayer_ = -1;
    int maxM_;
    int maxM0_;
    double mL_;
    int efConstruction_;
};

} // namespace carina::engine::index::hnsw
