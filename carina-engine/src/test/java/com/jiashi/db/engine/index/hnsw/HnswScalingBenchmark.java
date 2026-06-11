package com.jiashi.db.engine.index.hnsw;

import com.jiashi.db.engine.math.VectorMath;

import java.util.*;

/**
 * HNSW vs 暴力全扫的规模扫描基准（纯内存，不含引擎/磁盘）
 *
 * 动机：单点 N=2 万的测试显示暴力 SIMD 全扫反而更快 —— 暴力扫描是连续内存、
 * SIMD 友好的 O(N)；HNSW 是指针跳跃 + 装箱开销的近似 O(logN)，常数项大。
 * 本基准扫描 N，找出两条曲线的交叉点，回答"多大规模之后 HNSW 才划算"。
 *
 * 每档 N 输出：建图耗时、暴力 QPS、HNSW QPS、加速比、recall@10。
 *
 * 数据分布（args[0]，默认 uniform）：
 *   uniform  —— 128 维均匀随机。ANN 最恶劣输入：高维距离集中，所有点对几乎等距，
 *               recall 随 N 劣化是数据特性而非实现缺陷（ann-benchmarks 社区共识）。
 *   manifold —— 本征维度 16 的高斯流形线性嵌入 128 维（+1% 噪声），
 *               模拟真实 embedding 的低维流形结构。
 */
public class HnswScalingBenchmark {

    private static final int[] SCALES = {20_000, 50_000, 100_000, 200_000};
    private static final int DIM = 128;
    private static final int INTRINSIC_DIM = 16;
    private static final int K = 10;
    private static final int WARMUP = 50;
    private static final int QUERY_COUNT = 300;
    private static final long SEED = 2026L;

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "uniform";
        System.out.println("====== HNSW 规模扫描基准（DIM=" + DIM + ", K=" + K
                + ", M=16, efC=200, query=" + QUERY_COUNT + " 次/档, 数据分布=" + mode + "）======");

        int maxN = SCALES[SCALES.length - 1];
        Random random = new Random(SEED);
        float[][] manifoldBasis = null;
        if ("manifold".equals(mode)) {
            manifoldBasis = new float[DIM][INTRINSIC_DIM];
            for (int i = 0; i < DIM; i++)
                for (int j = 0; j < INTRINSIC_DIM; j++)
                    manifoldBasis[i][j] = (float) random.nextGaussian();
        }

        float[][] vectors = new float[maxN][];
        for (int i = 0; i < maxN; i++) vectors[i] = genVector(random, manifoldBasis);

        float[][] queries = new float[WARMUP + QUERY_COUNT][];
        for (int i = 0; i < queries.length; i++) queries[i] = genVector(random, manifoldBasis);

        System.out.printf("%n%-9s %-11s %-14s %-14s %-9s %-10s%n",
                "N", "建图(s)", "暴力(µs/q)", "HNSW(µs/q)", "加速比", "recall@10");

        for (int n : SCALES) {
            // ---- 建图 ----
            HnswIndex index = new HnswIndex(16, 200);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) index.insert(i, vectors[i]);
            double buildSec = (System.nanoTime() - t0) / 1e9;

            // ---- 预热（两条路径都热）----
            for (int q = 0; q < WARMUP; q++) {
                bruteForceTopK(queries[q], vectors, n, K);
                index.search(queries[q], K);
            }

            // ---- 暴力计时 + 留存对照组 ----
            List<Set<Integer>> truth = new ArrayList<>(QUERY_COUNT);
            t0 = System.nanoTime();
            for (int q = 0; q < QUERY_COUNT; q++) {
                truth.add(bruteForceTopK(queries[WARMUP + q], vectors, n, K));
            }
            long bruteNs = System.nanoTime() - t0;

            // ---- HNSW 计时 ----
            List<List<HnswIndex.NodeDistance>> hnswHits = new ArrayList<>(QUERY_COUNT);
            t0 = System.nanoTime();
            for (int q = 0; q < QUERY_COUNT; q++) {
                hnswHits.add(index.search(queries[WARMUP + q], K));
            }
            long hnswNs = System.nanoTime() - t0;

            // ---- recall ----
            double recall = 0;
            for (int q = 0; q < QUERY_COUNT; q++) {
                int overlap = 0;
                for (HnswIndex.NodeDistance hit : hnswHits.get(q)) {
                    if (truth.get(q).contains(hit.nodeId)) overlap++;
                }
                recall += (double) overlap / K;
            }
            recall /= QUERY_COUNT;

            System.out.printf("%-9d %-11.1f %-14.1f %-14.1f %-9.2f %-10.4f%n",
                    n, buildSec,
                    bruteNs / 1e3 / QUERY_COUNT,
                    hnswNs / 1e3 / QUERY_COUNT,
                    (double) bruteNs / hnswNs,
                    recall);
        }
        System.out.println("\n====== 扫描结束 ======");
    }

    private static float[] genVector(Random random, float[][] manifoldBasis) {
        float[] v = new float[DIM];
        if (manifoldBasis == null) {
            for (int d = 0; d < DIM; d++) v[d] = random.nextFloat() * 2 - 1;
            return v;
        }
        float[] z = new float[INTRINSIC_DIM];
        for (int j = 0; j < INTRINSIC_DIM; j++) z[j] = (float) random.nextGaussian();
        for (int i = 0; i < DIM; i++) {
            float s = 0;
            for (int j = 0; j < INTRINSIC_DIM; j++) s += manifoldBasis[i][j] * z[j];
            v[i] = s + 0.01f * (float) random.nextGaussian();
        }
        return v;
    }

    /** 只扫前 n 条，精确 topK */
    private static Set<Integer> bruteForceTopK(float[] query, float[][] vectors, int n, int k) {
        PriorityQueue<int[]> heap = new PriorityQueue<>((a, b) -> Float.compare(
                Float.intBitsToFloat(b[1]), Float.intBitsToFloat(a[1])));
        for (int i = 0; i < n; i++) {
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
}
