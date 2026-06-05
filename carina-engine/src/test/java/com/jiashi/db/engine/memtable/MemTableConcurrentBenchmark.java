package com.jiashi.db.engine.memtable;

import com.jiashi.db.common.model.LogRecord;
import com.jiashi.db.common.model.LogRecordType;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MemTable 并发写入 TPS：堆外无锁跳表 (OffHeapSkipList) vs JDK ConcurrentSkipListMap。
 *
 * 核心论点（回答"无锁 CAS 跳表到底比 JDK 现成结构强在哪"）：
 * OffHeapSkipList 的挂载临界区被降维成一条 Level 0 的 compareAndSwapInt（LOCK XADD / LSE CAS），
 * 没有任何互斥锁；线程数升高时硬件总线承担冲突仲裁，吞吐应随核数近线性放大。
 *
 * 同时这是一次 ARM64 对齐修复的实证压测：每个线程写一段不相交 key，跑完用 Level 0
 * 迭代器数出真实落库条数，断言 == 总插入数。若 CAS 因非对齐在 ARM 上半成功 / 撕裂，
 * 这里必然丢节点、计数对不上 —— 完整性校验通过，即等于证明对齐修复在并发下成立。
 *
 * 运行建议：
 *   java -Xmx1g -XX:MaxDirectMemorySize=2g \
 *     -cp "carina-engine/target/test-classes:carina-engine/target/classes:carina-common/target/classes" \
 *     com.jiashi.db.engine.memtable.MemTableConcurrentBenchmark
 */
public class MemTableConcurrentBenchmark {

    private static final int RECORDS = 1_000_000;
    private static final int VALUE_SIZE = 100;
    private static final int[] THREAD_COUNTS = {1, 2, 4, 8};

    public static void main(String[] args) throws Exception {
        // 预生成全部 key（"key_%010d"），按线程数等分成不相交区段，保证无重复 key、
        // 跑完总数必然 == RECORDS，从而能用计数做并发完整性校验。
        byte[][] keys = new byte[RECORDS][];
        for (int i = 0; i < RECORDS; i++) {
            keys[i] = String.format("key_%010d", i).getBytes(StandardCharsets.UTF_8);
        }
        byte[] value = new byte[VALUE_SIZE];

        System.out.println("=== MemTable 并发写入 TPS: OffHeapSkipList vs ConcurrentSkipListMap ===");
        System.out.printf("每档总写入 %,d 条, value = %d B, 线程内写不相交 key 段%n%n", RECORDS, VALUE_SIZE);

        // 预热（结果丢弃），让 JIT 把热点编译到 C2
        runSkipList(keys, value, 4, true);
        runJdk(keys, value, 4, true);

        System.out.printf("%-22s | %-7s | %9s | %13s | %s%n",
                "结构", "线程", "耗时(ms)", "吞吐(ops/s)", "完整性");
        System.out.println("-----------------------+---------+-----------+---------------+--------");
        for (int t : THREAD_COUNTS) {
            runSkipList(keys, value, t, false);
        }
        System.out.println("-----------------------+---------+-----------+---------------+--------");
        for (int t : THREAD_COUNTS) {
            runJdk(keys, value, t, false);
        }
    }

    // ---------------- OffHeapSkipList ----------------

    private static void runSkipList(byte[][] keys, byte[] value, int threads, boolean warmup) throws Exception {
        // 单节点上限按 200B 估（Header 20 + 指针 + key + value 100），充裕预留
        Arena arena = new Arena((int) (RECORDS * 200L));
        OffHeapSkipList list = new OffHeapSkipList(arena);

        long ms = parallelInsert(threads, (lo, hi) -> {
            for (int i = lo; i < hi; i++) {
                list.put(new LogRecord(LogRecordType.PUT_KV, keys[i], value, null));
            }
        });

        long counted = countLevel0(list);
        if (!warmup) report("OffHeapSkipList", threads, ms, counted);
        // 防止 list/arena 被提前回收
        if (list.hashCode() == 0) System.out.print("");
        arena = null;
        System.gc(); // 释放本轮堆外 DirectByteBuffer，避免多档累积撑爆直接内存
    }

    /** Level 0 迭代器全量计数 —— 这是节点是否真正原子挂载的唯一事实裁判。 */
    private static long countLevel0(OffHeapSkipList list) {
        long n = 0;
        for (LogRecord ignored : list) n++;
        return n;
    }

    // ---------------- ConcurrentSkipListMap ----------------

    private static void runJdk(byte[][] keys, byte[] value, int threads, boolean warmup) throws Exception {
        ConcurrentSkipListMap<byte[], byte[]> map =
                new ConcurrentSkipListMap<>(MemTableConcurrentBenchmark::compareUnsigned);

        long ms = parallelInsert(threads, (lo, hi) -> {
            for (int i = lo; i < hi; i++) {
                map.put(keys[i], value);
            }
        });

        if (!warmup) report("ConcurrentSkipListMap", threads, ms, map.size());
        if (map.isEmpty()) System.out.print("");
    }

    // ---------------- 通用并发压测器 ----------------

    private interface RangeTask {
        void run(int lo, int hi);
    }

    /**
     * CountDownLatch 统一发令的多线程压测：startGun 释放前所有线程已就绪并阻塞，
     * 从释放到全部 join 的墙钟时间即为本档真实并发耗时。返回毫秒。
     */
    private static long parallelInsert(int threads, RangeTask task) throws Exception {
        Thread[] pool = new Thread[threads];
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicLong elapsed = new AtomicLong();

        int chunk = RECORDS / threads;
        for (int t = 0; t < threads; t++) {
            final int lo = t * chunk;
            final int hi = (t == threads - 1) ? RECORDS : lo + chunk; // 末档兜底余数
            pool[t] = new Thread(() -> {
                ready.countDown();
                try { startGun.await(); } catch (InterruptedException e) { return; }
                task.run(lo, hi);
            });
            pool[t].start();
        }

        ready.await();                 // 等所有线程就位
        long t0 = System.nanoTime();
        startGun.countDown();          // 同时开闸
        for (Thread th : pool) th.join();
        elapsed.set((System.nanoTime() - t0) / 1_000_000);
        return elapsed.get();
    }

    private static void report(String name, int threads, long ms, long counted) {
        double tps = RECORDS * 1000.0 / ms;
        String integrity = (counted == RECORDS) ? "OK" : ("✗丢" + (RECORDS - counted));
        System.out.printf("%-22s | %5d   | %7d   | %,13.0f | %s%n",
                name, threads, ms, tps, integrity);
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