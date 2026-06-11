package com.jiashi.db.engine;

import com.jiashi.db.engine.index.hnsw.HnswIndex;
import com.jiashi.db.engine.math.VectorMath;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 向量检索基准：HNSW vs 暴力全扫
 *
 * 三个被测对象，层层剥洋葱定位开销：
 *   1. 暴力全扫 baseline —— SIMD L2 扫全量 + 大顶堆 topK（精确解，召回率定义为 1.0）
 *   2. 纯 HNSW 索引 —— 只测图搜索本身（不含 KV 打捞）
 *   3. 端到端 engine.searchVector —— HNSW + vectorId 反查 + query(key) 二次寻址全链路
 *
 * recall@K 以暴力全扫为对照组，在端到端路径上测（同时覆盖图质量与映射正确性）。
 */
public class VectorSearchBenchmark {

    private static final String BENCH_DIR = "./carina-vector-bench-data";

    private static final int N = 20_000;       // 入库向量数
    private static final int DIM = 128;        // 向量维度
    private static final int K = 10;           // topK
    private static final int WARMUP = 200;     // 预热查询数
    private static final int QUERY_COUNT = 1000; // 计时查询数
    private static final long SEED = 2026L;

    public static void main(String[] args) throws Exception {
        System.out.println("====== CarinaDB 向量检索基准（N=" + N + ", DIM=" + DIM + ", K=" + K + "）======");
        prepareDir();

        Random random = new Random(SEED);
        float[][] vectors = new float[N][];
        for (int i = 0; i < N; i++) vectors[i] = randomVector(random);

        float[][] queries = new float[WARMUP + QUERY_COUNT][];
        for (int i = 0; i < queries.length; i++) queries[i] = randomVector(random);

        // ---------------------------------------------------------
        // 阶段 1：建图耗时（纯 HNSW，无 WAL/fsync 干扰）
        // ---------------------------------------------------------
        System.out.println("\n[阶段 1] 纯 HNSW 建图 " + N + " 条...");
        HnswIndex pureIndex = new HnswIndex(16, 200);
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            pureIndex.insert(i, vectors[i]);
        }
        long buildNs = System.nanoTime() - t0;
        System.out.printf("    建图总耗时 %.1f s，平均 %.0f µs/条%n", buildNs / 1e9, buildNs / 1e3 / N);

