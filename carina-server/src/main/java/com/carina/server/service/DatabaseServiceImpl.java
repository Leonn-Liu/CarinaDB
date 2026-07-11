package com.carina.server.service;

import com.carina.server.CarinaEngineJNA;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.NativeLongByReference;
import java.nio.charset.StandardCharsets;

public class DatabaseServiceImpl implements DatabaseService {

    private final Pointer engine;

    public DatabaseServiceImpl(String dbDirectory) {
        System.out.println("Initializing C++ Engine via JNA at " + dbDirectory);
        this.engine = CarinaEngineJNA.INSTANCE.carina_engine_create(dbDirectory);
    }

    @Override
    public void put(String key, String value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        CarinaEngineJNA.INSTANCE.carina_engine_put(engine, k, k.length, v, v.length);
        System.out.println("Put -> Key: " + key + ", Value: " + value);
    }

    @Override
    public String get(String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        PointerByReference outVal = new PointerByReference();
        NativeLongByReference outLen = new NativeLongByReference();
        
        int found = CarinaEngineJNA.INSTANCE.carina_engine_query(engine, k, k.length, outVal, outLen);
        if (found == 1) {
            Pointer p = outVal.getValue();
            if (p == null) {
                return "";
            }
            int len = outLen.getValue().intValue();
            byte[] v = p.getByteArray(0, len);
            CarinaEngineJNA.INSTANCE.carina_free_bytes(p);
            String result = new String(v, StandardCharsets.UTF_8);
            System.out.println("Get -> Key: " + key + ", Result: " + result);
            return result;
        }
        System.out.println("Get -> Key: " + key + " (Not Found)");
        return null;
    }

    @Override
    public void putVector(String key, String value, float[] vector) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        CarinaEngineJNA.INSTANCE.carina_engine_put_vector(engine, k, k.length, v, v.length, vector, vector.length);
        System.out.println("PutVector -> Key: " + key + ", Value: " + value + ", Vector length: " + vector.length);
    }

    @Override
    public String searchVector(float[] queryVector, int k) {
        PointerByReference outCsv = new PointerByReference();
        NativeLongByReference outCsvLen = new NativeLongByReference();
        
        int count = CarinaEngineJNA.INSTANCE.carina_engine_search_vector_keys(engine, queryVector, queryVector.length, k, outCsv, outCsvLen);
        if (count > 0) {
            Pointer p = outCsv.getValue();
            if (p != null) {
                int len = outCsvLen.getValue().intValue();
                byte[] csvBytes = p.getByteArray(0, len);
                CarinaEngineJNA.INSTANCE.carina_free_bytes(p);
                String result = new String(csvBytes, StandardCharsets.UTF_8);
                System.out.println("SearchVector -> Found " + count + " items: " + result);
                return result;
            }
        }
        System.out.println("SearchVector -> No results found.");
        return "";
    }

    public void close() {
        CarinaEngineJNA.INSTANCE.carina_engine_close(engine);
        CarinaEngineJNA.INSTANCE.carina_engine_destroy(engine);
    }
}
