package com.jiashi.db.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 百万级写入压多级 Compaction：把 L0→L1 后台归并真正压出来并观测。
 *
 * 设计要点：
 *  - 写 100 万条纯 KV（512B value，不带向量，专注 LSM/SSTable/Compaction 路径，排除 WiscKey 噪声）。
 *  - 大 value 让单条节点 ~560B，约 10 万条即填满 64MB MemTable 的 90% 阈值 → 触发一次 L0 flush；
 *    100 万条 ≈ 10 次 flush → L0 文件数反复越过 L0_COMPACTION_TRIGGER(4) → 后台守护反复归并。
 *  - 写完后轮询数据目录，观测 L0 被抽干、L1 超级块累积，证明 backgroundCompaction 真实生效。
 *
 * 观测口径：
 *  - 引擎自身打印的 "Flush 完成" / "[后台雷达]...大清剿" 日志 → 外层用 grep -c 数次数。
 *  - 本程序末尾的 [层级体检] → 数据目录里 L0-*.sst / L1-*.sst 的最终文件数与磁盘占用。
 *
 * 注意：当前引擎归并恒定 targetLevel=1 且 isBottomLevel=true —— 只有 L0/L1 两层，无 L1→L2；
 *      每轮 compaction 产出一个独立 L1 块、彼此不再合并。本基准如实观测这一架构事实。
 *
 * 运行：
 *   java -Xmx2g -XX:MaxDirectMemorySize=2g --add-opens java.base/java.nio=ALL-UNNAMED \
 *     -cp "carina-engine/target/test-classes:carina-engine/target/classes:carina-common/target/classes" \
 *     com.jiashi.db.engine.CompactionBenchmark
 */
public class CompactionBenchmark {

    private static final String DB_DIR = "carina-compaction-data";
    private static final int TOTAL_RECORDS = 1_000_000;
    private static final int THREAD_COUNT = 32;
    private static final int VALUE_SIZE = 512; // 大 value：快速填满 MemTable，逼出多次 L0 flush

    // 写完后等待 Compaction 抽干 L0 的轮询参数（守护进程 10s 一跳）
    private static final int DRAIN_POLL_SECONDS = 5;
    private static final int DRAIN_MAX_SECONDS = 90;

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("CarinaDB 多级 Compaction 压测");
        System.out.println(" - 数据量: " + String.format("%,d", TOTAL_RECORDS) + " 条纯 KV");
        System.out.println(" - 并发:   " + THREAD_COUNT + " 线程");
        System.out.println(" - value:  " + VALUE_SIZE + " B");
        System.out.println("==================================================\n");

        cleanUpOldData(DB_DIR);
        CarinaEngine engine = new CarinaEngine(DB_DIR);

        try {
            // ---------------- 并发写入 ----------------
            byte[] value = new byte[VALUE_SIZE];
            Arrays.fill(value, (byte) 'x');

            ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch endGate = new CountDownLatch(THREAD_COUNT);
            AtomicInteger failed = new AtomicInteger(0);
            int perThread = TOTAL_RECORDS / THREAD_COUNT;

            for (int t = 0; t < THREAD_COUNT; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int j = 0; j < perThread; j++) {
                            int globalId = threadId * perThread + j;
                            byte[] key = String.format("key_%08d", globalId).getBytes();
                            engine.put(key, value, null); // 纯 KV，无向量
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            long t0 = System.currentTimeMillis();
            startGate.countDown();
            endGate.await();
            long writeMs = System.currentTimeMillis() - t0;
            pool.shutdown();

            double qps = TOTAL_RECORDS * 1000.0 / writeMs;
            System.out.println("\n[写入战报] 耗时 " + writeMs + " ms, 吞吐 "
                    + String.format("%,.0f", qps) + " ops/s, 失败 " + failed.get());

            // ---------------- 轮询观测 Compaction 抽干 L0 ----------------
            System.out.println("\n[抽干观测] 写入结束，等待后台 Compaction 归并 L0 (守护 10s 一跳)...");
            System.out.printf("%-8s | %-7s | %-7s%n", "经过(s)", "L0 文件", "L1 文件");
            for (int elapsed = 0; elapsed <= DRAIN_MAX_SECONDS; elapsed += DRAIN_POLL_SECONDS) {
                int l0 = countFiles(DB_DIR, "L0-");
                int l1 = countFiles(DB_DIR, "L1-");
                System.out.printf("%-8d | %-7d | %-7d%n", elapsed, l0, l1);
                if (l0 < CarinaEngine_L0_TRIGGER && elapsed > 0) {
                    System.out.println("→ L0 已降到归并阈值以下，抽干完成。");
                    break;
                }
                Thread.sleep(DRAIN_POLL_SECONDS * 1000L);
            }

        } finally {
            engine.close();
            healthCheck(DB_DIR);
        }
    }

    // L0_COMPACTION_TRIGGER 在引擎内为 private，这里镜像其值用于判停（与 CarinaEngine 保持一致）
    private static final int CarinaEngine_L0_TRIGGER = 4;

    // ================== 观测辅助 ==================

    private static int countFiles(String dir, String prefix) {
        File[] fs = new File(dir).listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".sst"));
        return fs == null ? 0 : fs.length;
    }

    private static void healthCheck(String dir) {
        try {
            File[] l0 = new File(dir).listFiles((d, n) -> n.startsWith("L0-") && n.endsWith(".sst"));
            File[] l1 = new File(dir).listFiles((d, n) -> n.startsWith("L1-") && n.endsWith(".sst"));
            long total = Files.walk(Paths.get(dir)).filter(p -> p.toFile().isFile())
                    .mapToLong(p -> p.toFile().length()).sum();

            System.out.println("\n========== [层级体检] ==========");
            System.out.println("L0 残留文件: " + countOf(l0) + "  " + sizeOf(l0));
            System.out.println("L1 超级块:   " + countOf(l1) + "  " + sizeOf(l1));
            System.out.println("总磁盘占用:  " + String.format("%.2f", total / 1024.0 / 1024.0) + " MB");
            if (l1 != null) {
                for (File f : l1) {
                    System.out.println("  └ " + f.getName() + "  "
                            + String.format("%.1f", f.length() / 1024.0 / 1024.0) + " MB");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static int countOf(File[] fs) {
        return fs == null ? 0 : fs.length;
    }

    private static String sizeOf(File[] fs) {
        if (fs == null || fs.length == 0) return "(0 MB)";
        long s = 0;
        for (File f : fs) s += f.length();
        return "(" + String.format("%.1f", s / 1024.0 / 1024.0) + " MB)";
    }

    private static void cleanUpOldData(String dirPath) {
        File dir = new File(dirPath);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
        }
    }
}
