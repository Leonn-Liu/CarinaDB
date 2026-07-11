#ifndef CARINA_C_API_H
#define CARINA_C_API_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void* CarinaEnginePtr;

CarinaEnginePtr carina_engine_create(const char* db_directory);
void carina_engine_close(CarinaEnginePtr engine);
void carina_engine_destroy(CarinaEnginePtr engine);

void carina_engine_put(CarinaEnginePtr engine, 
                       const uint8_t* key, size_t key_len, 
                       const uint8_t* value, size_t value_len);

int carina_engine_query(CarinaEnginePtr engine, 
                        const uint8_t* key, size_t key_len, 
                        uint8_t** out_value, size_t* out_value_len);

void carina_engine_put_vector(CarinaEnginePtr engine,
                              const uint8_t* key, size_t key_len,
                              const uint8_t* value, size_t value_len,
                              const float* vector, size_t vector_len);

int carina_engine_search_vector_keys(CarinaEnginePtr engine,
                                     const float* query_vector, size_t vector_len,
                                     int k, uint8_t** out_keys_csv, size_t* out_keys_csv_len);

void carina_free_bytes(uint8_t* ptr);

#ifdef __cplusplus
}
#endif

#endif // CARINA_C_API_H
