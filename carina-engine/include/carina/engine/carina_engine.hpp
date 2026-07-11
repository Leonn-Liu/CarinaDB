#pragma once
#include "carina/engine/memtable/memtable.hpp"
#include "carina/engine/blob/blob_pointer.hpp"
#include "carina/engine/blob/blob_reader.hpp"
#include "carina/engine/blob/blob_writer.hpp"
#include "carina/engine/sstable/sstable_reader.hpp"
#include "carina/engine/sstable/sstable_builder.hpp"
#include "carina/engine/sstable/sstable_scanner.hpp"
#include "carina/engine/compaction/compactor.hpp"
#include "carina/engine/index/hnsw/hnsw_index.hpp"
#include "carina/common/log_record.hpp"

#include <filesystem>
#include <thread>
#include <atomic>
#include <mutex>
#include <shared_mutex>
#include <deque>
#include <unordered_map>
#include <optional>
#include <chrono>
#include <iostream>
#include <algorithm>
#include <cstdio>
#include <cctype>

// 对应 com.jiashi.db.engine.CarinaEngine：负责 MemTable 调度、WiscKey 键值分离、
// 后台刷盘与读写管线。整体状态机、阈值、文件命名规则均与 Java 版保持一致。
namespace carina::engine {

namespace fs = std::filesystem;

class CarinaEngine {
public:
    struct QueryResult {
        std::vector<uint8_t> value;
        std::optional<std::vector<float>> vector;
    };

    static constexpr int MEMTABLE_CAPACITY = 64 * 1024 * 1024; // 64MB
    static constexpr int L0_COMPACTION_TRIGGER = 4;

    explicit CarinaEngine(const std::string& dbDirectory) : dbDirectory_(dbDirectory), hnswIndex_(16, 200) {
        if (!fs::exists(dbDirectory_)) fs::create_directories(dbDirectory_);

        loadSSTables();
        sstFileIdGenerator_ = loadMaxSSTableId();
        blobFileIdGenerator_ = loadMaxBlobFileId();
        if (blobFileIdGenerator_ == 0) blobFileIdGenerator_ = 1;

        std::string blobPath = dbDirectory_ + "/" + formatBlobName(blobFileIdGenerator_);
        blobWriter_ = std::make_shared<blob::BlobWriter>(blobPath, blobFileIdGenerator_);

        loadAllBlobReaders();

        activeMemTable_ = createNewMemTable();

        recoverFromOldWals();

        compactor_ = std::make_unique<compaction::Compactor>(dbDirectory_);
        stopCompaction_ = false;
        compactionThread_ = std::thread([this]() {
            while (!stopCompaction_.load()) {
                std::this_thread::sleep_for(std::chrono::seconds(10));
                if (stopCompaction_.load()) break;
                backgroundCompaction();
            }
        });

        std::cout << "CarinaDB 启动成功，已挂载 " << ssTables_.size() << " 个 SSTable。" << std::endl;
    }

    ~CarinaEngine() { close(); }

    CarinaEngine(const CarinaEngine&) = delete;
    CarinaEngine& operator=(const CarinaEngine&) = delete;

    // 核心写链路 —— 带向量的高维数据写入
    void put(const std::vector<uint8_t>& key, const std::vector<uint8_t>& value,
             const std::optional<std::vector<float>>& vector) {
        std::optional<std::vector<uint8_t>> pointerBytes;
        uint8_t type = carina::common::LogRecordType::PUT_KV;

        if (vector.has_value() && !vector->empty()) {
            auto pointer = blobWriter_->append(*vector);
            if (pointer.has_value()) {
                pointerBytes = pointer->toBytes();
                type = carina::common::LogRecordType::PUT_VECTOR;
            }
            int vectorId = vectorIdGenerator_.fetch_add(1);
            {
                std::lock_guard<std::mutex> lg(vectorIdToKeyMutex_);
                vectorIdToKey_[vectorId] = key;
            }
            hnswIndex_.insert(vectorId, *vector);
        }

        bool success = activeMemTable_->put(type, key, value, pointerBytes);
        if (!success) {
            std::lock_guard<std::mutex> lg(engineMutex_);
            success = activeMemTable_->put(type, key, value, pointerBytes);
            if (!success) {
                switchMemTable();
                activeMemTable_->put(type, key, value, pointerBytes);
            }
        }
    }

