#pragma once
#include "carina/engine/memtable/arena.hpp"
#include "carina/engine/memtable/offheap_skiplist.hpp"
#include "carina/engine/wal/wal.hpp"
#include "carina/engine/blob/blob_writer.hpp"
#include "carina/common/log_record.hpp"
#include "carina/common/log_record_coder.hpp"
#include <atomic>
#include <memory>
#include <functional>
#include <iostream>

// 对应 com.jiashi.db.engine.memtable.MemTable：统一接管 WAL 日志与无锁跳表的写入时序
namespace carina::engine::memtable {

class MemTable {
public:
    MemTable(int capacity, const std::string& walDirectory, std::string walFileName)
        : arena_(capacity), skipList_(arena_),
          wal_(walDirectory, walFileName), walFileName_(std::move(walFileName)),
          memoryThreshold_(static_cast<int>(capacity * 0.90)) {}

    std::optional<carina::common::LogRecord> getRecord(const std::vector<uint8_t>& key) const {
        return skipList_.getRecord(key);
    }

    // 对外暴露有序数据流，专供 Flush 到 SSTable 使用；只允许对 Immutable 的 MemTable 迭代
    void forEach(const std::function<void(const carina::common::LogRecord&)>& visitor) const {
        if (!isImmutable_.load()) {
            throw std::runtime_error("Cannot iterate a mutable MemTable. Flush is only allowed for Immutable MemTable.");
        }
        skipList_.forEach(visitor);
    }

    bool put(uint8_t type, const std::vector<uint8_t>& key, const std::vector<uint8_t>& value,
             std::optional<std::vector<uint8_t>> pointerBytes) {
        if (isImmutable_.load()) return false;
        carina::common::LogRecord record(type, key, value, std::move(pointerBytes));
        try {
            wal_.append(carina::common::LogRecordCoder::encode(record));
            skipList_.put(record);
            checkMemoryUsage();
            return true;
        } catch (const std::exception& e) {
            std::cerr << "MemTable 统一写入失败: " << e.what() << std::endl;
            return false;
        }
    }

    void setBlobWriter(std::shared_ptr<carina::engine::blob::BlobWriter> blobWriter) {
        blobWriter_ = blobWriter;
        wal_.setBlobSyncCallback([w = blobWriter]() { if (w) w->sync(); });
    }

    const std::string& getWalFileName() const { return walFileName_; }

    // 用于引擎启动时的 WAL 宕机恢复：仅写入 SkipList，不写本 MemTable 的 WAL
    void restoreFromWal(const carina::common::LogRecord& record) {
        skipList_.put(record);
        checkMemoryUsage();
    }

    void appendToWal(const carina::common::LogRecord& record) {
        wal_.append(carina::common::LogRecordCoder::encode(record));
    }

    bool isImmutable() const { return isImmutable_.load(); }
    void freeze() { isImmutable_.store(true); }

    void close() { wal_.close(); }

private:
    void checkMemoryUsage() {
        if (arena_.memoryUsage() >= memoryThreshold_) {
            bool expected = false;
            isImmutable_.compare_exchange_strong(expected, true);
        }
    }

    Arena arena_;
    OffHeapSkipList skipList_;
    carina::engine::wal::Wal wal_;
    std::string walFileName_;
    int memoryThreshold_;
    std::atomic<bool> isImmutable_{false};
    std::shared_ptr<carina::engine::blob::BlobWriter> blobWriter_;
};

} // namespace carina::engine::memtable
