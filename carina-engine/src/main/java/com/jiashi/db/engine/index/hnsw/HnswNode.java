package com.jiashi.db.engine.index.hnsw;

import java.util.ArrayList;
import java.util.List;

public class HnswNode {
    public final int id;
    public final float[] vector;
    public final int maxLevel;
    /**
     * 多层邻接表
     */
    public final List<List<Integer>> neighbors;

    public HnswNode(int id,float[] vector,int maxLevel,int maxM,int maxM0){
        this.id = id;
        this.vector = vector;
        this.maxLevel = maxLevel;
        this.neighbors = new ArrayList<>(maxLevel+1);
        for(int i=0;i<=maxLevel;i++){
            int capacity = (i==0) ? maxM0 : maxM;
            this.neighbors.add(new ArrayList<>(capacity));
        }
    }

}