        // ---------------------------------------------------------
        // 阶段 2：引擎全量写入（WAL + vlog + MemTable + HNSW）
        // ---------------------------------------------------------
        System.out.println("\n[阶段 2] 引擎端到端写入 " + N + " 条（fsync 主导，仅作参照）...");
        CarinaEngine engine = new CarinaEngine(BENCH_DIR);
        t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            engine.put(keyOf(i), payloadOf(i), vectors[i]);
        }
        long engineWriteNs = System.nanoTime() - t0;
        System.out.printf("    引擎写入总耗时 %.1f s，吞吐 %.0f ops/s%n",
                engineWriteNs / 1e9, N / (engineWriteNs / 1e9));

        // ---------------------------------------------------------
        // 阶段 3：暴力全扫 baseline（精确解 + 对照组答案）
        // ---------------------------------------------------------
        System.out.println("\n[阶段 3] 暴力全扫 baseline（" + QUERY_COUNT + " 次查询）...");
        // 预热
        for (int q = 0; q < WARMUP; q++) bruteForceTopK(queries[q], vectors, K);
        // 计时 + 留存对照组答案
        List<Set<Integer>> groundTruth = new ArrayList<>(QUERY_COUNT);
        t0 = System.nanoTime();
        for (int q = 0; q < QUERY_COUNT; q++) {
            groundTruth.add(bruteForceTopK(queries[WARMUP + q], vectors, K));
        }
        long bruteNs = System.nanoTime() - t0;
        report("暴力全扫", bruteNs, QUERY_COUNT);

        // ---------------------------------------------------------
        // 阶段 4：纯 HNSW 图搜索（不含 KV 打捞）
        // ---------------------------------------------------------
        System.out.println("\n[阶段 4] 纯 HNSW 图搜索...");
        for (int q = 0; q < WARMUP; q++) pureIndex.search(queries[q], K);
        t0 = System.nanoTime();
        long checksum = 0;
        for (int q = 0; q < QUERY_COUNT; q++) {
            checksum += pureIndex.search(queries[WARMUP + q], K).size();
        }
        long hnswNs = System.nanoTime() - t0;
        report("纯 HNSW", hnswNs, QUERY_COUNT);

        // ---------------------------------------------------------
        // 阶段 5：端到端 searchVector（HNSW + 反查 + 二次寻址）+ recall
        // ---------------------------------------------------------
        System.out.println("\n[阶段 5] 端到端 engine.searchVector + recall@" + K + "...");
        for (int q = 0; q < WARMUP; q++) engine.searchVector(queries[q], K);
        double totalRecall = 0;
        t0 = System.nanoTime();
        List<List<CarinaEngine.QueryResult>> allHits = new ArrayList<>(QUERY_COUNT);
        for (int q = 0; q < QUERY_COUNT; q++) {
            allHits.add(engine.searchVector(queries[WARMUP + q], K));
        }
        long e2eNs = System.nanoTime() - t0;
        report("端到端 searchVector", e2eNs, QUERY_COUNT);

        for (int q = 0; q < QUERY_COUNT; q++) {
            int overlap = 0;
            for (CarinaEngine.QueryResult hit : allHits.get(q)) {
                if (groundTruth.get(q).contains(idOf(hit.value))) overlap++;
            }
            totalRecall += (double) overlap / K;
        }
        double avgRecall = totalRecall / QUERY_COUNT;

        // ---------------------------------------------------------
        // 汇总
        // ---------------------------------------------------------
        System.out.println("\n====== 汇总 ======");
        System.out.printf("recall@%d        = %.4f（对照暴力精确解）%n", K, avgRecall);
        System.out.printf("HNSW vs 暴力     = %.1f× 加速（纯图搜索）%n", (double) bruteNs / hnswNs);
        System.out.printf("端到端 vs 暴力   = %.1f× 加速%n", (double) bruteNs / e2eNs);
        System.out.printf("KV 打捞开销占比  = %.0f%%（端到端耗时中非图搜索部分）%n",
                100.0 * (e2eNs - hnswNs) / e2eNs);
        System.out.println("(checksum=" + checksum + ", 防 JIT 死代码消除)");

        engine.close();
        System.exit(0);
    }

    // ============ 工具方法 ============

    private static void report(String name, long ns, int count) {
        System.out.printf("    [%s] 平均 %.1f µs/次，QPS = %.0f%n",
                name, ns / 1e3 / count, count / (ns / 1e9));
    }

    private static byte[] keyOf(int i) {
        return String.format("vec_%06d", i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] payloadOf(int i) {
        return ("payload_" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static int idOf(byte[] payload) {
        return Integer.parseInt(new String(payload, StandardCharsets.UTF_8).substring("payload_".length()));
    }

    private static float[] randomVector(Random random) {
        float[] v = new float[DIM];
        for (int d = 0; d < DIM; d++) v[d] = random.nextFloat() * 2 - 1;
        return v;
    }

    private static Set<Integer> bruteForceTopK(float[] query, float[][] vectors, int k) {
        PriorityQueue<int[]> heap = new PriorityQueue<>((a, b) -> Float.compare(
                Float.intBitsToFloat(b[1]), Float.intBitsToFloat(a[1])));
        for (int i = 0; i < vectors.length; i++) {
            float dist = VectorMath.l2DistanceSquareSimd(query, vectors[i]);
            if (heap.size() < k) {
                heap.offer(new int[]{i, Float.floatToIntBits(dist)});
            } else if (dist < Float.intBitsToFloat(heap.peek()[1])) {
                heap.poll();
                heap.offer(new int[]{i, Float.floatToIntBits(dist)});
            }
        }
        Set<Integer> result = new HashSet<>();
        for (int[] e : heap) result.add(e[0]);
        return result;
    }

    private static void prepareDir() {
        File dir = new File(BENCH_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
        } else {
            dir.mkdirs();
        }
    }
}