    // 删除链路
    void remove(const std::vector<uint8_t>& key) {
        bool success = activeMemTable_->put(carina::common::LogRecordType::DELETE, key, {}, std::nullopt);
        if (!success) {
            std::lock_guard<std::mutex> lg(engineMutex_);
            success = activeMemTable_->put(carina::common::LogRecordType::DELETE, key, {}, std::nullopt);
            if (!success) {
                switchMemTable();
                activeMemTable_->put(carina::common::LogRecordType::DELETE, key, {}, std::nullopt);
            }
        }
    }

    std::optional<QueryResult> query(const std::vector<uint8_t>& key) {
        std::optional<carina::common::LogRecord> record = activeMemTable_->getRecord(key);

        if (!record.has_value()) {
            std::lock_guard<std::mutex> lg(immutablesMutex_);
            for (auto it = immutableMemTables_.rbegin(); it != immutableMemTables_.rend(); ++it) {
                record = (*it)->getRecord(key);
                if (record.has_value()) break;
            }
        }

        if (!record.has_value()) {
            std::lock_guard<std::mutex> lg(ssTablesMutex_);
            for (auto& reader : ssTables_) {
                if (compareBytes(key, reader->getMinKey()) < 0 || compareBytes(key, reader->getMaxKey()) > 0) continue;
                record = reader->searchBinaryFullRecord(key);
                if (record.has_value()) break;
            }
        }

        if (!record.has_value() || record->getType() == carina::common::LogRecordType::DELETE) {
            return std::nullopt;
        }

        QueryResult result;
        result.value = record->getValue();
        const auto& pointerBytes = record->getBlobPointer();
        if (pointerBytes.has_value() && pointerBytes->size() == 20) {
            blob::BlobPointer pointer = blob::BlobPointer::fromBytes(*pointerBytes);
            std::lock_guard<std::mutex> lg(blobReadersMutex_);
            auto it = blobReaders_.find(pointer.fileId);
            if (it != blobReaders_.end()) {
                result.vector = it->second->readVector(pointer.offset, pointer.vectorDim);
            } else {
                std::cerr << "[数据一致性警告] 找不到 fileId=" << pointer.fileId << " 的 vLog 文件！" << std::endl;
            }
        }
        return result;
    }

    // 向量近似最近邻查询
    std::vector<QueryResult> searchVector(const std::vector<float>& queryVector, int k) {
        // 超额检索：HNSW 图不支持删除，已删 key 靠 query() 撞墓碑后过滤，多要一倍再截回 k 条。
        auto hits = hnswIndex_.search(queryVector, k * 2);

        std::vector<QueryResult> results;
        results.reserve(k);
        for (auto& hit : hits) {
            std::optional<std::vector<uint8_t>> key;
            {
                std::lock_guard<std::mutex> lg(vectorIdToKeyMutex_);
                auto it = vectorIdToKey_.find(hit.nodeId);
                if (it != vectorIdToKey_.end()) key = it->second;
            }
            if (!key.has_value()) continue;

            auto result = query(*key);
            if (result.has_value()) {
                results.push_back(std::move(*result));
                if (static_cast<int>(results.size()) >= k) break;
            }
        }
        return results;
    }

    void close() {
        if (closed_.exchange(true)) return;
        std::cout << "开始执行 CarinaDB 安全停机..." << std::endl;

        stopCompaction_ = true;
        if (compactionThread_.joinable()) compactionThread_.join();

        if (activeMemTable_ && !activeMemTable_->isImmutable()) {
            activeMemTable_->freeze();
            auto tableToFlush = activeMemTable_;
            {
                std::lock_guard<std::mutex> lg(immutablesMutex_);
                immutableMemTables_.push_back(tableToFlush);
            }
            try {
                flushMemTableToDisk(tableToFlush);
            } catch (const std::exception& e) {
                std::cerr << "停机前刷盘失败: " << e.what() << std::endl;
            }
        }

        for (auto& reader : ssTables_) reader->close();
        std::cout << "CarinaDB 安全停机完毕。" << std::endl;
    }

private:
    using MemTablePtr = std::shared_ptr<memtable::MemTable>;

    std::shared_ptr<memtable::MemTable> createNewMemTable() {
        auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        std::string walFileName = "wal_" + std::to_string(now) + ".log";
        auto memTable = std::make_shared<memtable::MemTable>(MEMTABLE_CAPACITY, dbDirectory_, walFileName);
        memTable->setBlobWriter(blobWriter_);
        return memTable;
    }

