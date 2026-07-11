#pragma once
#include <cstdint>
#include <cstddef>
#include <array>

// 与 java.util.zip.CRC32 完全一致的标准 CRC-32 (多项式 0xEDB88320)，
// 保证与原 Java 版 LogRecordCoder 编码出的校验值算法一致。
namespace carina::common {

namespace detail {
inline std::array<uint32_t, 256> makeCrc32Table() {
    std::array<uint32_t, 256> table{};
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int k = 0; k < 8; k++) {
            c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        }
        table[i] = c;
    }
    return table;
}
inline const std::array<uint32_t, 256>& crc32Table() {
    static const std::array<uint32_t, 256> table = makeCrc32Table();
    return table;
}
} // namespace detail

inline uint32_t crc32(const uint8_t* data, size_t len) {
    const auto& table = detail::crc32Table();
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; i++) {
        crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFFu;
}

} // namespace carina::common
