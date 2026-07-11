#pragma once
#include "carina/engine/blob/blob_pointer.hpp"
#include <string>
#include <cstring>
#include <atomic>
#include <optional>
#include <stdexcept>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

// 对应 com.jiashi.db.engine.blob.BlobWriter：vLog 文件追加写入器
namespace carina::engine::blob {

class BlobWriter {
public:
    BlobWriter(const std::string& path, int64_t fileId) : fileId_(fileId), path_(path) {
        fd_ = ::open(path.c_str(), O_CREAT | O_RDWR, 0644);
        if (fd_ < 0) throw std::runtime_error("BlobWriter: failed to open " + path);
        struct stat st{};
        ::fstat(fd_, &st);
        currentOffset_ = st.st_size;
    }

    ~BlobWriter() { if (fd_ >= 0) ::close(fd_); }

    std::optional<BlobPointer> append(const std::vector<float>& vector) {
        if (vector.empty()) return std::nullopt;
        size_t byteSize = vector.size() * sizeof(float);
        std::vector<uint8_t> buf(byteSize);
        // 与 Java ByteBuffer.putFloat 一致地按大端序落盘 IEEE754 位模式
        for (size_t i = 0; i < vector.size(); i++) {
            uint32_t bits;
            std::memcpy(&bits, &vector[i], 4);
            buf[i * 4 + 0] = static_cast<uint8_t>((bits >> 24) & 0xFF);
            buf[i * 4 + 1] = static_cast<uint8_t>((bits >> 16) & 0xFF);
            buf[i * 4 + 2] = static_cast<uint8_t>((bits >> 8) & 0xFF);
            buf[i * 4 + 3] = static_cast<uint8_t>(bits & 0xFF);
        }
        int64_t myOffset = currentOffset_.fetch_add(static_cast<int64_t>(byteSize));
        size_t written = 0;
        while (written < buf.size()) {
            ssize_t n = ::pwrite(fd_, buf.data() + written, buf.size() - written, myOffset + written);
            if (n <= 0) throw std::runtime_error("BlobWriter: write failed");
            written += static_cast<size_t>(n);
        }
        return BlobPointer{fileId_, myOffset, static_cast<int32_t>(vector.size())};
    }

    void sync() { ::fsync(fd_); }

    int64_t fileId() const { return fileId_; }

private:
    int64_t fileId_;
    std::string path_;
    int fd_ = -1;
    std::atomic<int64_t> currentOffset_{0};
};

} // namespace carina::engine::blob
