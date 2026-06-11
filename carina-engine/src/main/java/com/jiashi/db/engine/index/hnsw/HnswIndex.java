package com.jiashi.db.engine.index.hnsw;

import com.jiashi.db.engine.math.VectorMath;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Math.min;

public class HnswIndex {

    private final Map<Integer, HnswNode> nodes = new ConcurrentHashMap<>();

    // 全局状态图
    private int enterPointId = -1;
    private int maxLayer = -1;

    private final int maxM;           // 上层最大连接数
    private final int maxM0;          // 第 0 层最大连接数
    private final double mL;          // 层级概率乘数
    private final int efConstruction; // 建图时的候选集大小

    /**
     * @param maxM maxM 默认连接数 (通常为 16 或 32)
     * @param efConstruction efConstruction 建图探索范围 (通常为 100 到 200)
     */
    public HnswIndex(int maxM, int efConstruction){
        this.maxM = maxM;
        this.maxM0 = maxM * 2;
        this.mL = 1.0 / Math.log(maxM);
        this.efConstruction = efConstruction;
    }

    public static class NodeDistance{
        public final int nodeId;
        public final float distance;

        public NodeDistance(int nodeId, float distance){
            this.nodeId = nodeId;
            this.distance = distance;
        }
    }

    /**
     * 核心算法: 在指定的单层(lc)中搜索最近邻
     * @param query 目标查询向量
     * @param ep 入口节点的 ID
     * @param ef 需要保留的候选节点数量
     * @param lc 当前搜索的层级
     * @return 距离 query 最近的候选节点
     */
    public PriorityQueue<NodeDistance> searchLayer(float[] query, int ep, int ef, int lc){
        HashSet<Integer> visited = new HashSet<>();
        visited.add(ep);

        // 候选队列(小顶堆)
        PriorityQueue<NodeDistance> candidates = new PriorityQueue<>(Comparator.comparingDouble(
                n -> n.distance
        ));

        // 结果队列(大顶堆)
        PriorityQueue<NodeDistance> results = new PriorityQueue<>(
                (n1,n2) -> Float.compare(
                        n2.distance, n1.distance
                )
        );

        HnswNode epNode = nodes.get(ep);

        float initialDist = VectorMath.l2DistanceSquareSimd(query, epNode.vector);

        candidates.offer(new NodeDistance(ep,initialDist));
        results.offer(new NodeDistance(ep,initialDist));

        while(!candidates.isEmpty()){
            NodeDistance current = candidates.poll();

            NodeDistance furthestResult = results.peek();

            if(current.distance > furthestResult.distance){
                break;
            }

            HnswNode currNode = nodes.get(current.nodeId);
            for(Integer neighborId : currNode.neighbors.get(lc)){
                if(!visited.contains(neighborId)){
                    visited.add(neighborId);

                    HnswNode neighborNode = nodes.get(neighborId);
                    float neighborDist = VectorMath.l2DistanceSquareSimd(query, neighborNode.vector);

                    if(results.size() < ef || neighborDist < results.peek().distance){
                        candidates.offer(new NodeDistance(neighborId, neighborDist));
                        results.offer(new NodeDistance(neighborId, neighborDist));

                        if(results.size() > ef){
                            results.poll();
                        }
                    }
                }
            }
        }
        return results;
    }

    /**
     * 核心算法: 启发式裁剪
     * @param candidates 候选节点
     * @param targetVector 对比向量
     * @param layerMaxM 候选个数
     * @return
     */
    private List<Integer> selectHeuristic(PriorityQueue<NodeDistance> candidates, float[] targetVector, int layerMaxM){
        ArrayList<Integer> selected = new ArrayList<>(layerMaxM);

        PriorityQueue<NodeDistance> minHeap = new PriorityQueue<>(Comparator.comparingDouble(n -> n.distance));
        while(!candidates.isEmpty()){
            minHeap.offer(candidates.poll());
        }
        while(!minHeap.isEmpty() && selected.size() < layerMaxM){
            NodeDistance candidate = minHeap.poll();
            int candidateId = candidate.nodeId;
            float[] candidateVector = nodes.get(candidateId).vector;

            boolean keep = true;

            for (Integer Id : selected) {
                float[] vector = nodes.get(Id).vector;
                float distToSelect = VectorMath.l2DistanceSquareSimd(candidateVector, vector);
                if(distToSelect <= candidate.distance){
                    keep = false;
                    break;
                }
            }
            if(keep){
                selected.add(candidateId);
            }
        }
        return selected;
    }

