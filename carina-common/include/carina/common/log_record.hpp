#pragma once
#include <cstdint>
#include <vector>
#include <optional>

// 对应 com.jiashi.db.common.model.LogRecordType / LogRecord
namespace carina::common {

struct LogRecordType {
    static constexpr uint8_t PUT_KV = 0;
    static constexpr uint8_t PUT_VECTOR = 1;
    static constexpr uint8_t DELETE = 2;
};

// 内存中的数据载体，不涉及底层字节逻辑
// TODO(MVP-Debt): 缺乏 MVCC 全局序列号 (Sequence Number)。
class LogRecord {
public:
    LogRecord() = default;
    LogRecord(uint8_t type, std::vector<uint8_t> key, std::vector<uint8_t> value,
               std::optional<std::vector<uint8_t>> blobPointer)
        : type_(type), key_(std::move(key)), value_(std::move(value)),
          blobPointer_(std::move(blobPointer)) {}

    uint8_t getType() const { return type_; }
    const std::vector<uint8_t>& getKey() const { return key_; }
    const std::vector<uint8_t>& getValue() const { return value_; }
    const std::optional<std::vector<uint8_t>>& getBlobPointer() const { return blobPointer_; }

private:
    uint8_t type_ = 0;
    std::vector<uint8_t> key_;
    std::vector<uint8_t> value_;
    std::optional<std::vector<uint8_t>> blobPointer_;
};

} // namespace carina::common
