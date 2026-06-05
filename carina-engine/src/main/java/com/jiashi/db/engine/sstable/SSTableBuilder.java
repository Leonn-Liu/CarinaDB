package com.jiashi.db.engine.sstable;

import com.jiashi.db.common.filter.BloomFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 核心写链路：负责流式接收数据，生成 Data Block，并最终拼接元数据和 Footer 封盘
 */
public class SSTableBuilder {

    private final FileChannel channel;
    private final BlockBuilder dataBlockBuilder;

    // ======== 宏观元数据收集器 ========
    private final BloomFilter bloomFilter;
    private final List<IndexEntry> memIndex = new ArrayList<>();
    private byte[] minKey = null;
    private byte[] maxKey = null;

    // 记录当前磁盘物理写入指针的绝对位置
    private long currentOffset = 0;
    // 记录刚刚写入的最后一个 Key，用于给 Data Block 当界标
    private byte[] lastKey;

    // 目录项结构：存放某个 Data Block 的上界 Key 及其物理偏移量
    private static class IndexEntry {
        byte[] maxKey;
        long blockOffset;

        IndexEntry(byte[] maxKey, long blockOffset) {
            this.maxKey = maxKey;
            this.blockOffset = blockOffset;
        }
    }

    /**
     * @param expectedInsertions 预计写入的 Key 数量（从 MemTable 大小推算，用于精准初始化 BloomFilter）
     */
    public SSTableBuilder(Path filePath, int expectedInsertions) throws IOException {
        this.channel = FileChannel.open(filePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        this.dataBlockBuilder = new BlockBuilder();

        // 事实：设定 1% 的误判率，初始化工业级布隆过滤器
        this.bloomFilter = new BloomFilter(expectedInsertions, 0.01);
    }

    /**
     * 接收 MemTable 或 Compactor 传来的数据
     * @param type    物理类型 (PUT_KV, PUT_VECTOR, DELETE)
     * @param pointer 20 字节藏宝图 (如果是纯 KV 或墓碑，传 null)
     */
    public void add(byte[] key, byte[] value, byte type, byte[] pointer) throws IOException {
        if (minKey == null) {
            minKey = Arrays.copyOf(key, key.length);
        }
        maxKey = Arrays.copyOf(key, key.length);
        bloomFilter.add(key);
        if(!dataBlockBuilder.add(key,value,type,pointer))
        {
            flushDataBlock();
            dataBlockBuilder.add(key,value,type,pointer);
        }
        this.lastKey = Arrays.copyOf(key, key.length);
    }

    /**
     * 砌砖落地：将 4KB 小推车里的数据真正写入磁盘
     */
    private void flushDataBlock() throws IOException {
        if (dataBlockBuilder.isEmpty()) return;

        ByteBuffer dataBuffer = dataBlockBuilder.finish();

        // 极其重要：在落盘前，把这块砖的绝对坐标和它的 MaxKey 记录到内存目录里
        memIndex.add(new IndexEntry(lastKey, currentOffset));

        // 发生真实磁盘 I/O
        int written = 0;
        while (dataBuffer.hasRemaining()) {
            written += channel.write(dataBuffer);
        }

        // 物理指针向前推进
        currentOffset += written;
        dataBlockBuilder.reset();
    }

    /**
     * 终极结账：封盘打钢印
     */
    public void finish() throws IOException {
        // 1. 先把推车里最后剩下的一点半成品数据刷入磁盘
        flushDataBlock();

        // ------------------------------------------------
        // 阶段 1：写入 Bloom Filter Block
        // ------------------------------------------------
        long filterOffset = currentOffset; // 记录起点
        byte[] filterBytes = bloomFilter.serialize();
        ByteBuffer filterBuffer = ByteBuffer.wrap(filterBytes);
        while (filterBuffer.hasRemaining()) {
            currentOffset += channel.write(filterBuffer);
        }

        // ------------------------------------------------
        // 阶段 2：写入 Index Block
        // ------------------------------------------------
        long indexBlockOffset = currentOffset; // 记录起点
        // 计算 Index Block 总大小
        int indexSize = 0;
        for (IndexEntry entry : memIndex) {
            indexSize += Integer.BYTES + entry.maxKey.length + Integer.BYTES + Long.BYTES;
        }

        ByteBuffer indexBuffer = ByteBuffer.allocateDirect(indexSize);
        for (IndexEntry entry : memIndex) {
            indexBuffer.putInt(entry.maxKey.length);
            indexBuffer.put(entry.maxKey);
            // 写入 Value 长度 (Offset 是 long，固定 8 字节) 和 具体的 Offset
            indexBuffer.putInt(8);
            indexBuffer.putLong(entry.blockOffset);
        }
        indexBuffer.flip();
        while (indexBuffer.hasRemaining()) {
            currentOffset += channel.write(indexBuffer);
        }

        // ------------------------------------------------
        // 阶段 3：在尾部明文追加 MinKey 和 MaxKey
        // ------------------------------------------------
        long minKeyOffset = currentOffset;
        writeKeyWithLength(minKey);

        long maxKeyOffset = currentOffset;
        writeKeyWithLength(maxKey);

        // ------------------------------------------------
        // 阶段 4：用我们刚刚写好的 Footer 打上 40 字节定长钢印
        // ------------------------------------------------
        Footer.writeFooter(channel, filterOffset, indexBlockOffset, minKeyOffset, maxKeyOffset);

        // 物理事实：强制操作系统将页面缓存 (Page Cache) 刷入物理磁盘，防止掉电丢失
        channel.force(true);
        channel.close();
    }

    // 辅助序列化工具
    private void writeKeyWithLength(byte[] key) throws IOException {
        // 防止数据为空引发的空指针异常 (理论上不该发生)
        if (key == null) key = new byte[0];

        ByteBuffer buf = ByteBuffer.allocate(4 + key.length);
        buf.putInt(key.length);
        buf.put(key);
        buf.flip();
        while (buf.hasRemaining()) {
            currentOffset += channel.write(buf);
        }
    }
}