#pragma once
#include "carina/engine/sstable/footer.hpp"
#include "carina/engine/cache/block_cache.hpp"
#include "carina/common/bloom_filter.hpp"
#include "carina/common/log_record.hpp"
#include "carina/common/byte_io.hpp"
#include <string>
#include <vector>
#include <optional>
#include <stdexcept>
#include <fcntl.h>
#include <unistd.h>

// 对应 com.jiashi.db.engine.sstable.SSTableReader：三级 LRU 缓存的按需加载读链路
namespace carina::engine::sstable {

class SSTableReader {
public:
    struct IndexEntry {
        std::vector<uint8_t> maxKey;
        int64_t blockOffset;
    };

    explicit SSTableReader(const std::string& filePath) : filePath_(filePath) {
        auto slash = filePath.find_last_of('/');
        fileId_ = (slash == std::string::npos) ? filePath : filePath.substr(slash + 1);

        fd_ = ::open(filePath.c_str(), O_RDONLY);
        if (fd_ < 0) throw std::runtime_error("SSTableReader: failed to open " + filePath);
        int64_t fileSize = ::lseek(fd_, 0, SEEK_END);

        std::vector<uint8_t> footerBuf(Footer::ENCODED_LENGTH);
        pread(footerBuf, fileSize - Footer::ENCODED_LENGTH);
        carina::common::ByteReader in(footerBuf.data(), footerBuf.size());
        filterOffset_ = in.getInt64BE();
        indexBlockOffset_ = in.getInt64BE();
        minKeyOffset_ = in.getInt64BE();
        maxKeyOffset_ = in.getInt64BE();
        int64_t magic = in.getInt64BE();
        if (magic != Footer::MAGIC_NUMBER) {
            throw std::runtime_error("SSTable 魔数校验失败，文件损坏或非 CarinaDB 文件！");
        }

        minKey_ = readKeyWithLength(minKeyOffset_);
        maxKey_ = readKeyWithLength(maxKeyOffset_);
    }

    ~SSTableReader() { close(); }

    const std::vector<uint8_t>& getMinKey() const { return minKey_; }
    const std::vector<uint8_t>& getMaxKey() const { return maxKey_; }
    const std::string& getFileId() const { return fileId_; }

