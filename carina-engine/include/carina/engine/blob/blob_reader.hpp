#pragma once
#include <string>
#include <vector>
#include <cstring>
#include <stdexcept>
#include <fcntl.h>
#include <unistd.h>

// 对应 com.jiashi.db.engine.blob.BlobReader
namespace carina::engine::blob {

class BlobReader {
public:
    BlobReader(const std::string& path, int64_t fileId) : fileId_(fileId) {
        fd_ = ::open(path.c_str(), O_RDONLY);
        if (fd_ < 0) throw std::runtime_error("BlobReader: failed to open " + path);
    }
    ~BlobReader() { if (fd_ >= 0) ::close(fd_); }

    std::vector<float> readVector(int64_t offset, int vectorDim) const {
        if (vectorDim <= 0) return {};
        size_t byteSize = static_cast<size_t>(vectorDim) * 4;
        std::vector<uint8_t> buf(byteSize);
        ssize_t bytesRead = ::pread(fd_, buf.data(), byteSize, offset);
        if (bytesRead != static_cast<ssize_t>(byteSize)) {
            throw std::runtime_error("BlobReader: 物理寻址失败");
        }
        std::vector<float> vector(vectorDim);
        for (int i = 0; i < vectorDim; i++) {
            uint32_t bits = (uint32_t(buf[i * 4]) << 24) | (uint32_t(buf[i * 4 + 1]) << 16) |
                            (uint32_t(buf[i * 4 + 2]) << 8) | uint32_t(buf[i * 4 + 3]);
            std::memcpy(&vector[i], &bits, 4);
        }
        return vector;
    }

    int64_t fileId() const { return fileId_; }

private:
    int64_t fileId_;
    int fd_ = -1;
};

} // namespace carina::engine::blob
