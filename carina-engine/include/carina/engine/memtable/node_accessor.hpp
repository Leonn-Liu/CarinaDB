#pragma once
#include "carina/engine/memtable/arena.hpp"
#include "carina/common/log_record.hpp"
#include <vector>

// 对应 com.jiashi.db.engine.memtable.NodeAccessor
// 物理内存布局（与 Java 版一致）：
// [Type(1B)+Pad(3B)][KeyLen(4B)][ValLen(4B)][PointerLen(4B)][Height(4B)]  <- Header(20B)
// [NextPointers(Height*4B)]                                              <- 指针区
// [KeyBytes][ValBytes][PointerBytes]                                     <- 数据区
namespace carina::engine::memtable {

class NodeAccessor {
public:
    static constexpr int HEADER_SIZE = 20;

    static int allocateAndWriteNode(Arena& arena, const carina::common::LogRecord& record, int height) {
        const auto& key = record.getKey();
        const auto& value = record.getValue();
        const auto& pointerOpt = record.getBlobPointer();

        int keyLen = static_cast<int>(key.size());
        int valLen = static_cast<int>(value.size());
        int ptrLen = pointerOpt.has_value() ? static_cast<int>(pointerOpt->size()) : 0;

        int totalSize = HEADER_SIZE + (height * 4) + keyLen + valLen + ptrLen;
        int baseOffset = arena.allocate(totalSize);

        arena.putByte(baseOffset + TYPE_OFFSET, record.getType());
        arena.putInt(baseOffset + KEY_LEN_OFFSET, keyLen);
        arena.putInt(baseOffset + VAL_LEN_OFFSET, valLen);
        arena.putInt(baseOffset + PTR_LEN_OFFSET, ptrLen);
        arena.putInt(baseOffset + HEIGHT_OFFSET, height);

        for (int i = 0; i < height; i++) {
            setNextOffset(arena, baseOffset, i, 0);
        }

        int cursor = baseOffset + HEADER_SIZE + (height * 4);
        if (keyLen > 0) { arena.putBytes(cursor, key); cursor += keyLen; }
        if (valLen > 0) { arena.putBytes(cursor, value); cursor += valLen; }
        if (ptrLen > 0) { arena.putBytes(cursor, *pointerOpt); cursor += ptrLen; }

        return baseOffset;
    }

    static uint8_t getType(const Arena& arena, int baseOffset) { return arena.getByte(baseOffset + TYPE_OFFSET); }
    static int getKeyLength(const Arena& arena, int baseOffset) { return arena.getInt(baseOffset + KEY_LEN_OFFSET); }
    static int getValueLength(const Arena& arena, int baseOffset) { return arena.getInt(baseOffset + VAL_LEN_OFFSET); }
    static int getPointerLength(const Arena& arena, int baseOffset) { return arena.getInt(baseOffset + PTR_LEN_OFFSET); }
    static int getHeight(const Arena& arena, int baseOffset) { return arena.getInt(baseOffset + HEIGHT_OFFSET); }

    static std::vector<uint8_t> getKey(const Arena& arena, int baseOffset) {
        int keyLen = getKeyLength(arena, baseOffset);
        if (keyLen == 0) return {};
        int height = getHeight(arena, baseOffset);
        std::vector<uint8_t> key(keyLen);
        int keyStart = baseOffset + HEADER_SIZE + (height * 4);
        arena.getBytes(keyStart, key.data(), keyLen);
        return key;
    }

    static int getPointersAreaOffset(int baseOffset) { return baseOffset + HEADER_SIZE; }

    static int getNextOffset(const Arena& arena, int baseOffset, int level) {
        return arena.getInt(getPointersAreaOffset(baseOffset) + (level * 4));
    }
    static void setNextOffset(Arena& arena, int baseOffset, int level, int targetNodeOffset) {
        arena.putInt(getPointersAreaOffset(baseOffset) + (level * 4), targetNodeOffset);
    }
    static int getNextPointerAbsoluteAddress(int baseOffset, int level) {
        return getPointersAreaOffset(baseOffset) + (level * 4);
    }

private:
    static constexpr int TYPE_OFFSET = 0;
    static constexpr int KEY_LEN_OFFSET = 4;
    static constexpr int VAL_LEN_OFFSET = 8;
    static constexpr int PTR_LEN_OFFSET = 12;
    static constexpr int HEIGHT_OFFSET = 16;
};

} // namespace carina::engine::memtable
