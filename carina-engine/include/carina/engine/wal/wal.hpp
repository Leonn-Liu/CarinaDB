#pragma once
#include "carina/common/log_record.hpp"
#include "carina/common/log_record_coder.hpp"
#include <string>
#include <vector>
#include <deque>
#include <mutex>
#include <condition_variable>
#include <future>
#include <functional>
#include <iostream>
#include <stdexcept>
#include <fcntl.h>
#include <unistd.h>

// 对应 com.jiashi.db.engine.wal.WAL：Leader-Follower Group Commit。
// Java 版用 ConcurrentLinkedQueue + ReentrantLock.tryLock() 做 Leader 选举，
// CompletableFuture 唤醒 Follower。C++ 版用 mutex 保护的 deque 队列 + 每请求一个
// std::promise/future 做等价实现：算法（攒批、拼一次连续 buffer、一次 write+fsync、
// 集体唤醒）完全一致，只是把无锁队列换成了加锁队列这一底层并发原语（此处不追求
// Java 版的无锁实现，只保证 group commit 的行为语义一致）。
namespace carina::engine::wal {

class Wal {
public:
    static constexpr size_t MAX_BATCH_SIZE = 1000;

    Wal(const std::string& directory, const std::string& fileName) : path_(directory + "/" + fileName) {
        fd_ = ::open(path_.c_str(), O_CREAT | O_RDWR | O_APPEND, 0644);
        if (fd_ < 0) throw std::runtime_error("WAL: failed to open " + path_);
    }

    ~Wal() { close(); }

    void append(std::vector<uint8_t> data) {
        auto request = std::make_shared<Request>();
        request->data = std::move(data);
        auto fut = request->promise.get_future().share();

        {
            std::lock_guard<std::mutex> lg(queueMutex_);
            queue_.push_back(request);
        }

        while (fut.wait_for(std::chrono::seconds(0)) != std::future_status::ready) {
            std::unique_lock<std::mutex> leaderLock(leaderMutex_, std::try_to_lock);
            if (leaderLock.owns_lock()) {
                if (fut.wait_for(std::chrono::seconds(0)) == std::future_status::ready) break;
                while (true) {
                    bool drained = drainAndFlushOnce();
                    if (!drained) break;
                }
            } else {
                fut.wait_for(std::chrono::milliseconds(10));
            }
        }
        fut.get();
    }

    void setBlobSyncCallback(std::function<void()> cb) { blobSync_ = std::move(cb); }

    void close() {
        if (fd_ >= 0) {
            ::fsync(fd_);
            ::close(fd_);
            fd_ = -1;
        }
    }

    // 系统重启时的 WAL 灾难恢复逻辑：定长 Header(CRC32+PayloadSize) + Payload，逐条校验重放。
    void recover(const std::function<void(const carina::common::LogRecord&)>& consumer) {
        int rfd = ::open(path_.c_str(), O_RDONLY);
        if (rfd < 0) throw std::runtime_error("WAL: recover open failed " + path_);
        off_t fileSize = ::lseek(rfd, 0, SEEK_END);
        ::lseek(rfd, 0, SEEK_SET);

        off_t currentPos = 0;
        int recovered = 0;
        std::vector<uint8_t> headerBuf(carina::common::LogRecordCoder::HEADER_SIZE);

        while (currentPos < fileSize) {
            ssize_t readBytes = ::read(rfd, headerBuf.data(), headerBuf.size());
            if (readBytes <= 0) break;
            if (static_cast<size_t>(readBytes) < headerBuf.size()) {
                std::cerr << "WARN: Incomplete WAL header at " << currentPos << std::endl;
                break;
            }
            carina::common::ByteReader hin(headerBuf.data(), headerBuf.size());
            int32_t expectedCrc = hin.getInt32BE();
            int32_t payloadSize = hin.getInt32BE();

            if (payloadSize <= 0 || payloadSize > (1024 * 1024 * 100)) {
                std::cerr << "ERROR: Corrupted payload size (" << payloadSize << ") at " << currentPos << std::endl;
                break;
            }
            if (currentPos + static_cast<off_t>(headerBuf.size()) + payloadSize > fileSize) {
                std::cerr << "WARN: Torn write detected, stopping recovery." << std::endl;
                break;
            }

            std::vector<uint8_t> payload(payloadSize);
            ssize_t got = ::read(rfd, payload.data(), payload.size());
            if (got < payloadSize) {
                std::cerr << "WARN: Failed to read full payload." << std::endl;
                break;
            }

            uint32_t actualCrc = carina::common::crc32(payload.data(), payload.size());
            if (static_cast<uint32_t>(expectedCrc) != actualCrc) {
                std::cerr << "ERROR: CRC mismatch at " << currentPos << std::endl;
                break;
            }

            carina::common::LogRecord record = carina::common::LogRecordCoder::decodePayload(payload.data(), payload.size());
            consumer(record);
            recovered++;
            currentPos += static_cast<off_t>(headerBuf.size()) + payloadSize;
        }
        std::cout << "WAL Recovery Finished. Successfully recovered " << recovered << " records." << std::endl;

        if (currentPos < fileSize) {
            ::ftruncate(rfd, currentPos);
            ::fsync(rfd);
        }
        ::close(rfd);
    }

private:
    struct Request {
        std::vector<uint8_t> data;
        std::promise<void> promise;
    };

    bool drainAndFlushOnce() {
        std::vector<std::shared_ptr<Request>> batch;
        {
            std::lock_guard<std::mutex> lg(queueMutex_);
            while (!queue_.empty() && batch.size() < MAX_BATCH_SIZE) {
                batch.push_back(queue_.front());
                queue_.pop_front();
            }
        }
        if (batch.empty()) return false;

        std::vector<uint8_t> merged;
        size_t total = 0;
        for (auto& r : batch) total += r->data.size();
        merged.reserve(total);
        for (auto& r : batch) merged.insert(merged.end(), r->data.begin(), r->data.end());

        size_t written = 0;
        bool failed = false;
        while (written < merged.size()) {
            ssize_t n = ::write(fd_, merged.data() + written, merged.size() - written);
            if (n <= 0) { failed = true; break; }
            written += static_cast<size_t>(n);
        }
        if (!failed) {
            if (blobSync_) blobSync_();
            ::fsync(fd_);
        }
        for (auto& r : batch) {
            if (failed) {
                r->promise.set_exception(std::make_exception_ptr(std::runtime_error("WAL Flush Failed")));
            } else {
                r->promise.set_value();
            }
        }
        return true;
    }

    std::string path_;
    int fd_ = -1;
    std::deque<std::shared_ptr<Request>> queue_;
    std::mutex queueMutex_;
    std::mutex leaderMutex_;
    std::function<void()> blobSync_;
};

} // namespace carina::engine::wal
