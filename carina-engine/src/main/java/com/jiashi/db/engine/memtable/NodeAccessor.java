package com.jiashi.db.engine.memtable;

import com.jiashi.db.common.model.LogRecord;

/**
 * 堆外跳表节点的物理寻址器
 * 物理内存布局事实：
 * [Type(1B) + Pad(3B)] [KeyLen(4B)] [ValLen(4B)] [PointerLen(4B)] [Height(4B)]  <-- 固定 Header 区 (20B)
 * [NextPointers(Height * 4B)]                                         <-- 指针区 (紧随 Header)
 * [KeyBytes] [ValBytes] [PointerBytes]                                <-- 数据区
 */
public final class NodeAccessor {

    private NodeAccessor() {}

    // --- 物理头部偏移量常量事实 ---
    private static final int TYPE_OFFSET = 0;
    private static final int KEY_LEN_OFFSET = 4;
    private static final int VAL_LEN_OFFSET = 8;
    private static final int PTR_LEN_OFFSET = 12;
    private static final int HEIGHT_OFFSET = 16;

    public static final int HEADER_SIZE = 20;

    public static int allocateAndWriteNode(Arena arena, LogRecord record, int height) {
        byte[] key = record.getKey();
        byte[] value = record.getValue();
        byte[] pointer = record.getBlobPointer();

        int keyLen = key != null ? key.length : 0;
        int valLen = value != null ? value.length : 0;
        int ptrLen = pointer != null ? pointer.length : 0;

        // 1. 精确计算所需总物理内存
        int totalSize = HEADER_SIZE + (height * 4) + keyLen + valLen + ptrLen;

        // 2. 向 Arena 申请无锁连续内存块，获得绝对物理基址
        int baseOffset = arena.allocate(totalSize);

        // 3. 写入头部物理信息
        arena.putByte(baseOffset + TYPE_OFFSET, record.getType());
        arena.putInt(baseOffset + KEY_LEN_OFFSET, keyLen);
        arena.putInt(baseOffset + VAL_LEN_OFFSET, valLen);
        arena.putInt(baseOffset + PTR_LEN_OFFSET, ptrLen);
        arena.putInt(baseOffset + HEIGHT_OFFSET, height);

        // 4. 初始化所有 Next Pointers 为 0 (必须在写变长数据前完成)
        // 在我们的布局中，指针区紧挨着 Header
        for (int i = 0; i < height; i++) {
            setNextOffset(arena, baseOffset, i, 0);
        }

        // 5. 写入变长数据区 (游标卡尺向后滑动)
        // 事实：游标必须先跨过 Header 和所有的 Next 指针坑位！
        int currentWriteCursor = baseOffset + HEADER_SIZE + (height * 4);

        if (keyLen > 0) {
            arena.putBytes(currentWriteCursor, key);
            currentWriteCursor += keyLen;
        }

        if (valLen > 0) {
            arena.putBytes(currentWriteCursor, value);
            currentWriteCursor += valLen;
        }

        if (ptrLen > 0) {
            arena.putBytes(currentWriteCursor, pointer);
            currentWriteCursor += ptrLen;
        }

        return baseOffset;
    }

    public static byte getType(Arena arena, int baseOffset) {
        return arena.getByte(baseOffset + TYPE_OFFSET);
    }

    public static int getKeyLength(Arena arena, int baseOffset) {
        return arena.getInt(baseOffset + KEY_LEN_OFFSET);
    }

    public static int getValueLength(Arena arena, int baseOffset) {
        return arena.getInt(baseOffset + VAL_LEN_OFFSET);
    }

    public static int getPointerLength(Arena arena, int baseOffset) {
        return arena.getInt(baseOffset + PTR_LEN_OFFSET);
    }

    public static int getHeight(Arena arena, int baseOffset) {
        return arena.getInt(baseOffset + HEIGHT_OFFSET);
    }

    /**
     * 读取真实的 Key 字节数组
     *
     * TODO(perf): 寻路热路径（findPredecessors/getRecord）每次比较都调用本方法，
     *   每次都 new 一个 byte[] 并逐字节从堆外拷贝 —— 这是 BENCHMARKS.md §3/§4 中
     *   OffHeapSkipList 插入吞吐落后 ConcurrentSkipListMap ~2x、且反常触发 minor GC 的根因。
     *   优化方向：新增 compareKeyAt(arena, baseOffset, targetKey) 直接对堆外字节与目标 key
     *   逐字节比较、零中间分配（RocksDB Slice 视图思路），寻路时改调它而非 getKey。
     */
    public static byte[] getKey(Arena arena, int baseOffset) {
        int keyLen = getKeyLength(arena, baseOffset);
        if (keyLen == 0) return new byte[0];

        int height = getHeight(arena, baseOffset);
        byte[] key = new byte[keyLen];

        // 事实：计算 Key 的绝对物理地址：基址 + Header 大小 + 指针区大小
        int keyStartOffset = baseOffset + HEADER_SIZE + (height * 4);
        arena.getBytes(keyStartOffset, key);
        return key;
    }

    // ==========================================
    // 跳表寻址灵魂：Next Pointers 的读写 (已优化为 O(1) 绝对极速寻址)
    // ==========================================

    /**
     * O(1) 极速计算指针区的起始物理偏移量
     */
    private static int getPointersAreaOffset(int baseOffset) {
        // 事实：指针区紧挨着固定长度的 Header，没有任何冗余的内存读取
        return baseOffset + HEADER_SIZE;
    }

    public static int getNextOffset(Arena arena, int baseOffset, int level) {
        int ptrAreaStart = getPointersAreaOffset(baseOffset);
        return arena.getInt(ptrAreaStart + (level * 4));
    }

    public static void setNextOffset(Arena arena, int baseOffset, int level, int targetNodeOffset) {
        int ptrAreaStart = getPointersAreaOffset(baseOffset);
        arena.putInt(ptrAreaStart + (level * 4), targetNodeOffset);
    }

    public static int getNextPointerAbsoluteAddress(Arena arena, int baseOffset, int level) {
        return getPointersAreaOffset(baseOffset) + (level * 4);
    }
}