// 小范围数据集正确性验证工具：作为一个 TCP 客户端连接 carina-server，
// 对 put/get/delete/putVector/searchVector 全链路做黑盒正确性校验。
#include "carina/common/byte_io.hpp"
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cstring>
#include <iostream>
#include <random>
#include <string>
#include <vector>
#include <unordered_map>
#include <cassert>

namespace {

constexpr uint8_t OP_PUT = 0;
constexpr uint8_t OP_GET = 1;
constexpr uint8_t OP_DELETE = 2;
constexpr uint8_t OP_PUT_VECTOR = 3;
constexpr uint8_t OP_SEARCH_VECTOR = 4;

class Client {
public:
    Client(const std::string& host, int port) {
        fd_ = ::socket(AF_INET, SOCK_STREAM, 0);
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(port);
        addr.sin_addr.s_addr = inet_addr(host.c_str());
        if (::connect(fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
            throw std::runtime_error("connect failed");
        }
    }
    ~Client() { if (fd_ >= 0) ::close(fd_); }

    bool put(const std::vector<uint8_t>& key, const std::vector<uint8_t>& value) {
        std::vector<uint8_t> req;
        req.push_back(OP_PUT);
        carina::common::putInt32BE(req, (int32_t)key.size());
        carina::common::putBytes(req, key);
        carina::common::putInt32BE(req, (int32_t)value.size());
        carina::common::putBytes(req, value);
        auto resp = roundTrip(req);
        return !resp.empty() && resp[0] == 1;
    }

    bool get(const std::vector<uint8_t>& key, std::vector<uint8_t>& outValue) {
        std::vector<uint8_t> req;
        req.push_back(OP_GET);
        carina::common::putInt32BE(req, (int32_t)key.size());
        carina::common::putBytes(req, key);
        auto resp = roundTrip(req);
        if (resp.empty() || resp[0] == 0) return false;
        carina::common::ByteReader in(resp.data() + 1, resp.size() - 1);
        int32_t len = in.getInt32BE();
        outValue = in.getBytes(len);
        return true;
    }

    bool remove(const std::vector<uint8_t>& key) {
        std::vector<uint8_t> req;
        req.push_back(OP_DELETE);
        carina::common::putInt32BE(req, (int32_t)key.size());
        carina::common::putBytes(req, key);
        auto resp = roundTrip(req);
        return !resp.empty() && resp[0] == 1;
    }

    bool putVector(const std::vector<uint8_t>& key, const std::vector<uint8_t>& value, const std::vector<float>& vec) {
        std::vector<uint8_t> req;
        req.push_back(OP_PUT_VECTOR);
        carina::common::putInt32BE(req, (int32_t)key.size());
        carina::common::putBytes(req, key);
        carina::common::putInt32BE(req, (int32_t)value.size());
        carina::common::putBytes(req, value);
        carina::common::putInt32BE(req, (int32_t)vec.size());
        for (float f : vec) {
            int32_t bits; std::memcpy(&bits, &f, 4);
            carina::common::putInt32BE(req, bits);
        }
        auto resp = roundTrip(req);
        return !resp.empty() && resp[0] == 1;
    }

    std::vector<std::vector<uint8_t>> searchVector(const std::vector<float>& vec, int topK) {
        std::vector<uint8_t> req;
        req.push_back(OP_SEARCH_VECTOR);
        carina::common::putInt32BE(req, (int32_t)vec.size());
        for (float f : vec) {
            int32_t bits; std::memcpy(&bits, &f, 4);
            carina::common::putInt32BE(req, bits);
        }
        carina::common::putInt32BE(req, topK);
        auto resp = roundTrip(req);
        std::vector<std::vector<uint8_t>> out;
        if (resp.empty() || resp[0] == 0) return out;
        carina::common::ByteReader in(resp.data() + 1, resp.size() - 1);
        int32_t count = in.getInt32BE();
        for (int i = 0; i < count; i++) {
            int32_t len = in.getInt32BE();
            out.push_back(in.getBytes(len));
        }
        return out;
    }

private:
    std::vector<uint8_t> roundTrip(const std::vector<uint8_t>& payload) {
        std::vector<uint8_t> lenBuf;
        carina::common::putInt32BE(lenBuf, (int32_t)payload.size());
        ::send(fd_, lenBuf.data(), lenBuf.size(), 0);
        ::send(fd_, payload.data(), payload.size(), 0);

        uint8_t rlenBuf[4];
        size_t got = 0;
        while (got < 4) {
            ssize_t n = ::recv(fd_, rlenBuf + got, 4 - got, 0);
            if (n <= 0) throw std::runtime_error("connection closed");
            got += n;
        }
        carina::common::ByteReader lin(rlenBuf, 4);
        int32_t len = lin.getInt32BE();
        std::vector<uint8_t> resp(len);
        got = 0;
        while (got < (size_t)len) {
            ssize_t n = ::recv(fd_, resp.data() + got, len - got, 0);
            if (n <= 0) throw std::runtime_error("connection closed");
            got += n;
        }
        return resp;
    }

    int fd_ = -1;
};

std::vector<uint8_t> strToBytes(const std::string& s) { return std::vector<uint8_t>(s.begin(), s.end()); }
std::string bytesToStr(const std::vector<uint8_t>& b) { return std::string(b.begin(), b.end()); }

} // namespace

int main() {
    const int N = 1000;
    Client client("127.0.0.1", 8080);

    std::mt19937 rng(42);
    std::uniform_int_distribution<int> byteDist(0, 255);
    std::uniform_real_distribution<float> vecDist(-1.0f, 1.0f);

    std::unordered_map<std::string, std::string> reference;
    int failures = 0;

    std::cout << "[1/4] 写入 " << N << " 条随机 KV..." << std::endl;
    for (int i = 0; i < N; i++) {
        std::string key = "key-" + std::to_string(i);
        std::string value = "value-" + std::to_string(i) + "-" + std::to_string(byteDist(rng));
        if (!client.put(strToBytes(key), strToBytes(value))) {
            std::cerr << "  put 失败: " << key << std::endl;
            failures++;
            continue;
        }
        reference[key] = value;
    }

    std::cout << "[2/4] 校验全部读回..." << std::endl;
    for (auto& [key, expected] : reference) {
        std::vector<uint8_t> got;
        if (!client.get(strToBytes(key), got)) {
            std::cerr << "  get 未命中: " << key << std::endl;
            failures++;
            continue;
        }
        if (bytesToStr(got) != expected) {
            std::cerr << "  值不一致: " << key << " expected=" << expected << " got=" << bytesToStr(got) << std::endl;
            failures++;
        }
    }

    std::cout << "[3/4] 校验 delete 语义..." << std::endl;
    int deleteCount = 0;
    for (auto it = reference.begin(); it != reference.end() && deleteCount < 100; ++it, ++deleteCount) {
        if (!client.remove(strToBytes(it->first))) {
            std::cerr << "  delete 失败: " << it->first << std::endl;
            failures++;
            continue;
        }
        std::vector<uint8_t> got;
        if (client.get(strToBytes(it->first), got)) {
            std::cerr << "  delete 后仍能读到: " << it->first << std::endl;
            failures++;
        }
    }

    std::cout << "[4/4] 校验向量写入与近似检索..." << std::endl;
    const int dim = 8;
    std::vector<std::vector<float>> vectors;
    for (int i = 0; i < 50; i++) {
        std::vector<float> v(dim);
        for (float& f : v) f = vecDist(rng);
        std::string key = "vec-" + std::to_string(i);
        std::string value = "vecvalue-" + std::to_string(i);
        if (!client.putVector(strToBytes(key), strToBytes(value), v)) {
            std::cerr << "  putVector 失败: " << key << std::endl;
            failures++;
        }
        vectors.push_back(v);
    }
    auto hits = client.searchVector(vectors[0], 5);
    if (hits.empty()) {
        std::cerr << "  searchVector 未返回任何结果" << std::endl;
        failures++;
    } else {
        bool foundSelf = false;
        for (auto& h : hits) if (bytesToStr(h) == "vecvalue-0") foundSelf = true;
        if (!foundSelf) {
            std::cerr << "  searchVector 未能在 top-5 中找回自身向量" << std::endl;
            failures++;
        }
    }

    std::cout << "==========================================" << std::endl;
    if (failures == 0) {
        std::cout << "✅ 全部校验通过（" << N << " 条 KV + 100 条 delete + 50 条向量）" << std::endl;
        return 0;
    } else {
        std::cout << "❌ 发现 " << failures << " 处不一致" << std::endl;
        return 1;
    }
}