    void switchMemTable() {
        if (!activeMemTable_->isImmutable()) activeMemTable_->freeze();

        auto tableToFlush = activeMemTable_;
        {
            std::lock_guard<std::mutex> lg(immutablesMutex_);
            immutableMemTables_.push_back(tableToFlush);
        }

        activeMemTable_ = createNewMemTable();

        std::thread([this, tableToFlush]() {
            try {
                flushMemTableToDisk(tableToFlush);
            } catch (const std::exception& e) {
                std::cerr << "后台刷盘致命失败: " << e.what() << std::endl;
            }
        }).detach();
    }

    void flushMemTableToDisk(const MemTablePtr& memTable) {
        int64_t newFileId = ++sstFileIdGenerator_;
        char nameBuf[64];
        std::snprintf(nameBuf, sizeof(nameBuf), "L0-%06lld.sst", static_cast<long long>(newFileId));
        std::string sstPath = dbDirectory_ + "/" + nameBuf;

        int estimatedInsertions = 100000;
        sstable::SSTableBuilder builder(sstPath, estimatedInsertions);
        memTable->forEach([&](const carina::common::LogRecord& record) {
            builder.add(record.getKey(), record.getValue(), record.getType(), record.getBlobPointer());
        });
        builder.finish();

        auto newReader = std::make_shared<sstable::SSTableReader>(sstPath);
        {
            std::lock_guard<std::mutex> lg(ssTablesMutex_);
            ssTables_.insert(ssTables_.begin(), newReader);
        }

        {
            std::lock_guard<std::mutex> lg(immutablesMutex_);
            immutableMemTables_.erase(
                std::remove(immutableMemTables_.begin(), immutableMemTables_.end(), memTable),
                immutableMemTables_.end());
        }
        std::string walFileName = memTable->getWalFileName();
        memTable->close();
        std::error_code ec;
        fs::remove(dbDirectory_ + "/" + walFileName, ec);

        std::cout << "Flush 完成：生成新段文件 " << nameBuf << std::endl;
    }

    void loadSSTables() {
        std::vector<fs::path> sstPaths;
        if (fs::exists(dbDirectory_)) {
            for (auto& entry : fs::directory_iterator(dbDirectory_)) {
                if (entry.is_regular_file() && entry.path().extension() == ".sst") {
                    sstPaths.push_back(entry.path());
                }
            }
        }
        // 文件号越大代表数据越新，降序排列
        std::sort(sstPaths.begin(), sstPaths.end(),
                  [](const fs::path& a, const fs::path& b) { return a.filename().string() > b.filename().string(); });
        for (auto& p : sstPaths) {
            ssTables_.push_back(std::make_shared<sstable::SSTableReader>(p.string()));
        }
    }

    void recoverFromOldWals() {
        std::cout << "🚀 开始扫描并回放历史 WAL 日志..." << std::endl;
        std::vector<fs::path> walPaths;
        if (fs::exists(dbDirectory_)) {
            for (auto& entry : fs::directory_iterator(dbDirectory_)) {
                std::string name = entry.path().filename().string();
                if (entry.is_regular_file() && name.find("wal_") != std::string::npos &&
                    name.size() > 4 && name.substr(name.size() - 4) == ".log") {
                    walPaths.push_back(entry.path());
                }
            }
        }
        if (walPaths.empty()) {
            std::cout << "✅ 没有发现遗留的 WAL 日志，无需恢复。" << std::endl;
            return;
        }
        std::sort(walPaths.begin(), walPaths.end());

        for (auto& walPath : walPaths) {
            std::string oldFileName = walPath.filename().string();
            if (oldFileName == activeMemTable_->getWalFileName()) continue;

            wal::Wal wal(dbDirectory_, oldFileName);
            wal.recover([this](const carina::common::LogRecord& record) {
                activeMemTable_->restoreFromWal(record);
                activeMemTable_->appendToWal(record);
            });
            wal.close();
            std::error_code ec;
            fs::remove(walPath, ec);
        }
    }

