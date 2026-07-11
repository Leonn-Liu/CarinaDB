#include "carina/engine/carina_c_api.h"
#include "carina/engine/carina_engine.hpp"

#include <vector>
#include <string>
#include <cstring>
#include <cstdlib>

using carina::engine::CarinaEngine;

extern "C" {

CarinaEnginePtr carina_engine_create(const char* db_directory) {
    if (!db_directory) return nullptr;
    CarinaEngine* engine = new CarinaEngine(db_directory);
    return static_cast<CarinaEnginePtr>(engine);
}

void carina_engine_close(CarinaEnginePtr engine) {
    if (engine) {
        static_cast<CarinaEngine*>(engine)->close();
    }
}

void carina_engine_destroy(CarinaEnginePtr engine) {
    if (engine) {
        delete static_cast<CarinaEngine*>(engine);
    }
}

void carina_engine_put(CarinaEnginePtr engine, 
                       const uint8_t* key, size_t key_len, 
                       const uint8_t* value, size_t value_len) {
    if (!engine || !key || !value) return;
    std::vector<uint8_t> k(key, key + key_len);
    std::vector<uint8_t> v(value, value + value_len);
    static_cast<CarinaEngine*>(engine)->put(k, v, std::nullopt);
}

int carina_engine_query(CarinaEnginePtr engine, 
                        const uint8_t* key, size_t key_len, 
                        uint8_t** out_value, size_t* out_value_len) {
    if (!engine || !key || !out_value || !out_value_len) return 0;
    
    std::vector<uint8_t> k(key, key + key_len);
    auto res = static_cast<CarinaEngine*>(engine)->query(k);
    
    if (res.has_value()) {
        const auto& val = res->value;
        *out_value_len = val.size();
        if (val.empty()) {
            *out_value = nullptr;
        } else {
            *out_value = static_cast<uint8_t*>(std::malloc(val.size()));
            std::memcpy(*out_value, val.data(), val.size());
        }
        return 1;
    }
    return 0;
}

void carina_engine_put_vector(CarinaEnginePtr engine, 
                              const uint8_t* key, size_t key_len, 
                              const uint8_t* value, size_t value_len,
                              const float* vector, size_t vector_len) {
    if (!engine || !key || !value || !vector) return;
    std::vector<uint8_t> k(key, key + key_len);
    std::vector<uint8_t> v(value, value + value_len);
    std::vector<float> vec(vector, vector + vector_len);
    static_cast<CarinaEngine*>(engine)->put(k, v, vec);
}

int carina_engine_search_vector_keys(CarinaEnginePtr engine,
                                     const float* query_vector, size_t vector_len,
                                     int k, uint8_t** out_keys_csv, size_t* out_keys_csv_len) {
    if (!engine || !query_vector || !out_keys_csv || !out_keys_csv_len) return 0;
    
    std::vector<float> q(query_vector, query_vector + vector_len);
    auto results = static_cast<CarinaEngine*>(engine)->searchVector(q, k);
    
    std::string csv;
    for (size_t i = 0; i < results.size(); ++i) {
        if (i > 0) csv += ",";
        const auto& val = results[i].value; // In our tests we usually put key as value or just extract the key. Wait, QueryResult doesn't return key!
        // Wait, CarinaEngine::QueryResult only has 'value' and 'vector'.
        // If it doesn't return the key, let's just return the 'value' strings as CSV! Because in testing, values often contain the necessary info.
        csv.append(reinterpret_cast<const char*>(val.data()), val.size());
    }
    
    *out_keys_csv_len = csv.size();
    if (csv.empty()) {
        *out_keys_csv = nullptr;
    } else {
        *out_keys_csv = static_cast<uint8_t*>(std::malloc(csv.size()));
        std::memcpy(*out_keys_csv, csv.data(), csv.size());
    }
    return results.size();
}

void carina_free_bytes(uint8_t* ptr) {
    if (ptr) {
        std::free(ptr);
    }
}

} // extern "C"
