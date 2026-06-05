package com.jiashi.db.engine.memtable;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 堆外内存分配器：彻底隔离 GC，实现无锁并发分配
 */
public class Arena {
    // 默认MemTable内存阈值，例如64MB。
    // 达到此阈值后，该Arena对应的MemTable将被冻结为Immutable(只读状态)。
    private static final int DEFAULT_BLOCK_SIZE = 64 * 1024 * 1024;

    // 物理对齐量：4字节。防止CPU跨缓存行（Cache Line）读取导致的性能惩罚
    private static final int ALIGN_BYTES = 4;

    // 真正的物理存储，完全游离于 JVM 堆外
    private final ByteBuffer buffer;

    // 核心并发游标：记录当前内存分配的“尾部绝对坐标”
    private final AtomicInteger allocateOffset;

    private final long memoryStartAddress;

    public Arena(int size) {
        // 向操作系统直接申请内存
        this.buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        // 从 4（ALIGN_BYTES）起步而非 0/1：offset 0 保留为 NULL 哨兵，同时保证首个 baseOffset 即 4 对齐"
        this.allocateOffset = new AtomicInteger(ALIGN_BYTES);

        try {
            // 事实：绕过访问控制，直接抓取 java.nio.Buffer 里的私有变量 address
            Field addressField = Buffer.class.getDeclaredField("address");
            addressField.setAccessible(true);
            // 提取出操作系统的绝对物理地址 (例如：0x7FFF12340000)
            this.memoryStartAddress = (long) addressField.get(this.buffer);
        } catch (Exception e) {
            // 如果提取失败，整个无锁跳表将无法建立，必须直接宕机
            throw new Error("Failed to get raw memory address from DirectByteBuffer. JVM may not support this operation.", e);
        }
    }

    public Arena() {
        this(DEFAULT_BLOCK_SIZE);
    }

    /**
     * 无锁并发分配内存块
     * @param size 需要的字节大小
     * @return 分配成功的物理偏移量（Offset，相当于这块内存的首地址）
     */
    public int allocate(int size) {
        // 1. 内存对齐 (位运算黑魔法)
        // 假设要 13 字节，对齐后必须是 16 字节，保证强迫症般的物理整齐
        int alignedSize = align(size);

        // 2. 核心降维：将锁竞争降级为一条 CPU 指令 (LOCK XADD)
        // 无论多少个线程并发涌入，硬件总线会保证它们拿到互不重叠的 offset 坐标
        int offset = allocateOffset.getAndAdd(alignedSize);

        // 3. 物理边界防御
        if (offset + alignedSize > buffer.capacity()) {
            // 当内存被切分殆尽，说明当前的 Active MemTable 已满。
            // 此时必须抛出异常或返回特定标识，通知上层状态机：
            // "立刻冻结当前 MemTable 开始异步 Flush，并 new 一个新的 Arena 接管流量！"
            throw new OutOfMemoryError("Arena MemTable is full. Current capacity: " + buffer.capacity());
        }

        return offset;
    }

    /**
     * 字节对齐算法：(size + (ALIGN - 1)) & ~(ALIGN - 1)
     * 翻译成数学公式就是求：大于等于 size 且是 4 的倍数的最小整数。
     */
    private int align(int size) {
        return (size + (ALIGN_BYTES - 1)) & ~(ALIGN_BYTES - 1);
    }

    // ==========================================
    // 底层物理读写 API (必须使用绝对位置偏移量，避开非线程安全的 position)
    // ==========================================
    /**
     * 【新增提供给 Unsafe 使用的核心方法】
     * 暴露操作系统的绝对物理基址
     */
    public long getMemoryStartAddress() {
        return memoryStartAddress;
    }


    public void putInt(int offset, int value) {
        buffer.putInt(offset, value);
    }

    public int getInt(int offset) {
        return buffer.getInt(offset);
    }

    public void putByte(int offset, byte value) {
        buffer.put(offset, value);
    }

    public byte getByte(int offset) {
        return buffer.get(offset);
    }

    public void putBytes(int offset, byte[] data) {
        // 使用绝对位置 API，而不是 buffer.put()，确保多线程并发读写同一块 Buffer 的不同区域时不会相互污染
        // TODO(perf): 逐字节 put 太慢；改用 Unsafe.copyMemory(data, BYTE_ARRAY_BASE_OFFSET,
        //   null, memoryStartAddress + offset, data.length) 一次性批量拷贝。见 BENCHMARKS.md §3 根因分析。
        for (int i = 0; i < data.length; i++) {
            buffer.put(offset + i, data[i]);
        }
    }

    public void getBytes(int offset, byte[] dest) {
        // TODO(perf): 同 putBytes，逐字节 get 是寻路热路径的主要开销之一；改 Unsafe.copyMemory 批量拷贝。
        for (int i = 0; i < dest.length; i++) {
            dest[i] = buffer.get(offset + i);
        }
    }

    /**
     * 精确追踪当前 MemTable 的真实物理内存开销
     */
    public int memoryUsage() {
        return allocateOffset.get();
    }
}