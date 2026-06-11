package com.jiashi.db.engine;

import com.jiashi.db.engine.math.VectorMath;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 向量功能全链路正确性测试：
 * put(带向量) → vlog 落盘 + HNSW 建索引 → searchVector → vectorId 反查 key → query 二次寻址取回
 *
 * 五个阶段：
 *   1. 点查回捞：query(key) 取回的向量与写入的逐元素一致（vlog 往返无损）
 *   2. ANN recall@10：searchVector vs 全量暴力扫描，平均 recall 必须 >= 0.90
 *   3. 自查询：用已入库向量本身做查询，top-1 必须命中自己（距离 0 是图上最强信号）
 *   4. 删除一致性：delete 后的 key 绝不允许出现在检索结果中（HNSW 不支持删除，
 *      靠 query(key) 撞墓碑返回 null 兜底过滤 —— 本阶段验证的就是这道防线）
 *   5. 结果完整性：返回的每个 QueryResult 必须 value/vector 双全
 */
public class VectorPipelineCorrectnessTest {

    private static final String TEST_DIR = "./carina-vector-test-data";

    private static final int N = 3000;        // 入库向量数
    private static final int DIM = 128;       // 向量维度
    private static final int K = 10;          // topK
    private static final int QUERY_COUNT = 50; // recall 测试的查询次数
    private static final long SEED = 42L;     // 固定种子，保证可复现

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("====== CarinaDB 向量全链路正确性测试开始 ======");
        prepareTestDir();

        CarinaEngine engine = new CarinaEngine(TEST_DIR);
        Random random = new Random(SEED);

        // ---------------------------------------------------------
        // 阶段 0：写入 N 条带向量数据，内存中留一份做对照组
        // ---------------------------------------------------------
        System.out.println("\n[阶段 0] 写入 " + N + " 条 " + DIM + " 维向量数据...");
        float[][] vectors = new float[N][];
        long writeStart = System.nanoTime();
        for (int i = 0; i < N; i++) {
            vectors[i] = randomVector(random);
            engine.put(keyOf(i), payloadOf(i), vectors[i]);
        }
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
        System.out.println("✅ 写入完成，耗时 " + writeMs + " ms（含 HNSW 同步建图）");

        // ---------------------------------------------------------
        // 阶段 1：点查回捞 —— vlog 二次寻址往返必须无损
        // ---------------------------------------------------------
        System.out.println("\n[阶段 1] 随机点查 100 条，校验 value 与向量逐元素一致...");
        int roundTripOk = 0;
        for (int t = 0; t < 100; t++) {
            int i = random.nextInt(N);
            CarinaEngine.QueryResult r = engine.query(keyOf(i));
            if (r != null
                    && Arrays.equals(r.value, payloadOf(i))
                    && r.vector != null
                    && Arrays.equals(r.vector, vectors[i])) {
                roundTripOk++;
            }
        }
        check("点查向量回捞", roundTripOk == 100, roundTripOk + "/100 完全一致");

        // ---------------------------------------------------------
        // 阶段 2：ANN recall@K —— 对照全量暴力扫描
        // ---------------------------------------------------------
        System.out.println("\n[阶段 2] " + QUERY_COUNT + " 次随机查询，对照暴力扫描算 recall@" + K + "...");
        double totalRecall = 0;
        int completeResults = 0;
        for (int q = 0; q < QUERY_COUNT; q++) {
            float[] query = randomVector(random);
            Set<Integer> truth = bruteForceTopK(query, vectors, K, Collections.emptySet());

            List<CarinaEngine.QueryResult> hits = engine.searchVector(query, K);
            if (hits.size() == K) completeResults++;

            int overlap = 0;
            for (CarinaEngine.QueryResult hit : hits) {
                if (truth.contains(idOf(hit.value))) overlap++;
            }
            totalRecall += (double) overlap / K;
        }
        double avgRecall = totalRecall / QUERY_COUNT;
        System.out.printf("    平均 recall@%d = %.4f，满额返回 %d/%d%n", K, avgRecall, completeResults, QUERY_COUNT);
        check("ANN recall@" + K + " >= 0.90", avgRecall >= 0.90, String.format("%.4f", avgRecall));

        // ---------------------------------------------------------
        // 阶段 3：自查询 —— 入库向量查自己，top-1 必须是自己
        // ---------------------------------------------------------
        System.out.println("\n[阶段 3] 100 次自查询（用已入库向量查 top-1）...");
        int selfHit = 0;
        for (int t = 0; t < 100; t++) {
            int i = random.nextInt(N);
            List<CarinaEngine.QueryResult> hits = engine.searchVector(vectors[i], 1);
            if (!hits.isEmpty() && idOf(hits.get(0).value) == i) selfHit++;
        }
        check("自查询 top-1 命中率 >= 95%", selfHit >= 95, selfHit + "/100");

