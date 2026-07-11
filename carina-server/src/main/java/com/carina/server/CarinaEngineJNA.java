package com.carina.server;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.NativeLongByReference;

public interface CarinaEngineJNA extends Library {
    CarinaEngineJNA INSTANCE = Native.load("carina_engine", CarinaEngineJNA.class);

    Pointer carina_engine_create(String db_directory);
    void carina_engine_close(Pointer engine);
    void carina_engine_destroy(Pointer engine);

    void carina_engine_put(Pointer engine, 
                           byte[] key, long key_len, 
                           byte[] value, long value_len);

    int carina_engine_query(Pointer engine, 
                            byte[] key, long key_len, 
                            PointerByReference out_value, NativeLongByReference out_value_len);

    void carina_engine_put_vector(Pointer engine, 
                                  byte[] key, long key_len, 
                                  byte[] value, long value_len, 
                                  float[] vector, long vector_len);

    int carina_engine_search_vector_keys(Pointer engine, 
                                         float[] query_vector, long vector_len, 
                                         int k, PointerByReference out_keys_csv, NativeLongByReference out_keys_csv_len);

    void carina_free_bytes(Pointer ptr);
}
