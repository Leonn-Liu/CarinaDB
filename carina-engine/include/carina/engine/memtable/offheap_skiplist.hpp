#pragma once
#include "carina/engine/memtable/arena.hpp"
#include "carina/engine/memtable/node_accessor.hpp"
#include "carina/common/log_record.hpp"
#include <array>
#include <random>
#include <optional>
#include <functional>

// 对应 com.jiashi.db.engine.memtable.OffHeapSkipList：工业级堆外无锁跳表。
// Java 版用 sun.misc.Unsafe.compareAndSwapInt 在物理地址上做 CAS；
// C++ 版用 Arena::atomicIntAt() 把同一块内存当作 std::atomic<int32_t> 做
// compare_exchange_strong，语义完全等价，且不再需要反射拿地址的黑魔法。
namespace carina::engine::memtable {

class OffHeapSkipList {
public:
    static constexpr int MAX_HEIGHT = 12;
    static constexpr double PROBABILITY = 0.25;

    explicit OffHeapSkipList(Arena& arena) : arena_(arena) {
        carina::common::LogRecord dummy(0, {}, {}, std::nullopt);
        headOffset_ = NodeAccessor::allocateAndWriteNode(arena_, dummy, MAX_HEIGHT);
    }

    void put(const carina::common::LogRecord& record) {
        int randomHeight = randomHeight_();
        int newNodeOffset = NodeAccessor::allocateAndWriteNode(arena_, record, randomHeight);

        std::array<int, MAX_HEIGHT> preds{};
        std::array<int, MAX_HEIGHT> succs{};

        while (true) {
            findPredecessors(record.getKey(), preds, succs);

            for (int i = 0; i < randomHeight; i++) {
                NodeAccessor::setNextOffset(arena_, newNodeOffset, i, succs[i]);
            }

            int predLevel0 = preds[0];
            int succLevel0 = succs[0];
            std::atomic<int32_t>* predNextPtr =
                arena_.atomicIntAt(NodeAccessor::getNextPointerAbsoluteAddress(predLevel0, 0));

            int32_t expected = succLevel0;
            if (!predNextPtr->compare_exchange_strong(expected, newNodeOffset)) {
                continue; // 有人插队，重新寻路重试
            }

            for (int i = 1; i < randomHeight; i++) {
                while (true) {
                    std::atomic<int32_t>* upperPtr =
                        arena_.atomicIntAt(NodeAccessor::getNextPointerAbsoluteAddress(preds[i], i));
                    int32_t expectedUpper = succs[i];
                    if (upperPtr->compare_exchange_strong(expectedUpper, newNodeOffset)) {
                        break;
                    }
                    findPredecessors(record.getKey(), preds, succs);
                }
            }
            break;
        }
    }

    void remove(const std::vector<uint8_t>& key) {
        carina::common::LogRecord tombstone(carina::common::LogRecordType::DELETE, key, {}, std::nullopt);
        put(tombstone);
    }

    // 无锁读取：返回包含 pointer 的完整 LogRecord
    std::optional<carina::common::LogRecord> getRecord(const std::vector<uint8_t>& targetKey) const {
        int currentOffset = headOffset_;
        for (int level = MAX_HEIGHT - 1; level >= 0; level--) {
            int nextOffset = NodeAccessor::getNextOffset(arena_, currentOffset, level);
            while (nextOffset != 0) {
                std::vector<uint8_t> nextKey = NodeAccessor::getKey(arena_, nextOffset);
                int cmp = compareKeys(nextKey, targetKey);
                if (cmp < 0) {
                    currentOffset = nextOffset;
                    nextOffset = NodeAccessor::getNextOffset(arena_, currentOffset, level);
                } else if (cmp == 0) {
                    // 多版本事实：同 key 的新版本（含墓碑）永远插在等值区头部，但旧节点的塔
                    // 可能更高——高层撞到的等值节点不保证是最新版本，只有 Level 0 等值区的
                    // 第一个节点才是最新版本，可以放心返回。
                    if (level > 0) break;
                    uint8_t type = NodeAccessor::getType(arena_, nextOffset);
                    int valLen = NodeAccessor::getValueLength(arena_, nextOffset);
                    int height = NodeAccessor::getHeight(arena_, nextOffset);
                    std::vector<uint8_t> value(valLen);
                    int valStart = nextOffset + NodeAccessor::HEADER_SIZE + (height * 4) + static_cast<int>(nextKey.size());
                    arena_.getBytes(valStart, value.data(), valLen);

                    int ptrLen = NodeAccessor::getPointerLength(arena_, nextOffset);
                    std::optional<std::vector<uint8_t>> pointer;
                    if (ptrLen > 0) {
                        std::vector<uint8_t> ptr(ptrLen);
                        arena_.getBytes(valStart + valLen, ptr.data(), ptrLen);
                        pointer = std::move(ptr);
                    }
                    return carina::common::LogRecord(type, nextKey, value, pointer);
                } else {
                    break;
                }
            }
        }
        return std::nullopt;
    }