        // ---------------------------------------------------------
        // 阶段 4：删除一致性 —— 删掉的 key 绝不能再被检索出来
        // ---------------------------------------------------------
        System.out.println("\n[阶段 4] 删除 20 个 key 后重新检索，校验墓碑过滤...");
        Set<Integer> deleted = new HashSet<>();
        float[] probeQuery = randomVector(random);
        // 专门把 probeQuery 的暴力 top-K 全部删掉，逼检索结果换血
        for (int id : bruteForceTopK(probeQuery, vectors, K, Collections.emptySet())) {
            engine.delete(keyOf(id));
            deleted.add(id);
        }
        // 再随机补删 10 个
        while (deleted.size() < K + 10) {
            int i = random.nextInt(N);
            engine.delete(keyOf(i));
            deleted.add(i);
        }

        boolean ghostFound = false;
        List<CarinaEngine.QueryResult> afterDelete = engine.searchVector(probeQuery, K);
        for (CarinaEngine.QueryResult hit : afterDelete) {
            if (deleted.contains(idOf(hit.value))) ghostFound = true;
        }
        check("删除的 key 不出现在结果中", !ghostFound,
                "删 " + deleted.size() + " 个，检索返回 " + afterDelete.size() + " 条");

        // 删除后存活数据的 recall 仍要达标（对照组同样剔除已删项）
        double recallAfterDelete = 0;
        for (int q = 0; q < QUERY_COUNT; q++) {
            float[] query = randomVector(random);
            Set<Integer> truth = bruteForceTopK(query, vectors, K, deleted);
            int overlap = 0;
            for (CarinaEngine.QueryResult hit : engine.searchVector(query, K)) {
                if (truth.contains(idOf(hit.value))) overlap++;
            }
            recallAfterDelete += (double) overlap / K;
        }
        recallAfterDelete /= QUERY_COUNT;
        System.out.printf("    删除后存活集 recall@%d = %.4f%n", K, recallAfterDelete);
        check("删除后 recall 仍 >= 0.85", recallAfterDelete >= 0.85, String.format("%.4f", recallAfterDelete));

        // ---------------------------------------------------------
        // 阶段 5：结果完整性 —— 每条结果 value/vector 必须双全
        // ---------------------------------------------------------
        System.out.println("\n[阶段 5] 校验检索结果的字段完整性...");
        boolean allComplete = true;
        for (int q = 0; q < 10; q++) {
            for (CarinaEngine.QueryResult hit : engine.searchVector(randomVector(random), K)) {
                if (hit.value == null || hit.vector == null || hit.vector.length != DIM) {
                    allComplete = false;
                }
            }
        }
        check("QueryResult value/vector 双全", allComplete, "10 轮 × topK 全检");

        engine.close();

        System.out.println("\n====== 测试结束：" + (failures == 0 ? "全部通过 ✅" : failures + " 项失败 ❌") + " ======");
        if (failures > 0) System.exit(1);
    }

    // ============ 工具方法 ============

    private static byte[] keyOf(int i) {
        return String.format("vec_%05d", i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] payloadOf(int i) {
        return ("payload_" + i).getBytes(StandardCharsets.UTF_8);
    }

    /** 从 payload 反推向量编号，用于和暴力扫描的对照组比对 */
    private static int idOf(byte[] payload) {
        return Integer.parseInt(new String(payload, StandardCharsets.UTF_8).substring("payload_".length()));
    }

    private static float[] randomVector(Random random) {
        float[] v = new float[DIM];
        for (int d = 0; d < DIM; d++) {
            v[d] = random.nextFloat() * 2 - 1;
        }
        return v;
    }

    /** 对照组：全量暴力扫描的精确 topK（可剔除已删除项） */
    private static Set<Integer> bruteForceTopK(float[] query, float[][] vectors, int k, Set<Integer> excluded) {
        PriorityQueue<int[]> heap = new PriorityQueue<>((a, b) -> Float.compare(
                Float.intBitsToFloat(b[1]), Float.intBitsToFloat(a[1]))); // 大顶堆，堆顶最远
        for (int i = 0; i < vectors.length; i++) {
            if (excluded.contains(i)) continue;
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

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("✅ [" + name + "] " + detail);
        } else {
            System.out.println("❌ [" + name + "] " + detail);
            failures++;
        }
    }

    private static void prepareTestDir() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
        } else {
            dir.mkdirs();
        }
    }
}
