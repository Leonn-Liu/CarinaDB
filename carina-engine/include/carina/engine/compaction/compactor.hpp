#pragma once
#include "carina/engine/compaction/merge_entry.hpp"
#include "carina/engine/compaction/compaction_comparator.hpp"
#include "carina/engine/sstable/sstable_builder.hpp"
#include "carina/engine/sstable/sstable_scanner.hpp"
#include "carina/common/log_record.hpp"
#include <queue>
#include <vector>
#include <memory>
#include <string>

// 对应 com.jiashi.db.engine.compaction.Compactor：优先队列 N 路归并
namespace carina::engine::compaction {

class Compactor {
public:
    explicit Compactor(std::string dbPath) : dbPath_(std::move(dbPath)) {}

    std::string executeCompaction(std::vector<std::shared_ptr<carina::engine::sstable::SSTableScanner>>& scanners,
                                   int targetLevel, int64_t newFileId, bool isBottomLevel) {
        std::priority_queue<MergeEntry, std::vector<MergeEntry>, CompactionGreater> heap;
        for (auto& scanner : scanners) {
            if (scanner->hasNext()) {
                heap.push(MergeEntry{*scanner->next(), scanner->getFileId(), scanner});
            }
        }

        char nameBuf[64];
        std::snprintf(nameBuf, sizeof(nameBuf), "L%d-%lld.sst", targetLevel, static_cast<long long>(newFileId));
        std::string newFilePath = dbPath_ + "/" + nameBuf;
        carina::engine::sstable::SSTableBuilder builder(newFilePath, 10000);

        std::optional<std::vector<uint8_t>> lastProcessedKey;
        while (!heap.empty()) {
            MergeEntry current = heap.top();
            heap.pop();
            const auto& currentKey = current.record.getKey();
            if (lastProcessedKey.has_value() && *lastProcessedKey == currentKey) {
                advanceScanner(heap, current);
                continue;
            }
            lastProcessedKey = currentKey;
            if (current.record.getType() == carina::common::LogRecordType::DELETE && isBottomLevel) {
                advanceScanner(heap, current);
                continue;
            }
            builder.add(current.record.getKey(), current.record.getValue(), current.record.getType(),
                        current.record.getBlobPointer());
            advanceScanner(heap, current);
        }
        builder.finish();
        return newFilePath;
    }

private:
    void advanceScanner(std::priority_queue<MergeEntry, std::vector<MergeEntry>, CompactionGreater>& heap,
                          MergeEntry& currentEntry) {
        if (currentEntry.scanner->hasNext()) {
            heap.push(MergeEntry{*currentEntry.scanner->next(), currentEntry.fileId, currentEntry.scanner});
        }
    }

    std::string dbPath_;
};

} // namespace carina::engine::compaction
