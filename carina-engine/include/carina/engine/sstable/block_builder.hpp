#pragma once
#include "carina/common/byte_io.hpp"
#include <vector>
#include <cstdint>

// 对应 com.jiashi.db.engine.sstable.BlockBuilder：在内存中构建 4KB 数据块
// 记录格式：Type(1)+KeyLen(4)+ValLen(4)+PtrLen(4)+Key+Val+Ptr
namespace carina::engine::sstable {

class BlockBuilder {
public:
    static constexpr size_t BLOCK_SIZE = 4096;

    bool add(const std::vector<uint8_t>& key, const std::vector<uint8_t>& value, uint8_t type,
              const std::optional<std::vector<uint8_t>>& pointer) {
        size_t keyLen = key.size();
        size_t valLen = value.size();
        size_t ptrLen = pointer.has_value() ? pointer->size() : 0;
        size_t requiredSpace = 13 + keyLen + valLen + ptrLen;
        if (buffer_.size() + requiredSpace > BLOCK_SIZE) return false;

        buffer_.push_back(type);
        carina::common::putInt32BE(buffer_, static_cast<int32_t>(keyLen));
        carina::common::putInt32BE(buffer_, static_cast<int32_t>(valLen));
        carina::common::putInt32BE(buffer_, static_cast<int32_t>(ptrLen));
        if (keyLen > 0) carina::common::putBytes(buffer_, key);
        if (valLen > 0) carina::common::putBytes(buffer_, value);
        if (ptrLen > 0) carina::common::putBytes(buffer_, *pointer);
        return true;
    }

    const std::vector<uint8_t>& finish() const { return buffer_; }
    void reset() { buffer_.clear(); }
    bool isEmpty() const { return buffer_.empty(); }

private:
    std::vector<uint8_t> buffer_;
};

} // namespace carina::engine::sstable
