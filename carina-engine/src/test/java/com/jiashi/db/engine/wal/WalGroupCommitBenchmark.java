package com.jiashi.db.engine.wal;

import com.jiashi.db.common.coder.LogRecordCoder;
import com.jiashi.db.common.model.LogRecord;
import com.jiashi.db.common.model.LogRecordType;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WAL 组提交（Group Commit）吞吐基准。
 *
 * 固定总写入量，扫描并发线程数 {1,8,32,64}：
 * 单线程时每次 append 都独占一次 fsync，吞吐受磁盘同步延迟封顶；
 * 并发升高后，Leader 一次 drain 多个请求合并成一次 fsync，聚合吞吐随之抬升。
 * 这条曲线就是 group commit 价值的直接证据。
 */
public class WalGroupCommitBenchmark {

    private static final String DIR = "./bench-wal-data";
    // 单线程每次 append 独占一次 fsync，macOS 上 F_FULLFSYNC ~10ms，单线程吞吐极低，
    // 故总量取 5000：既能跑出单线程基线，又不至于等到天荒地老。
    private static final int TOTAL = 5_000;             // 每个并发档位的总 append 数
    private static final int[] THREADS = {1, 8, 32, 64};
    private static final int VALUE_SIZE = 100;          // 每条记录 value 字节数

    public static void main(String[] args) throws Exception {
        new File(DIR).mkdirs();
        byte[] value = new byte[VALUE_SIZE];
        for (int i = 0; i < VALUE_SIZE; i++) value[i] = (byte) ('a' + (i % 26));

        System.out.println("=== WAL Group Commit Benchmark ===");
        System.out.printf("总写入量/档位 = %,d, value = %d B%n", TOTAL, VALUE_SIZE);

        // 预热：先跑一档把 JIT 热起来，结果丢弃
        runOnce(8, TOTAL / 4, value);

        System.out.println("threads |  best(ms) |          TPS(ops/s)");
        for (int threads : THREADS) {
            long ms = runOnce(threads, TOTAL, value);
            double tps = TOTAL * 1000.0 / ms;
            System.out.printf("%7d | %8d | %,18.0f%n", threads, ms, tps);
        }
    }

    private static long runOnce(int threads, int total, byte[] value) throws Exception {
        File wf = new File(DIR, "gc-bench-" + threads + ".wal");
        if (wf.exists()) wf.delete();
        WAL wal = new WAL(DIR, wf.getName());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        int perThread = total / threads;

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        byte[] key = ("wal_" + tid + "_" + i).getBytes(StandardCharsets.UTF_8);
                        LogRecord r = new LogRecord(LogRecordType.PUT_KV, key, value, null);
                        wal.append(LogRecordCoder.encode(r));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        long t0 = System.nanoTime();
        start.countDown();
        done.await();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        pool.shutdown();
        wal.close();
        wf.delete();
        return elapsedMs;
    }
}
