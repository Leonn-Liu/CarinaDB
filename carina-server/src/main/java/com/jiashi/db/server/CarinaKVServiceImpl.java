package com.jiashi.db.server;

import com.jiashi.db.api.CarinaKVService;
import com.jiashi.db.engine.CarinaEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CarinaKVServiceImpl implements CarinaKVService {

    private final CarinaEngine engine;

    public CarinaKVServiceImpl(CarinaEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean put(byte[] key, byte[] value) {
        try{
            if(key == null || value == null){
                return false;
            }
            engine.put(key, value,null);
            return true;
        }catch (IOException ex){
            System.err.println("网络层写入物理引擎失败" +  ex.getMessage());
            return false;
        }
    }

    @Override
    public byte[] get(byte[] key) {
        try{
            if(key == null){
                return null;
            }
            return engine.get(key);
        }catch (IOException ex){
            System.err.println("网络层读取物理引擎失败"  +  ex.getMessage());
            return null;
        }
    }

    @Override
    public boolean delete(byte[] key) {
        try {
            if (key == null) return false;
            // 物理墓碑
            engine.put(key, new byte[0]);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean putVector(byte[] key, byte[] value, float[] vector) {
        try{
            if(key == null || value == null || vector.length == 0){
                return false;
            }
            engine.put(key, value, vector);
            return true;
        }catch (IOException ex){
            System.err.println("网络层写入向量数据失败"+  ex.getMessage());
            return false;
        }
    }

    @Override
    public List<byte[]> searchVector(float[] vector, int topK) {
        // TODO: HNSW 向量检索留到我们做 AI 引擎时再写
        System.err.println("收到向量检索请求，HNSW 引擎待点亮...");
        return new ArrayList<>();
    }
}