    void backgroundCompaction() {
        try {
            std::vector<fs::path> l0Files;
            for (auto& entry : fs::directory_iterator(dbDirectory_)) {
                std::string name = entry.path().filename().string();
                if (name.rfind("L0-", 0) == 0 && name.size() > 4 && name.substr(name.size() - 4) == ".sst") {
                    l0Files.push_back(entry.path());
                }
            }
            if (static_cast<int>(l0Files.size()) < L0_COMPACTION_TRIGGER) return;

            std::cout << "\n[后台雷达] L0 层积压 " << l0Files.size() << " 个文件，开始大清剿..." << std::endl;

            std::vector<std::shared_ptr<sstable::SSTableScanner>> scanners;
            for (auto& f : l0Files) scanners.push_back(std::make_shared<sstable::SSTableScanner>(f.string()));

            int64_t newL1FileId = ++sstFileIdGenerator_;
            std::string newSST = compactor_->executeCompaction(scanners, 1, newL1FileId, true);

            for (auto& s : scanners) s->close();
            for (auto& f : l0Files) { std::error_code ec; fs::remove(f, ec); }

            {
                std::lock_guard<std::mutex> lg(ssTablesMutex_);
                ssTables_.insert(ssTables_.begin(), std::make_shared<sstable::SSTableReader>(newSST));
            }
            std::cout << "[大清剿结束] 新纪元超级块诞生: " << newSST << std::endl;
        } catch (const std::exception& e) {
            std::cerr << "后台合并发生异常: " << e.what() << std::endl;
        }
    }

    int64_t loadMaxSSTableId() {
        if (ssTables_.empty()) return 0;
        const std::string& fileName = ssTables_.front()->getFileId();
        std::string digits;
        for (char c : fileName) if (isdigit(static_cast<unsigned char>(c))) digits += c;
        if (digits.empty()) return 0;
        try { return std::stoll(digits); } catch (...) { return 0; }
    }

    int64_t loadMaxBlobFileId() {
        int64_t maxId = 0;
        if (!fs::exists(dbDirectory_)) return maxId;
        for (auto& entry : fs::directory_iterator(dbDirectory_)) {
            std::string name = entry.path().filename().string();
            if (name.rfind("vlog-", 0) == 0 && name.size() > 5) {
                auto dot = name.find_last_of('.');
                std::string idStr = name.substr(5, dot - 5);
                try { maxId = std::max(maxId, static_cast<int64_t>(std::stoll(idStr))); } catch (...) {}
            }
        }
        return maxId;
    }

    void loadAllBlobReaders() {
        if (!fs::exists(dbDirectory_)) return;
        for (auto& entry : fs::directory_iterator(dbDirectory_)) {
            std::string name = entry.path().filename().string();
            if (name.rfind("vlog-", 0) == 0 && name.size() > 5) {
                auto dot = name.find_last_of('.');
                std::string idStr = name.substr(5, dot - 5);
                try {
                    int64_t id = std::stoll(idStr);
                    blobReaders_[id] = std::make_unique<blob::BlobReader>(entry.path().string(), id);
                } catch (const std::exception& e) {
                    std::cerr << "加载 vLog 文件失败: " << name << std::endl;
                }
            }
        }
    }

    static std::string formatBlobName(int64_t id) {
        char buf[64];
        std::snprintf(buf, sizeof(buf), "vlog-%04lld.data", static_cast<long long>(id));
        return buf;
    }

    static int compareBytes(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
        size_t lim = std::min(a.size(), b.size());
        for (size_t i = 0; i < lim; i++) {
            if (a[i] != b[i]) return int(a[i]) - int(b[i]);
        }
        return static_cast<int>(a.size()) - static_cast<int>(b.size());
    }

    std::string dbDirectory_;

    int64_t sstFileIdGenerator_ = 0;
    int64_t blobFileIdGenerator_ = 0;

    MemTablePtr activeMemTable_;
    std::mutex engineMutex_;
    std::deque<MemTablePtr> immutableMemTables_;
    std::mutex immutablesMutex_;

    std::vector<std::shared_ptr<sstable::SSTableReader>> ssTables_;
    std::mutex ssTablesMutex_;

    std::shared_ptr<blob::BlobWriter> blobWriter_;
    std::unordered_map<int64_t, std::unique_ptr<blob::BlobReader>> blobReaders_;
    std::mutex blobReadersMutex_;

    index::hnsw::HnswIndex hnswIndex_;
    std::atomic<int> vectorIdGenerator_{0};
    std::unordered_map<int, std::vector<uint8_t>> vectorIdToKey_;
    std::mutex vectorIdToKeyMutex_;

    std::unique_ptr<compaction::Compactor> compactor_;
    std::thread compactionThread_;
    std::atomic<bool> stopCompaction_{false};
    std::atomic<bool> closed_{false};
};

} // namespace carina::engine