    // 顺序遍历（Level 0），用于 Flush 到 SSTable
    void forEach(const std::function<void(const carina::common::LogRecord&)>& visitor) const {
        int currentOffset = NodeAccessor::getNextOffset(arena_, headOffset_, 0);
        while (currentOffset != 0) {
            uint8_t type = NodeAccessor::getType(arena_, currentOffset);
            std::vector<uint8_t> key = NodeAccessor::getKey(arena_, currentOffset);
            int valLen = NodeAccessor::getValueLength(arena_, currentOffset);
            int height = NodeAccessor::getHeight(arena_, currentOffset);
            std::vector<uint8_t> value(valLen);
            int valStart = currentOffset + NodeAccessor::HEADER_SIZE + (height * 4) + static_cast<int>(key.size());
            arena_.getBytes(valStart, value.data(), valLen);

            int ptrLen = NodeAccessor::getPointerLength(arena_, currentOffset);
            std::optional<std::vector<uint8_t>> pointer;
            if (ptrLen > 0) {
                std::vector<uint8_t> ptr(ptrLen);
                arena_.getBytes(valStart + valLen, ptr.data(), ptrLen);
                pointer = std::move(ptr);
            }
            visitor(carina::common::LogRecord(type, key, value, pointer));
            currentOffset = NodeAccessor::getNextOffset(arena_, currentOffset, 0);
        }
    }

private:
    int randomHeight_() {
        static thread_local std::mt19937 rng(std::random_device{}());
        static thread_local std::uniform_real_distribution<double> dist(0.0, 1.0);
        int height = 1;
        while (height < MAX_HEIGHT && dist(rng) < PROBABILITY) height++;
        return height;
    }

    void findPredecessors(const std::vector<uint8_t>& targetKey,
                           std::array<int, MAX_HEIGHT>& preds,
                           std::array<int, MAX_HEIGHT>& succs) const {
        int currentOffset = headOffset_;
        for (int level = MAX_HEIGHT - 1; level >= 0; level--) {
            int nextOffset = NodeAccessor::getNextOffset(arena_, currentOffset, level);
            while (nextOffset != 0) {
                std::vector<uint8_t> nextKey = NodeAccessor::getKey(arena_, nextOffset);
                if (compareKeys(nextKey, targetKey) < 0) {
                    currentOffset = nextOffset;
                    nextOffset = NodeAccessor::getNextOffset(arena_, currentOffset, level);
                } else {
                    break;
                }
            }
            preds[level] = currentOffset;
            succs[level] = nextOffset;
        }
    }

    static int compareKeys(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
        size_t minLen = std::min(a.size(), b.size());
        for (size_t i = 0; i < minLen; i++) {
            int av = a[i], bv = b[i];
            if (av != bv) return av - bv;
        }
        return static_cast<int>(a.size()) - static_cast<int>(b.size());
    }

    Arena& arena_;
    int headOffset_;
};

} // namespace carina::engine::memtable
