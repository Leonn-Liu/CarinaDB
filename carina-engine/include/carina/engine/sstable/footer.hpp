#pragma once
#include "carina/common/byte_io.hpp"
#include <cstdint>
#include <vector>
#include <unistd.h>

// 对应 com.jiashi.db.engine.sstable.Footer：SSTable 定长封底 (40 字节)
namespace carina::engine::sstable {

class Footer {
public:
    static constexpr int64_t MAGIC_NUMBER = 0x436172696E614442LL; // "CarinaDB"
    static constexpr int ENCODED_LENGTH = 40;

    static void writeFooter(int fd, int64_t filterOffset, int64_t indexOffset,
                             int64_t minKeyOffset, int64_t maxKeyOffset) {
        std::vector<uint8_t> buf;
        buf.reserve(ENCODED_LENGTH);
        carina::common::putInt64BE(buf, filterOffset);
        carina::common::putInt64BE(buf, indexOffset);
        carina::common::putInt64BE(buf, minKeyOffset);
        carina::common::putInt64BE(buf, maxKeyOffset);
        carina::common::putInt64BE(buf, MAGIC_NUMBER);
        size_t written = 0;
        while (written < buf.size()) {
            ssize_t n = ::write(fd, buf.data() + written, buf.size() - written);
            if (n <= 0) throw std::runtime_error("Footer: write failed");
            written += static_cast<size_t>(n);
        }
    }
};

} // namespace carina::engine::sstable
