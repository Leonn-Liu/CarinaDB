#include "carina/engine/carina_engine.hpp"
#include "carina/common/carina_kv_service.hpp"
#include "carina/common/byte_io.hpp"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <csignal>

#include <iostream>
#include <memory>
#include <thread>
#include <vector>
#include <atomic>
#include <cstring>

// 对应原 carina-server 模块（CarinaKVServiceImpl + CarinaServerApplication）。
// Orion RPC + ZooKeeper 是 Java 专属框架，C++ 侧无法复用，改为一个自定义的
// 长度前缀二进制协议（纯 TCP，无第三方依赖）。业务判断（key/value 是否为空等）
// 与原 CarinaKVServiceImpl 保持一致；put/get/delete/searchVector 直接调用
// CarinaEngine 当前真实的方法（query/delete/searchVector 均已有 HNSW 实现），
// 而不是仓库里那份早已和引擎脱节的旧 stub。
//
// 线路协议（大端序）：
// 请求: int32 totalLen | byte opcode | payload
//   0 PUT           : keyLen,key, valLen,val
//   1 GET           : keyLen,key
//   2 DELETE        : keyLen,key
//   3 PUT_VECTOR    : keyLen,key, valLen,val, vecLen, vecLen 个 float
//   4 SEARCH_VECTOR : vecLen, vecLen 个 float, topK
// 响应: int32 totalLen | byte status(0/1) | payload
//   PUT/DELETE/PUT_VECTOR : 无 payload
//   GET                   : status=1 时 valLen+val
//   SEARCH_VECTOR         : status=1 时 count + count*(valLen+val)

namespace {

constexpr uint8_t OP_PUT = 0;
constexpr uint8_t OP_GET = 1;
constexpr uint8_t OP_DELETE = 2;
constexpr uint8_t OP_PUT_VECTOR = 3;
constexpr uint8_t OP_SEARCH_VECTOR = 4;

class CarinaKVServiceImpl : public carina::common::CarinaKVService {
public:
    explicit CarinaKVServiceImpl(std::shared_ptr<carina::engine::CarinaEngine> engine) : engine_(std::move(engine)) {}

    bool put(const std::vector<uint8_t>& key, const std::vector<uint8_t>& value) override {
        try {
            if (key.empty() || value.empty()) return false;
            engine_->put(key, value, std::nullopt);
            return true;
        } catch (const std::exception& e) {
            std::cerr << "网络层写入物理引擎失败" << e.what() << std::endl;
            return false;
        }
    }

    std::optional<std::vector<uint8_t>> get(const std::vector<uint8_t>& key) override {
        try {
            if (key.empty()) return std::nullopt;
            auto result = engine_->query(key);
            if (!result.has_value()) return std::nullopt;
            return result->value;
        } catch (const std::exception& e) {
            std::cerr << "网络层读取物理引擎失败" << e.what() << std::endl;
            return std::nullopt;
        }
    }

    bool remove(const std::vector<uint8_t>& key) override {
        try {
            if (key.empty()) return false;
            engine_->remove(key);
            return true;
        } catch (const std::exception& e) {
            return false;
        }
    }

    bool putVector(const std::vector<uint8_t>& key, const std::vector<uint8_t>& value,
                   const std::vector<float>& vector) override {
        try {
            if (key.empty() || value.empty() || vector.empty()) return false;
            engine_->put(key, value, vector);
            return true;
        } catch (const std::exception& e) {
            std::cerr << "网络层写入向量数据失败" << e.what() << std::endl;
            return false;
        }
    }