    /**
     * 生成随机层级,遵循指数分布
     * @return
     */
    private int randomLevel(){
        double r = -Math.log(Math.random()) * this.mL;
        return (int)r;
    }

    /**
     *  核心算法: 多层插入与路由
     * @param id 插入节点的 ID
     * @param vector 插入节点的向量
     */
    public void insert(int id, float[] vector){
        int targetLevel = randomLevel();
        HnswNode newNode = new HnswNode(id, vector, targetLevel, this.maxM, this.maxM0);

        if(nodes.isEmpty()){
            enterPointId = id;
            maxLayer = targetLevel;
            nodes.put(id,newNode);
            return;
        }

        // 必须在连边之前注册：双向连边会把本节点 id 写进邻居的邻接表，
        // 邻居溢出裁剪时会按 id 反查 nodes，晚注册就是 NPE。
        // 此时尚无任何边指向本节点，searchLayer 不可能访问到它，提前注册不影响搜索。
        nodes.put(id, newNode);

        int currObj = enterPointId;
        int currMaxLayer = maxLayer;
        for(int lc = currMaxLayer; lc > targetLevel; lc--){
            PriorityQueue<NodeDistance> nearest = searchLayer(vector, currObj, 1, lc);
            currObj = nearest.poll().nodeId;
        }

        int minLayer = min(currMaxLayer, targetLevel);
        for(int lc = minLayer; lc >= 0; lc--){
            PriorityQueue<NodeDistance> candidates = searchLayer(vector, currObj, this.efConstruction, lc);

            int layerMaxM = (lc == 0) ? this.maxM0 : this.maxM;

            List<Integer> selectNeighbors = selectHeuristic(candidates, vector, layerMaxM);

            // 下一层入口必须用最近候选：candidates 是大顶堆，peek() 是最远的；
            // selectHeuristic 按距离升序返回且最近者永不被裁剪，首元素即全局最近。
            // 用最远候选当入口会让建图路由逐层跑偏，recall 随规模累积劣化。
            int nextObj = selectNeighbors.isEmpty() ? currObj : selectNeighbors.get(0);

            for (Integer neighorId : selectNeighbors) {
                newNode.neighbors.get(lc).add(neighorId);
                HnswNode neighborNode = nodes.get(neighorId);
                neighborNode.neighbors.get(lc).add(id);
                if (neighborNode.neighbors.get(lc).size() > layerMaxM) {
                    PriorityQueue<NodeDistance> neighborCandidates = new PriorityQueue<>(
                            Comparator.comparingDouble(n -> n.distance));
                    for (Integer nbrId : neighborNode.neighbors.get(lc)) {
                        float dist = VectorMath.l2DistanceSquareSimd(
                                neighborNode.vector, nodes.get(nbrId).vector);
                        neighborCandidates.offer(new NodeDistance(nbrId, dist));
                    }
                    List<Integer> prunedNeighbors = selectHeuristic(
                            neighborCandidates, neighborNode.vector, layerMaxM);
                    neighborNode.neighbors.get(lc).clear();
                    neighborNode.neighbors.get(lc).addAll(prunedNeighbors);
                }
            }
            currObj = nextObj;
        }
        if(targetLevel > maxLayer){
            maxLayer = targetLevel;
            enterPointId = id;
        }
    }

    /**
     * 核心算法: 近似最近邻查询
     * @param query 查询向量
     * @param k 返回的最近邻数量
     * @return 距离 query 最近的 k 个节点
     */
    public List<NodeDistance> search(float[] query, int k) {
        if (nodes.isEmpty()) return Collections.emptyList();

        int currObj = enterPointId;

        // 从顶层到第 1 层：每层只取最近的 1 个，贪心下降
        for (int lc = maxLayer; lc >= 1; lc--) {
            PriorityQueue<NodeDistance> nearest = searchLayer(query, currObj, 1, lc);
            currObj = nearest.poll().nodeId;
        }

        // 在第 0 层用完整的 ef 搜索
        PriorityQueue<NodeDistance> candidates = searchLayer(query, currObj, this.efConstruction, 0);

        // candidates 是大顶堆，转成有序列表取前 k 个
        List<NodeDistance> results = new ArrayList<>(candidates);
        results.sort(Comparator.comparingDouble(n -> n.distance));

        return results.subList(0, Math.min(k, results.size()));
    }
}
