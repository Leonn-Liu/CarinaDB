#pragma once
#include <unordered_map>
#include <list>
#include <mutex>
#include <optional>

// 对应 com.jiashi.db.engine.cache.BlockCache：LRU 块缓存，防止 OOM
namespace carina::engine::cache {

template <typename K, typename V>
class BlockCache {
public:
    explicit BlockCache(size_t maxCapacity) : maxCapacity_(maxCapacity) {}

    void put(const K& key, V value) {
        std::lock_guard<std::mutex> lg(mutex_);
        auto it = index_.find(key);
        if (it != index_.end()) {
            it->second->second = std::move(value);
            order_.splice(order_.begin(), order_, it->second);
            return;
        }
        order_.emplace_front(key, std::move(value));
        index_[key] = order_.begin();
        if (order_.size() > maxCapacity_) {
            index_.erase(order_.back().first);
            order_.pop_back();
        }
    }

    std::optional<V> get(const K& key) {
        std::lock_guard<std::mutex> lg(mutex_);
        auto it = index_.find(key);
        if (it == index_.end()) return std::nullopt;
        order_.splice(order_.begin(), order_, it->second);
        return it->second->second;
    }

private:
    using ListType = std::list<std::pair<K, V>>;
    size_t maxCapacity_;
    ListType order_;
    std::unordered_map<K, typename ListType::iterator> index_;
    std::mutex mutex_;
};

} // namespace carina::engine::cache
