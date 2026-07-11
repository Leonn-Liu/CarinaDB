#pragma once
#include "carina/engine/compaction/merge_entry.hpp"
#include <algorithm>

// 对应 com.jiashi.db.engine.compaction.CompactionComparator：
// Key 小的优先出列；Key 相同时 FileId 大的优先出列。
// 用作 std::priority_queue 的比较器时需要“反过来”写：
// Greater(a,b) 返回 true 表示 a 的优先级低于 b（std::priority_queue 是大顶堆，
// top() 永远是 Greater 意义下的“最大值”，我们要让它等于 Java 里 poll() 出来的那个）。
namespace carina::engine::compaction {

struct CompactionGreater {
    bool operator()(const MergeEntry& a, const MergeEntry& b) const {
        int cmp = compareKeys(a.record.getKey(), b.record.getKey());
        if (cmp != 0) return cmp > 0; // key 更大的 a 优先级更低
        return a.fileId < b.fileId;   // fileId 更小的 a 优先级更低（大的优先出列）
    }

private:
    static int compareKeys(const std::vector<uint8_t>& k1, const std::vector<uint8_t>& k2) {
        size_t minLen = std::min(k1.size(), k2.size());
        for (size_t i = 0; i < minLen; i++) {
            if (k1[i] != k2[i]) return int(k1[i]) - int(k2[i]);
        }
        return static_cast<int>(k1.size()) - static_cast<int>(k2.size());
    }
};

} // namespace carina::engine::compaction
