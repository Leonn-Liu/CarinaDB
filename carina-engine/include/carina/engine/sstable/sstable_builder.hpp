#pragma once
#include "carina/engine/sstable/block_builder.hpp"
#include "carina/engine/sstable/footer.hpp"
#include "carina/common/bloom_filter.hpp"
#include "carina/common/byte_io.hpp"
#include <string>
#include <vector>
#include <optional>
#include <stdexcept>
#include <fcntl.h>
#include <unistd.h>

// 对应 com.jiashi.db.engine.sstable.SSTableBuilder：核心写链路
namespace carina::engine::sstable {

class SSTableBuilder {
public:
    SSTableBuilder(const std::string& filePath, int expectedInsertions)
        : filePath_(filePath), bloomFilter_(expectedInsertions, 0.01) {
        fd_ = ::open(filePath.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
        if (fd_ < 0) throw std::runtime_error("SSTableBuilder: failed to open " + filePath);
    }

    void add(const std::vector<uint8_t>& key, const std::vector<uint8_t>& value, uint8_t type,
              const std::optional<std::vector<uint8_t>>& pointer) {
        if (!hasMinKey_) { minKey_ = key; hasMinKey_ = true; }
        maxKey_ = key;
        bloomFilter_.add(key);
        if (!dataBlockBuilder_.add(key, value, type, pointer)) {
            flushDataBlock();
            dataBlockBuilder_.add(key, value, type, pointer);
        }
        lastKey_ = key;
    }

    void finish() {
        flushDataBlock();

        int64_t filterOffset = currentOffset_;
        std::vector<uint8_t> filterBytes = bloomFilter_.serialize();
        writeRaw(filterBytes.data(), filterBytes.size());

        int64_t indexBlockOffset = currentOffset_;
        std::vector<uint8_t> indexBuf;
        for (auto& entry : memIndex_) {
            carina::common::putInt32BE(indexBuf, static_cast<int32_t>(entry.maxKey.size()));
            carina::common::putBytes(indexBuf, entry.maxKey);
            carina::common::putInt32BE(indexBuf, 8);
            carina::common::putInt64BE(indexBuf, entry.blockOffset);
        }
        writeRaw(indexBuf.data(), indexBuf.size());

        int64_t minKeyOffset = currentOffset_;
        writeKeyWithLength(minKey_);
        int64_t maxKeyOffset = currentOffset_;
        writeKeyWithLength(maxKey_);

        Footer::writeFooter(fd_, filterOffset, indexBlockOffset, minKeyOffset, maxKeyOffset);
        ::fsync(fd_);
        ::close(fd_);
        fd_ = -1;
    }

private:
    struct IndexEntry {
        std::vector<uint8_t> maxKey;
        int64_t blockOffset;
    };

    void flushDataBlock() {
        if (dataBlockBuilder_.isEmpty()) return;
        const auto& data = dataBlockBuilder_.finish();
        memIndex_.push_back({lastKey_, currentOffset_});
        writeRaw(data.data(), data.size());
        dataBlockBuilder_.reset();
    }

    void writeKeyWithLength(const std::vector<uint8_t>& key) {
        std::vector<uint8_t> buf;
        carina::common::putInt32BE(buf, static_cast<int32_t>(key.size()));
        carina::common::putBytes(buf, key);
        writeRaw(buf.data(), buf.size());
    }

    void writeRaw(const uint8_t* data, size_t len) {
        size_t written = 0;
        while (written < len) {
            ssize_t n = ::write(fd_, data + written, len - written);
            if (n <= 0) throw std::runtime_error("SSTableBuilder: write failed");
            written += static_cast<size_t>(n);
        }
        currentOffset_ += static_cast<int64_t>(len);
    }

    std::string filePath_;
    int fd_ = -1;
    BlockBuilder dataBlockBuilder_;
    carina::common::BloomFilter bloomFilter_;
    std::vector<IndexEntry> memIndex_;
    std::vector<uint8_t> minKey_, maxKey_, lastKey_;
    bool hasMinKey_ = false;
    int64_t currentOffset_ = 0;
};

} // namespace carina::engine::sstable
