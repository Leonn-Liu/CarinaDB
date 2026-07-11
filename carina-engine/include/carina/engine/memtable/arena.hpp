#pragma once
#include <cstdint>
#include <cstring>
#include <atomic>
#include <memory>
#include <stdexcept>
#include <vector>

// 对应 com.jiashi.db.engine.memtable.Arena：堆外内存分配器。
// Java 版必须用 Unsafe 反射拿 DirectByteBuffer 的物理地址来伪造指针；
// 在 C++ 里我们本来就有真指针，直接 malloc 一大块内存即可，语义完全一致：
// 无锁并发分配（fetch_add 对应 Java 的 AtomicInteger.getAndAdd / LOCK XADD），
// offset 0 保留为 NULL 哨兵，4 字节对齐。
namespace carina::engine::memtable {

class Arena {
public:
    static constexpr int DEFAULT_BLOCK_SIZE = 64 * 1024 * 1024;
    static constexpr int ALIGN_BYTES = 4;

    explicit Arena(int size = DEFAULT_BLOCK_SIZE)
        : capacity_(size), buffer_(new uint8_t[size]), allocateOffset_(ALIGN_BYTES) {
        std::memset(buffer_.get(), 0, size);
    }

    // 无锁并发分配内存块，返回物理偏移量
    int allocate(int size) {
        int alignedSize = align(size);
        int offset = allocateOffset_.fetch_add(alignedSize);
        if (offset + alignedSize > capacity_) {
            throw std::runtime_error("Arena MemTable is full. Current capacity: " + std::to_string(capacity_));
        }
        return offset;
    }

    // 注意：这里用机器原生字节序存储（不是网络字节序），因为这是进程内部的跳表指针/
    // 长度字段，从不落盘也不跨网络传输；且必须和下面 atomicIntAt() 的 CAS 视图共用
    // 同一套字节表示，否则 compare_exchange 会读到和 getInt/putInt 不一致的值。
    void putInt(int offset, int32_t value) {
        std::memcpy(buffer_.get() + offset, &value, 4);
    }
    int32_t getInt(int offset) const {
        int32_t value;
        std::memcpy(&value, buffer_.get() + offset, 4);
        return value;
    }
    void putByte(int offset, uint8_t value) { buffer_[offset] = value; }
    uint8_t getByte(int offset) const { return buffer_[offset]; }

    void putBytes(int offset, const std::vector<uint8_t>& data) {
        if (!data.empty()) std::memcpy(buffer_.get() + offset, data.data(), data.size());
    }
    void getBytes(int offset, uint8_t* dest, size_t len) const {
        if (len > 0) std::memcpy(dest, buffer_.get() + offset, len);
    }

    int memoryUsage() const { return allocateOffset_.load(); }

    // 供跳表 CAS 使用的原子指针视图：把 offset 处的 4 字节当作 atomic<int32_t> 操作
    std::atomic<int32_t>* atomicIntAt(int offset) {
        return reinterpret_cast<std::atomic<int32_t>*>(buffer_.get() + offset);
    }

private:
    static int align(int size) { return (size + (ALIGN_BYTES - 1)) & ~(ALIGN_BYTES - 1); }

    int capacity_;
    std::unique_ptr<uint8_t[]> buffer_;
    std::atomic<int> allocateOffset_;
};

} // namespace carina::engine::memtable