    std::vector<std::vector<uint8_t>> searchVector(const std::vector<float>& vector, int topK) override {
        try {
            auto hits = engine_->searchVector(vector, topK);
            std::vector<std::vector<uint8_t>> out;
            out.reserve(hits.size());
            for (auto& hit : hits) out.push_back(hit.value);
            return out;
        } catch (const std::exception& e) {
            std::cerr << "向量检索失败: " << e.what() << std::endl;
            return {};
        }
    }

private:
    std::shared_ptr<carina::engine::CarinaEngine> engine_;
};

bool readFrame(int fd, std::vector<uint8_t>& out) {
    uint8_t lenBuf[4];
    size_t got = 0;
    while (got < 4) {
        ssize_t n = ::recv(fd, lenBuf + got, 4 - got, 0);
        if (n <= 0) return false;
        got += static_cast<size_t>(n);
    }
    carina::common::ByteReader lin(lenBuf, 4);
    int32_t len = lin.getInt32BE();
    out.resize(len);
    got = 0;
    while (got < static_cast<size_t>(len)) {
        ssize_t n = ::recv(fd, out.data() + got, len - got, 0);
        if (n <= 0) return false;
        got += static_cast<size_t>(n);
    }
    return true;
}

void writeFrame(int fd, const std::vector<uint8_t>& payload) {
    std::vector<uint8_t> lenBuf;
    carina::common::putInt32BE(lenBuf, static_cast<int32_t>(payload.size()));
    ::send(fd, lenBuf.data(), lenBuf.size(), 0);
    if (!payload.empty()) ::send(fd, payload.data(), payload.size(), 0);
}

void handleConnection(int clientFd, std::shared_ptr<CarinaKVServiceImpl> service) {
    std::vector<uint8_t> frame;
    while (readFrame(clientFd, frame)) {
        carina::common::ByteReader in(frame.data(), frame.size());
        uint8_t opcode = in.getByte();
        std::vector<uint8_t> response;

        if (opcode == OP_PUT) {
            int32_t keyLen = in.getInt32BE();
            auto key = in.getBytes(keyLen);
            int32_t valLen = in.getInt32BE();
            auto value = in.getBytes(valLen);
            bool ok = service->put(key, value);
            response.push_back(ok ? 1 : 0);
        } else if (opcode == OP_GET) {
            int32_t keyLen = in.getInt32BE();
            auto key = in.getBytes(keyLen);
            auto value = service->get(key);
            if (value.has_value()) {
                response.push_back(1);
                carina::common::putInt32BE(response, static_cast<int32_t>(value->size()));
                carina::common::putBytes(response, *value);
            } else {
                response.push_back(0);
            }
        } else if (opcode == OP_DELETE) {
            int32_t keyLen = in.getInt32BE();
            auto key = in.getBytes(keyLen);
            bool ok = service->remove(key);
            response.push_back(ok ? 1 : 0);
        } else if (opcode == OP_PUT_VECTOR) {
            int32_t keyLen = in.getInt32BE();
            auto key = in.getBytes(keyLen);
            int32_t valLen = in.getInt32BE();
            auto value = in.getBytes(valLen);
            int32_t vecLen = in.getInt32BE();
            std::vector<float> vector(vecLen);
            for (int i = 0; i < vecLen; i++) {
                int32_t bits = in.getInt32BE();
                std::memcpy(&vector[i], &bits, 4);
            }
            bool ok = service->putVector(key, value, vector);
            response.push_back(ok ? 1 : 0);
        } else if (opcode == OP_SEARCH_VECTOR) {
            int32_t vecLen = in.getInt32BE();
            std::vector<float> vector(vecLen);
            for (int i = 0; i < vecLen; i++) {
                int32_t bits = in.getInt32BE();
                std::memcpy(&vector[i], &bits, 4);
            }
            int32_t topK = in.getInt32BE();
            auto hits = service->searchVector(vector, topK);
            response.push_back(1);
            carina::common::putInt32BE(response, static_cast<int32_t>(hits.size()));
            for (auto& v : hits) {
                carina::common::putInt32BE(response, static_cast<int32_t>(v.size()));
                carina::common::putBytes(response, v);
            }
        } else {
            response.push_back(0);
        }

        writeFrame(clientFd, response);
    }
    ::close(clientFd);
}

} // namespace

int main() {
    const int PORT = 8080;
    const std::string DB_DIR = "./carina-data";

    std::cout << " 🚀 正在启动 CarinaDB Server..." << std::endl;

    ::signal(SIGPIPE, SIG_IGN);

    auto engine = std::make_shared<carina::engine::CarinaEngine>(DB_DIR);
    auto service = std::make_shared<CarinaKVServiceImpl>(engine);

    std::cout << "✅ 底层引擎装载完毕，正在启动 TCP 网络层..." << std::endl;

    int serverFd = ::socket(AF_INET, SOCK_STREAM, 0);
    int opt = 1;
    ::setsockopt(serverFd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    addr.sin_port = htons(PORT);

    if (::bind(serverFd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::cerr << "❌ CarinaDB 启动失败：端口绑定失败" << std::endl;
        return 1;
    }
    ::listen(serverFd, 128);

    std::cout << "✅ CarinaDB 网络飞升成功！监听 127.0.0.1:" << PORT << std::endl;
    std::cout << "==========================================" << std::endl;

    while (true) {
        int clientFd = ::accept(serverFd, nullptr, nullptr);
        if (clientFd < 0) continue;
        std::thread(handleConnection, clientFd, service).detach();
    }
}
