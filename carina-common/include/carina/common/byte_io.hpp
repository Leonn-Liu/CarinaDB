#pragma once
#include <cstdint>
#include <cstring>
#include <vector>
#include <stdexcept>

// 大端序（网络字节序）读写辅助，对应 Java ByteBuffer 的默认 BIG_ENDIAN 行为。
namespace carina::common {

inline void putInt32BE(std::vector<uint8_t>& buf, int32_t v) {
    buf.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>(v & 0xFF));
}

inline void putInt64BE(std::vector<uint8_t>& buf, int64_t v) {
    for (int shift = 56; shift >= 0; shift -= 8) {
        buf.push_back(static_cast<uint8_t>((v >> shift) & 0xFF));
    }
}

inline void putBytes(std::vector<uint8_t>& buf, const std::vector<uint8_t>& data) {
    buf.insert(buf.end(), data.begin(), data.end());
}

class ByteReader {
public:
    ByteReader(const uint8_t* data, size_t len) : data_(data), len_(len) {}

    int32_t getInt32BE() {
        require(4);
        int32_t v = (int32_t(data_[pos_]) << 24) | (int32_t(data_[pos_ + 1]) << 16) |
                    (int32_t(data_[pos_ + 2]) << 8) | int32_t(data_[pos_ + 3]);
        pos_ += 4;
        return v;
    }

    int64_t getInt64BE() {
        require(8);
        int64_t v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | int64_t(data_[pos_ + i]);
        }
        pos_ += 8;
        return v;
    }

    uint8_t getByte() {
        require(1);
        return data_[pos_++];
    }

    std::vector<uint8_t> getBytes(size_t n) {
        require(n);
        std::vector<uint8_t> out(data_ + pos_, data_ + pos_ + n);
        pos_ += n;
        return out;
    }

    size_t remaining() const { return len_ - pos_; }
    size_t position() const { return pos_; }

private:
    void require(size_t n) const {
        if (pos_ + n > len_) throw std::runtime_error("ByteReader: buffer underflow");
    }
    const uint8_t* data_;
    size_t len_;
    size_t pos_ = 0;
};

} // namespace carina::common