    std::optional<carina::common::LogRecord> searchBinaryFullRecord(const std::vector<uint8_t>& targetKey) {
        carina::common::BloomFilter& filter = getBloomFilterLazy();
        if (!filter.mightContain(targetKey)) return std::nullopt;

        const std::vector<IndexEntry>& indexEntries = getIndexBlockLazy();
        int64_t targetBlockOffset = -1, nextBlockOffset = -1;
        int low = 0, high = static_cast<int>(indexEntries.size()) - 1;
        while (low <= high) {
            int mid = (low + high) >> 1;
            const IndexEntry& midEntry = indexEntries[mid];
            int cmp = compareBytes(targetKey, midEntry.maxKey);
            if (cmp == 0) {
                targetBlockOffset = midEntry.blockOffset;
                if (mid + 1 < static_cast<int>(indexEntries.size())) nextBlockOffset = indexEntries[mid + 1].blockOffset;
                break;
            } else if (cmp < 0) {
                targetBlockOffset = midEntry.blockOffset;
                if (mid + 1 < static_cast<int>(indexEntries.size())) nextBlockOffset = indexEntries[mid + 1].blockOffset;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        if (targetBlockOffset == -1) return std::nullopt;

        const std::vector<uint8_t>& dataBlock = getDataBlockLazy(targetBlockOffset, nextBlockOffset);
        carina::common::ByteReader reader(dataBlock.data(), dataBlock.size());
        while (reader.remaining() > 0) {
            uint8_t type = reader.getByte();
            int32_t keyLen = reader.getInt32BE();
            if (keyLen <= 0) break;
            int32_t valLen = reader.getInt32BE();
            int32_t ptrLen = reader.getInt32BE();
            std::vector<uint8_t> currentKey = reader.getBytes(keyLen);
            std::vector<uint8_t> currentValue = reader.getBytes(valLen);
            std::optional<std::vector<uint8_t>> currentPointer;
            if (ptrLen > 0) currentPointer = reader.getBytes(ptrLen);
            if (currentKey == targetKey) {
                return carina::common::LogRecord(type, currentKey, currentValue, currentPointer);
            }
        }
        return std::nullopt;
    }

    void close() {
        if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
    }

private:
    static int compareBytes(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
        size_t lim = std::min(a.size(), b.size());
        for (size_t i = 0; i < lim; i++) {
            if (a[i] != b[i]) return int(a[i]) - int(b[i]);
        }
        return static_cast<int>(a.size()) - static_cast<int>(b.size());
    }

    void pread(std::vector<uint8_t>& buf, int64_t offset) const {
        size_t got = 0;
        while (got < buf.size()) {
            ssize_t n = ::pread(fd_, buf.data() + got, buf.size() - got, offset + static_cast<int64_t>(got));
            if (n <= 0) break;
            got += static_cast<size_t>(n);
        }
    }

    std::vector<uint8_t> readKeyWithLength(int64_t offset) const {
        std::vector<uint8_t> lenBuf(4);
        pread(lenBuf, offset);
        carina::common::ByteReader lin(lenBuf.data(), lenBuf.size());
        int32_t len = lin.getInt32BE();
        std::vector<uint8_t> keyBuf(len);
        pread(keyBuf, offset + 4);
        return keyBuf;
    }

    carina::common::BloomFilter& getBloomFilterLazy() {
        auto cached = FILTER_CACHE().get(fileId_);
        if (cached.has_value()) {
            filterHolder_ = std::make_unique<carina::common::BloomFilter>(*cached);
            return *filterHolder_;
        }
        int64_t filterSize = indexBlockOffset_ - filterOffset_;
        std::vector<uint8_t> buf(filterSize);
        pread(buf, filterOffset_);
        carina::common::BloomFilter filter(buf);
        FILTER_CACHE().put(fileId_, filter);
        filterHolder_ = std::make_unique<carina::common::BloomFilter>(filter);
        return *filterHolder_;
    }

    const std::vector<IndexEntry>& getIndexBlockLazy() {
        auto cached = INDEX_CACHE().get(fileId_);
        if (cached.has_value()) {
            indexHolder_ = std::make_unique<std::vector<IndexEntry>>(*cached);
            return *indexHolder_;
        }
        int64_t indexSize = minKeyOffset_ - indexBlockOffset_;
        std::vector<uint8_t> buf(indexSize);
        pread(buf, indexBlockOffset_);
        carina::common::ByteReader reader(buf.data(), buf.size());
        std::vector<IndexEntry> entries;
        while (reader.remaining() > 0) {
            int32_t keyLen = reader.getInt32BE();
            std::vector<uint8_t> key = reader.getBytes(keyLen);
            reader.getInt32BE(); // valLen, 固定为 8
            int64_t blockOffset = reader.getInt64BE();
            entries.push_back({key, blockOffset});
        }
        INDEX_CACHE().put(fileId_, entries);
        indexHolder_ = std::make_unique<std::vector<IndexEntry>>(std::move(entries));
        return *indexHolder_;
    }

    const std::vector<uint8_t>& getDataBlockLazy(int64_t targetBlockOffset, int64_t nextBlockOffset) {
        std::string cacheKey = fileId_ + "_data_" + std::to_string(targetBlockOffset);
        auto cached = DATA_BLOCK_CACHE().get(cacheKey);
        if (cached.has_value()) {
            dataBlockHolder_ = std::make_unique<std::vector<uint8_t>>(*cached);
            return *dataBlockHolder_;
        }
        int64_t blockSize = (nextBlockOffset != -1) ? (nextBlockOffset - targetBlockOffset)
                                                     : (filterOffset_ - targetBlockOffset);
        std::vector<uint8_t> buf(blockSize);
        pread(buf, targetBlockOffset);
        DATA_BLOCK_CACHE().put(cacheKey, buf);
        dataBlockHolder_ = std::make_unique<std::vector<uint8_t>>(std::move(buf));
        return *dataBlockHolder_;
    }

    // 全局共享的三级缓存：所有 SSTableReader 实例共用同一份配额（对应 Java 版 static 字段）
    static carina::engine::cache::BlockCache<std::string, std::vector<uint8_t>>& DATA_BLOCK_CACHE() {
        static carina::engine::cache::BlockCache<std::string, std::vector<uint8_t>> cache(1000);
        return cache;
    }
    static carina::engine::cache::BlockCache<std::string, carina::common::BloomFilter>& FILTER_CACHE() {
        static carina::engine::cache::BlockCache<std::string, carina::common::BloomFilter> cache(100);
        return cache;
    }
    static carina::engine::cache::BlockCache<std::string, std::vector<IndexEntry>>& INDEX_CACHE() {
        static carina::engine::cache::BlockCache<std::string, std::vector<IndexEntry>> cache(100);
        return cache;
    }

    std::string filePath_;
    std::string fileId_;
    int fd_ = -1;
    std::vector<uint8_t> minKey_, maxKey_;
    int64_t filterOffset_, indexBlockOffset_, minKeyOffset_, maxKeyOffset_;

    std::unique_ptr<carina::common::BloomFilter> filterHolder_;
    std::unique_ptr<std::vector<IndexEntry>> indexHolder_;
    std::unique_ptr<std::vector<uint8_t>> dataBlockHolder_;
};

} // namespace carina::engine::sstable
