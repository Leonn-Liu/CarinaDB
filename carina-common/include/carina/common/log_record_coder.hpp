#pragma once
#include "carina/common/log_record.hpp"
#include "carina/common/crc32.hpp"
#include "carina/common/byte_io.hpp"
#include <vector>
#include <stdexcept>

// 对应 com.jiashi.db.common.coder.LogRecordCoder：负责 LogRecord 与字节流的互相转换。
// 线路格式与 Java 版逐字节保持一致：
//   Header(8B) = CRC32(4) + PayloadSize(4)
//   Payload    = Type(1) + KeyLen(4) + ValLen(4) + PtrLen(4) + Key + Value + Ptr
namespace carina::common {

class LogRecordCoder {
public:
    static constexpr int HEADER_SIZE = 8;

    static std::vector<uint8_t> encode(const LogRecord& record) {
        const auto& key = record.getKey();
        const auto& value = record.getValue();
        const auto& pointer = record.getBlobPointer();
        size_t keyLen = key.size();
        size_t valLen = value.size();
        size_t ptrLen = pointer.has_value() ? pointer->size() : 0;

        std::vector<uint8_t> payload;
        payload.reserve(1 + 4 + 4 + 4 + keyLen + valLen + ptrLen);
        payload.push_back(record.getType());
        putInt32BE(payload, static_cast<int32_t>(keyLen));
        putInt32BE(payload, static_cast<int32_t>(valLen));
        putInt32BE(payload, static_cast<int32_t>(ptrLen));
        if (keyLen > 0) putBytes(payload, key);
        if (valLen > 0) putBytes(payload, value);
        if (ptrLen > 0) putBytes(payload, *pointer);

        uint32_t crc = crc32(payload.data(), payload.size());

        std::vector<uint8_t> out;
        out.reserve(HEADER_SIZE + payload.size());
        putInt32BE(out, static_cast<int32_t>(crc));
        putInt32BE(out, static_cast<int32_t>(payload.size()));
        putBytes(out, payload);
        return out;
    }

    // 解码：payload 不含 Header，与 Java decodePayload 语义一致
    static LogRecord decodePayload(const uint8_t* payload, size_t len) {
        ByteReader in(payload, len);
        uint8_t type = in.getByte();
        int32_t keyLen = in.getInt32BE();
        int32_t valLen = in.getInt32BE();
        int32_t ptrLen = in.getInt32BE();

        std::vector<uint8_t> key = keyLen > 0 ? in.getBytes(keyLen) : std::vector<uint8_t>{};
        std::vector<uint8_t> value = valLen > 0 ? in.getBytes(valLen) : std::vector<uint8_t>{};
        std::optional<std::vector<uint8_t>> pointer;
        if (ptrLen > 0) pointer = in.getBytes(ptrLen);

        return LogRecord(type, std::move(key), std::move(value), std::move(pointer));
    }
};

} // namespace carina::common
