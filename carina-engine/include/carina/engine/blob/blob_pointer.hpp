#pragma once
#include "carina/common/byte_io.hpp"
#include <cstdint>
#include <vector>

// 对应 com.jiashi.db.engine.blob.BlobPointer：向量数据的物理寻址指针 (20 字节)
namespace carina::engine::blob {

struct BlobPointer {
    int64_t fileId;
    int64_t offset;
    int32_t vectorDim;

    std::vector<uint8_t> toBytes() const {
        std::vector<uint8_t> out;
        out.reserve(20);
        carina::common::putInt64BE(out, fileId);
        carina::common::putInt64BE(out, offset);
        carina::common::putInt32BE(out, vectorDim);
        return out;
    }

    static BlobPointer fromBytes(const std::vector<uint8_t>& data) {
        carina::common::ByteReader in(data.data(), data.size());
        BlobPointer p{};
        p.fileId = in.getInt64BE();
        p.offset = in.getInt64BE();
        p.vectorDim = in.getInt32BE();
        return p;
    }
};

} // namespace carina::engine::blob
