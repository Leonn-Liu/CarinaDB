package com.jiashi.db.engine.memtable;

import com.jiashi.db.common.model.LogRecord;
import com.jiashi.db.common.model.LogRecordType;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工业级堆外无锁跳表：彻底零 GC，纯物理指针寻址
 */
public class OffHeapSkipList implements Iterable<LogRecord> {

    // 事实：跳表的最大物理层高。LevelDB 默认是 12，RocksDB 默认是 16。
    // 我们采用 12，意味着头节点最多预留 12 个 int 的坑位。
    private static final int MAX_HEIGHT = 12;
    // 抛硬币概率：每升高一层的概率是 25% (空间和时间的最佳工程折中)
    private static final double PROBABILITY = 0.25;

    private final Arena arena;
    // 跳表永远的入口：头节点的物理绝对偏移量
    private final int headOffset;

    // Java 底层黑魔法：绕过 JVM，直接操作物理内存
    private static final Unsafe UNSAFE;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new Error("系统不支持 Unsafe 操作，无法启动引擎", e);
        }
    }

    public OffHeapSkipList(Arena arena) {
        this.arena = arena;
        // 事实：在 Arena 创建之初，强制分配一个拥有最大层高的“哑节点”作为车头
        // 我们构造一个空的 LogRecord 作为占位符
        LogRecord dummyRecord = new LogRecord((byte)0, new byte[0], new byte[0], null);
        this.headOffset = NodeAccessor.allocateAndWriteNode(arena, dummyRecord, MAX_HEIGHT);
    }

    @Override
    public Iterator<LogRecord> iterator() {
        return new SkipListIterator();
    }

    /**
     * 核心写入链路：将请求转化为堆外物理节点，并无锁挂载
     */
    public void put(LogRecord record) {
        // 1. 物理事实生成：掷骰子决定这个新节点要建几层高铁站
        int randomHeight = randomHeight();

        // 2. 内存切分：在 Arena 中开辟空间，写入数据，拿到新节点的 offset
        // 此时，它的 next pointers 全部是 0 (NULL)
        int newNodeOffset = NodeAccessor.allocateAndWriteNode(arena, record, randomHeight);

        // 3. 并发寻路数组：准备两个数组，记录每一层插入位置的“前驱节点”和“后继节点”
        // preds[0] 就是第 0 层新节点前面的那个老节点的 offset
        int[] preds = new int[MAX_HEIGHT];
        int[] succs = new int[MAX_HEIGHT];

        // --- 开始无锁循环重试 (Lock-Free Retry Loop) ---
        while (true) {
            // 4. 核心寻路算法：从最高层往下找，填满 preds 和 succs 数组
            // (这个方法的实现极其精妙，我们下一步单独写)
            findPredecessors(record.getKey(), preds, succs);

            // 5. 组装新节点：把新节点的 Next 指向找到的后继节点
            // 注意：这一步是安全的，因为新节点还没挂到主链表上，别的线程看不到它
            for (int i = 0; i < randomHeight; i++) {
                NodeAccessor.setNextOffset(arena, newNodeOffset, i, succs[i]);
            }

            // 6. 决定性的硬件降维打击：CAS 原子挂载最底层 (Level 0)
            // 物理事实：跳表的最底层包含了所有数据。只要 Level 0 挂载成功，数据就不会丢。
            int predLevel0Offset = preds[0];
            int succLevel0Offset = succs[0];

            // 算出前驱节点在 Level 0 的 Next 指针所在的绝对物理内存地址
            long predNextPointerAddr = arena.getMemoryStartAddress() +
                    NodeAccessor.getNextPointerAbsoluteAddress(arena, predLevel0Offset, 0);

            // 呼叫 CPU：
            // "请比对我指定的内存地址，如果它现在的值依然是 succLevel0Offset，说明没人插队，请原子地把它改成 newNodeOffset！"
            boolean level0Success = UNSAFE.compareAndSwapInt(null, predNextPointerAddr, succLevel0Offset, newNodeOffset);

            if (!level0Success) {
                // 如果失败了，说明在我们寻路到准备修改的这几纳秒内，有别的线程在这个位置插队了！
                // 无锁编程的代价：老老实实回到 while(true) 起点，重新寻路，重新尝试。
                continue;
            }

            // 7. Level 0 挂载成功后，依次把上面的高速公路也接上 (Level 1 到 randomHeight - 1)
            // 高层的失败不影响数据完整性，但为了完美，通常也是 CAS 循环接上
            for (int i = 1; i < randomHeight; i++) {
                while (true) {
                    long upperPtrAddr = arena.getMemoryStartAddress() +
                            NodeAccessor.getNextPointerAbsoluteAddress(arena, preds[i], i);
                    if (UNSAFE.compareAndSwapInt(null, upperPtrAddr, succs[i], newNodeOffset)) {
                        break; // 这一层接上了，去接更高一层
                    }
                    // 高层接续失败，需要重新找高层的前驱，这是高阶优化，V1 阶段可先略过复杂的重试
                    // 重新寻路...
                    findPredecessors(record.getKey(), preds, succs);
                }
            }

            // 全部挂载完毕，物理写入彻底闭环！
            break;
        }
    }

    /**
     * 概率引擎：模拟抛硬币，连续抛出正面的次数决定层高
     */
    private int randomHeight() {
        int height = 1;
        while (height < MAX_HEIGHT && ThreadLocalRandom.current().nextDouble() < PROBABILITY) {
            height++;
        }
        return height;
    }

    private void findPredecessors(byte[] targetKey, int[] preds, int[] succs) {
        // 事实 1：寻路永远从车头（哑节点）开始
        int currentOffset = headOffset;

        // 事实 2：从最高层的高速公路开始往下遍历
        for (int level = MAX_HEIGHT - 1; level >= 0; level--) {
            // 读取当前节点在当前层的 next 指针（物理偏移量）
            int nextOffset = NodeAccessor.getNextOffset(arena, currentOffset, level);

            // 事实 3：水平推进
            // 0 通常在堆外内存设计中代表 NULL（无效地址）
            while (nextOffset != 0) {
                // 从堆外内存中提取 next 节点的 Key
                byte[] nextKey = NodeAccessor.getKey(arena, nextOffset);

                // 进行字节数组的字典序比较
                if (compareKeys(nextKey, targetKey) < 0) {
                    // 如果 next节点的 key 小于我们要找的 key，说明还没到位，继续往右走
                    currentOffset = nextOffset;
                    nextOffset = NodeAccessor.getNextOffset(arena, currentOffset, level);
                } else {
                    // 如果 next节点的 key 大于等于目标 key，说明在这一层不能再往右了
                    break;
                }
            }

            // 事实 4：记录当前层的快照，供后续的 CAS 挂载使用
            preds[level] = currentOffset;
            succs[level] = nextOffset;

            // 循环继续，level--，意味着指针向下掉一层，基于当前的 currentOffset 继续向右找
        }
    }

    /**
     * 无锁读取：返回包含pointLen的完整 LogRecord
     */
    public LogRecord getRecord(byte[] targetKey) {
        int currentOffset = headOffset;
        for(int level = MAX_HEIGHT - 1; level >= 0; level--) {
            int nextOffset = NodeAccessor.getNextOffset(arena, currentOffset, level);
            while (nextOffset != 0) {
                byte[] nextKey = NodeAccessor.getKey(arena, nextOffset);
                int cmp  = compareKeys(nextKey, targetKey);
                if (cmp < 0) {
                    currentOffset = nextOffset;
                    nextOffset = NodeAccessor.getNextOffset(arena, currentOffset, level);
                }else if(cmp == 0){
                    // 多版本事实：同 key 的新版本（含墓碑）永远插在等值区头部，
                    // 但旧节点的塔可能更高——高层撞到的等值节点不保证是最新版本。
                    // 因此高层命中只能当作 >= 处理继续降层，唯有 Level 0 等值区的
                    // 第一个节点才是最新版本，可以放心返回。
                    if (level > 0) {
                        break;
                    }
                    byte type = NodeAccessor.getType(arena, nextOffset);
                    int valLen = NodeAccessor.getValueLength(arena, nextOffset);
                    byte[] value = new byte[valLen];
                    int height = NodeAccessor.getHeight(arena, nextOffset);

                    int valStartOffset = nextOffset + NodeAccessor.HEADER_SIZE + (height * 4) + nextKey.length;
                    arena.getBytes(valStartOffset, value);

                    int pointerLen = NodeAccessor.getPointerLength(arena, nextOffset);
                    byte[] pointerBytes = null;
                    if (pointerLen > 0) {
                        pointerBytes = new byte[pointerLen];
                        // Pointer 的绝对物理坐标，就紧紧贴在 Value 的屁股后面！
                        int pointerStartOffset = valStartOffset + valLen;
                        arena.getBytes(pointerStartOffset, pointerBytes);
                    }

                    return new LogRecord(type, nextKey, value, pointerBytes);
                }else{
                    break;
                }
            }
        }
        return null;
    }

    /**
     * 无锁删除的工业级事实：追加逻辑墓碑 (Tombstone)
     */
    public void delete(byte[] key) {
        // 构造一个 Type 为 DELETE，且 Value 为空的占位节点
        LogRecord tombstone = new LogRecord(
                LogRecordType.DELETE,
                key,
                new byte[0], // 释放 Value 空间
                null         // 释放 Pointer 空间
        );
        // 复用put的无锁挂载逻辑，将墓碑插入跳表
        this.put(tombstone);
    }

    /**
     * 事实体现：极轻量级的堆外内存游标
     */
    /**
     * 事实体现：极轻量级的堆外内存游标
     * 物理法则：跳表的最底层 (Level 0) 就是一条包含了所有数据的有序单向链表。
     * 我们只需要顺着 Level 0 的 next_offset 物理指针一路往下爬即可。
     */
    private class SkipListIterator implements Iterator<LogRecord> {
        // 游标：指向当前准备读取的节点在 Arena 中的绝对物理偏移量
        private int currentOffset;

        public SkipListIterator() {
            // 初始化事实：从跳表的物理车头 (headOffset) 开始，获取它在最底层 (Level 0) 的下一个真实节点
            this.currentOffset = NodeAccessor.getNextOffset(arena, headOffset, 0);
        }

        @Override
        public boolean hasNext() {
            // 物理事实：在我们的堆外内存设计中，0 代表 NULL (链表尽头)
            return currentOffset != 0;
        }

        @Override
        public LogRecord next() {
            if (!hasNext()) {
                throw new NoSuchElementException("SkipList is fully traversed.");
            }

            // 1. 从堆外内存解析当前节点的数据
            byte type = NodeAccessor.getType(arena, currentOffset);
            byte[] key = NodeAccessor.getKey(arena, currentOffset);

            // 计算 Value 的位置并提取
            int valLen = NodeAccessor.getValueLength(arena, currentOffset);
            byte[] value = new byte[valLen];
            int height = NodeAccessor.getHeight(arena, currentOffset);
            int valStartOffset = currentOffset + NodeAccessor.HEADER_SIZE + (height * 4) + key.length;
            arena.getBytes(valStartOffset, value);

            int ptrLen = NodeAccessor.getPointerLength(arena, currentOffset);
            byte[] pointer = null;
            if (ptrLen > 0) {
                pointer = new byte[ptrLen];
                int ptrStartOffset = valStartOffset + valLen;
                arena.getBytes(ptrStartOffset, pointer);
            }

            // 2. 组装成高层能理解的 LogRecord
            LogRecord record = new LogRecord(type, key, value, pointer);

            // 3. 游标推进：强制读取当前节点的 Level 0 指针，驶向下一个物理节点
            currentOffset = NodeAccessor.getNextOffset(arena, currentOffset, 0);

            return record;
        }
    }

    /**
     * 字节数组的无符号字典序比对（数据库底层的通用比对方式）
     * 返回值: < 0 (a < b), == 0 (a == b), > 0 (a > b)
     */
    private int compareKeys(byte[] a, byte[] b) {
        int minLength = Math.min(a.length, b.length);
        for (int i = 0; i < minLength; i++) {
            // 将 byte 转为无符号 int 进行比较，避免 Java 默认的有符号负数干扰
            int aVal = a[i] & 0xFF;
            int bVal = b[i] & 0xFF;
            if (aVal != bVal) {
                return aVal - bVal;
            }
        }
        return a.length - b.length;
    }
}