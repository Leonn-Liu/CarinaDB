#pragma once
#include "carina/common/byte_io.hpp"
#include <cmath>
#include <vector>
#include <cstdint>
#include <algorithm>

// 对应 com.jiashi.db.common.filter.BloomFilter。
// 位图的物理打包方式是本次 C++ 重写自定义的（bitIndex -> byte[i/8] 的第 i%8 位），
// 不要求与旧 Java 版本的 BitSet 序列化字节兼容，但 add/mightContain/序列化/反序列化
// 在本系统内部自洽，算法（murmur 式简单哈希、误判率参数计算）与 Java 版一致。
namespace carina::common {

class BloomFilter {
public:
    // 构造 1：供 Builder 全新创建
    BloomFilter(int expectedInsertions, double fpp) {
        bitSize_ = static_cast<int>(-expectedInsertions * std::log(fpp) / (std::log(2.0) * std::log(2.0)));
        if (bitSize_ < 1) bitSize_ = 1;
        int hashes = static_cast<int>(std::llround(static_cast<double>(bitSize_) / expectedInsertions * std::log(2.0)));
        numHashFunctions_ = std::max(1, hashes);
        bits_.assign((bitSize_ + 7) / 8, 0);
    }

    // 构造 2：供 Reader 从序列化字节精准还原
    explicit BloomFilter(const std::vector<uint8_t>& data) {
        ByteReader in(data.data(), data.size());
        bitSize_ = in.getInt32BE();
        numHashFunctions_ = in.getInt32BE();
        bits_ = in.getBytes(in.remaining());
    }

    void add(const std::vector<uint8_t>& key) {
        for (int i = 0; i < numHashFunctions_; i++) {
            uint32_t h = murmurHash3(key, i);
            setBit(h % static_cast<uint32_t>(bitSize_));
        }
    }

    bool mightContain(const std::vector<uint8_t>& key) const {
        for (int i = 0; i < numHashFunctions_; i++) {
            uint32_t h = murmurHash3(key, i);
            if (!getBit(h % static_cast<uint32_t>(bitSize_))) return false;
        }
        return true;
    }

    // 序列化协议：[bitSize(4)][numHashFunctions(4)][bits...]
    std::vector<uint8_t> serialize() const {
        std::vector<uint8_t> out;
        out.reserve(8 + bits_.size());
        putInt32BE(out, bitSize_);
        putInt32BE(out, numHashFunctions_);
        putBytes(out, bits_);
        return out;
    }

private:
    void setBit(uint32_t idx) { bits_[idx / 8] |= static_cast<uint8_t>(1u << (idx % 8)); }
    bool getBit(uint32_t idx) const { return (bits_[idx / 8] & static_cast<uint8_t>(1u << (idx % 8))) != 0; }

    // 与 Java 版一致的简单乘法哈希：h = 31*h + b，以 seed 作为初始值区分多个哈希函数
    static uint32_t murmurHash3(const std::vector<uint8_t>& data, int seed) {
        int32_t h = seed;
        for (uint8_t b : data) {
            h = 31 * h + static_cast<int8_t>(b);
        }
        return static_cast<uint32_t>(h) & 0x7FFFFFFFu; // 等价于 Java 的 hash & Integer.MAX_VALUE
    }

    int bitSize_;
    int numHashFunctions_;
    std::vector<uint8_t> bits_;
};

} // namespace carina::common
