package com.carina.server.service;

public interface DatabaseService {
    void put(String key, String value);
    String get(String key);
    
    void putVector(String key, String value, float[] vector);
    String searchVector(float[] vector, int k);
}
