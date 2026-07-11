#pragma once
#include "carina/common/log_record.hpp"
#include "carina/engine/sstable/sstable_scanner.hpp"
#include <memory>

// 对应 com.jiashi.db.engine.compaction.MergeEntry：参与多路归并的实体
namespace carina::engine::compaction {

struct MergeEntry {
    carina::common::LogRecord record;
    int64_t fileId; // 数字越大代表数据越新
    std::shared_ptr<carina::engine::sstable::SSTableScanner> scanner;
};

} // namespace carina::engine::compaction
