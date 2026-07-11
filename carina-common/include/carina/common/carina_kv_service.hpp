#pragma once
#include <cstdint>
#include <vector>
#include <optional>

// 对应 com.jiashi.db.api.CarinaKVService 接口
namespace carina::common {

class CarinaKVService {
public:
    virtual ~CarinaKVService() = default;

    virtual bool put(const std::vector<uint8_t>& key, const std::vector<uint8_t>& value) = 0;

    virtual std::optional<std::vector<uint8_t>> get(const std::vector<uint8_t>& key) = 0;

    virtual bool remove(const std::vector<uint8_t>& key) = 0;

    // 核心扩展：写入带有向量的数据
    // key    主键
    // value  附加元数据 (比如图片的 URL、文本内容)
    // vector 高维向量 (比如大模型生成的 Embedding)
    virtual bool putVector(const std::vector<uint8_t>& key, const std::vector<uint8_t>& value,
                           const std::vector<float>& vector) = 0;

    // 向量相似度检索 (Top-K)
    virtual std::vector<std::vector<uint8_t>> searchVector(const std::vector<float>& vector, int topK) = 0;
};

} // namespace carina::common
