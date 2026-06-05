package com.jiashi.db.engine.memtable;

import com.jiashi.db.common.model.LogRecord;
import com.jiashi.db.common.model.LogRecordType;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 堆外无锁跳表 vs JDK ConcurrentSkipListMap：单线程插入吞吐 + GC 开销对比。
 *
 * 核心论点（回答"你为什么不直接用 ConcurrentSkipListMap"）：
 * 自研 OffHeapSkipList 把节点写进堆外 Arena，百万级数据集几乎不占 JVM 堆、不给 GC 添堆内留存；
 * ConcurrentSkipListMap 每条都产生并长期持有堆对象（Node + key + value），堆内留存大、GC 压力高。
 *
 * 运行建议：java -Xmx1g -XX:MaxDirectMemorySize=1g ...（让两者都在受限堆下，对比才明显）
 */
public class SkipListVsJdkBenchmark {

    private static final int RECORDS = 1_000_000;
    private static final int WARMUP = 200_000;
    private static final int VALUE_SIZE = 100;

    public static void main(String[] args) {
        byte[][] keys = new byte[RECORDS][];
        for (int i = 0; i < RECORDS; i++) {
            keys[i] = String.format("key_%010d", i).getBytes(StandardCharsets.UTF_8);
        }
        byte[] value = new byte[VALUE_SIZE];

        System.out.println("=== OffHeapSkipList vs ConcurrentSkipListMap ===");
        System.out.printf("单线程插入 %,d 条, value = %d B%n%n", RECORDS, VALUE_SIZE);

        // 预热（结果丢弃）
        benchSkipList(keys, value, WARMUP, true);
        benchJdk(keys, value, WARMUP, true);

        System.out.println("结构                   |  耗时   |     吞吐(ops/s) | GC次数 | GC耗时 | 堆内留存 | 堆外占用");
        benchSkipList(keys, value, RECORDS, false);
        benchJdk(keys, value, RECORDS, false);
    }

    private static void benchSkipList(byte[][] keys, byte[] value, int count, boolean warmup) {
        Arena arena = new Arena((int) (count * 280L)); // 单节点上限按 280B 估，充裕预留
        OffHeapSkipList list = new OffHeapSkipList(arena);

        long heapBefore = usedHeapAfterGc();
        long[] gc0 = gcSnapshot();
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            list.put(new LogRecord(LogRecordType.PUT_KV, keys[i], value, null));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long[] gc1 = gcSnapshot();
        long heapAfter = usedHeapAfterGc();

        if (!warmup) report("OffHeapSkipList", count, ms, gc0, gc1,
                heapAfter - heapBefore, (long) arena.memoryUsage());
        // 防止 list/arena 被提前回收影响堆测量
        if (list.hashCode() == 0) System.out.print("");
    }

    private static void benchJdk(byte[][] keys, byte[] value, int count, boolean warmup) {
        ConcurrentSkipListMap<byte[], byte[]> map =
                new ConcurrentSkipListMap<>(SkipListVsJdkBenchmark::compareUnsigned);

        long heapBefore = usedHeapAfterGc();
        long[] gc0 = gcSnapshot();
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            map.put(keys[i], value);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long[] gc1 = gcSnapshot();
        long heapAfter = usedHeapAfterGc();

        if (!warmup) report("ConcurrentSkipListMap", count, ms, gc0, gc1,
                heapAfter - heapBefore, -1);
        if (map.size() == 0) System.out.print("");
    }

    private static void report(String name, int n, long ms, long[] gc0, long[] gc1,
                               long retainedBytes, long offHeapBytes) {
        double tps = n * 1000.0 / ms;
        String offHeap = offHeapBytes >= 0 ? (offHeapBytes / 1024 / 1024) + " MB" : "  —";
        System.out.printf("%-22s | %5d ms | %,13.0f | %5d | %4d ms | %6d MB | %s%n",
                name, ms, tps, gc1[0] - gc0[0], gc1[1] - gc0[1],
                retainedBytes / 1024 / 1024, offHeap);
    }

    /** 强制两轮 GC 后读取堆内留存，得到相对稳定的"结构净占用"估计（指示性，非精确）。 */
    private static long usedHeapAfterGc() {
        for (int i = 0; i < 2; i++) {
            System.gc();
            try { Thread.sleep(80); } catch (InterruptedException ignored) {}
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long[] gcSnapshot() {
        long count = 0, time = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = b.getCollectionCount();
            long t = b.getCollectionTime();
            if (c > 0) count += c;
            if (t > 0) time += t;
        }
        return new long[]{count, time};
    }

    private static int compareUnsigned(byte[] a, byte[] b) {
        int min = Math.min(a.length, b.length);
        for (int i = 0; i < min; i++) {
            int av = a[i] & 0xFF, bv = b[i] & 0xFF;
            if (av != bv) return av - bv;
        }
        return a.length - b.length;
    }
}
