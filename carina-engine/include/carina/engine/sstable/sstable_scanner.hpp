#pragma once
#include "carina/common/log_record.hpp"
#include "carina/common/byte_io.hpp"
#include <string>
#include <vector>
#include <optional>
#include <stdexcept>
#include <fcntl.h>
#include <unistd.h>

// 对应 com.jiashi.db.engine.sstable.SSTableScanner：流式顺序读取 SSTable
namespace carina::engine::sstable {

class SSTableScanner {
public:
    explicit SSTableScanner(const std::string& path) : path_(path) {
        auto slash = path.find_last_of('/');
        std::string filename = (slash == std::string::npos) ? path : path.substr(slash + 1);
        auto dot = filename.find_last_of('.');
        std::string nameWithoutExt = (dot == std::string::npos) ? filename : filename.substr(0, dot);
        auto dash = nameWithoutExt.find('-');
        std::string levelPart = nameWithoutExt.substr(0, dash);   // e.g. "L0"
        std::string idPart = nameWithoutExt.substr(dash + 1);     // e.g. "000006"
        level_ = std::stoi(levelPart.substr(1));
        fileId_ = std::stoll(idPart);

        int rfd = ::open(path.c_str(), O_RDONLY);
        if (rfd < 0) throw std::runtime_error("SSTableScanner: failed to open " + path);
        int64_t fileSize = ::lseek(rfd, 0, SEEK_END);
        std::vector<uint8_t> buf(8);
        ::pread(rfd, buf.data(), 8, fileSize - 40); // Footer 前 8 字节即 filterOffset == 数据区结束点
        carina::common::ByteReader in(buf.data(), buf.size());
        dataEndOffset_ = in.getInt64BE();
        ::close(rfd);

        fd_ = ::open(path.c_str(), O_RDONLY);
        if (fd_ < 0) throw std::runtime_error("SSTableScanner: failed to reopen " + path);
    }

    ~SSTableScanner() { close(); }

    bool hasNext() const { return currentReadOffset_ < dataEndOffset_; }

    std::optional<carina::common::LogRecord> next() {
        if (!hasNext()) return std::nullopt;

        std::vector<uint8_t> header(13);
        readFully(header.data(), 13);
        carina::common::ByteReader hin(header.data(), 13);
        uint8_t type = hin.getByte();
        int32_t keySize = hin.getInt32BE();
        int32_t valSize = hin.getInt32BE();
        int32_t ptrSize = hin.getInt32BE();
        currentReadOffset_ += 13;

        std::vector<uint8_t> key(keySize);
        readFully(key.data(), keySize);
        currentReadOffset_ += keySize;

        std::vector<uint8_t> value(valSize);
        readFully(value.data(), valSize);
        currentReadOffset_ += valSize;

        std::optional<std::vector<uint8_t>> pointer;
        if (ptrSize > 0) {
            std::vector<uint8_t> ptr(ptrSize);
            readFully(ptr.data(), ptrSize);
            pointer = std::move(ptr);
        }
        currentReadOffset_ += ptrSize;

        return carina::common::LogRecord(type, key, value, pointer);
    }

    int64_t getFileId() const { return fileId_; }
    int getLevel() const { return level_; }

    void close() { if (fd_ >= 0) { ::close(fd_); fd_ = -1; } }

private:
    void readFully(uint8_t* dest, size_t len) {
        size_t got = 0;
        while (got < len) {
            ssize_t n = ::read(fd_, dest + got, len - got);
            if (n <= 0) throw std::runtime_error("SSTableScanner: unexpected EOF");
            got += static_cast<size_t>(n);
        }
    }

    std::string path_;
    int level_;
    int64_t fileId_;
    int64_t dataEndOffset_;
    int64_t currentReadOffset_ = 0;
    int fd_ = -1;
};

} // namespace carina::engine::sstable
