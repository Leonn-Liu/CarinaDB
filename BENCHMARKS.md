# CarinaDB 基准测试记录

> 所有基准测试的环境、命令、结果与分析。新增基准按日期追加，便于追踪性能演进与回归。

## 测试环境

| 项 | 值 |
|----|----|
| CPU | Apple M 系列（ARM64 / NEON 128-bit）|
| JDK | OpenJDK 25 |
| 基准框架 | JMH 1.37 |
| OS | macOS (Darwin 25.5) |

> SIMD 加速比与硬件向量宽度强相关：NEON = 128bit（4 个 float lane），AVX-512 = 512bit（16 lane）。**换机器必须重测并在此记录硬件。**

---

## 1. VectorMath：SIMD vs 标量（2026-06-03）

**被测**：`com.jiashi.db.engine.math.VectorMath` 的点积 / L2 距离²，SIMD（Java Vector API）vs 纯标量。

**方法**：JMH，AverageTime 模式，3 warmup + 5 measurement × 1 fork；结果交给 `Blackhole` 防 JIT 死代码消除；fork JVM 追加 `--add-modules jdk.incubator.vector`。

**复跑**：
```bash
mvn -pl carina-engine -am test-compile
mvn -pl carina-engine dependency:build-classpath -Dmdep.outputFile=/tmp/carina_cp.txt -Dmdep.includeScope=test -q
java --add-modules jdk.incubator.vector \
  -cp "carina-engine/target/test-classes:carina-engine/target/classes:$(cat /tmp/carina_cp.txt)" \
  com.jiashi.db.engine.math.VectorMathBenchmark
```

**结果**（ns/op，越低越好）：

| 运算 | 维度 | 标量 | SIMD | 加速比 |
|------|------|------|------|--------|
| 点积 | 128 | 29.52 | 7.05 | **4.19×** |
| 点积 | 768 | 322.81 | 67.52 | **4.78×** |
| 点积 | 1536 | 720.43 | 164.31 | **4.38×** |
| L2² | 128 | 33.45 | 8.39 | **3.99×** |
| L2² | 768 | 350.95 | 72.22 | **4.86×** |
| L2² | 1536 | 778.41 | 179.88 | **4.33×** |

**分析**：稳定 4–5× 加速。M 系列 NEON 为 128-bit = 4 个 float lane，理论上限 ~4×，实测略超因省去了循环计数等开销。标量版未被 C2 自动向量化（否则差距会收窄），印证显式 Vector API 的价值在于"可预测的向量化"。

**边界 / 待严谨**：JDK 25 的 Compiler Blackhole 为实验性支持；要坐实"真的向量化了"可加 `-prof perfasm` 看汇编。换 AVX-512 服务器上限更高（~8–16×）。

---

## 2. WAL 组提交吞吐：并发摊销 fsync（2026-06-03）

**被测**：`WAL.append`（Leader-Follower 组提交）。固定每档总写入 5000 条（value 100B），扫描并发线程数，观察 group commit 如何把多次 fsync 合并成一次。

**方法**：自研多线程压测器 `WalGroupCommitBenchmark`（`CountDownLatch` 统一发令，`System.nanoTime` 计时）。说明：吞吐型 + 真实磁盘 I/O 的宏基准用自研 harness 而非 JMH——这与 RocksDB `db_bench` 取向一致；JMH 更适合纯 CPU 微基准（见 §1）。

**复跑**：
```bash
mvn -pl carina-engine test-compile
java -cp "carina-engine/target/test-classes:carina-engine/target/classes:carina-common/target/classes" \
  com.jiashi.db.engine.wal.WalGroupCommitBenchmark
```

**结果**（macOS APFS SSD，`force(false)` 实测走 F_FULLFSYNC ~3.8ms/次）：

| 并发线程 | 总耗时 (ms) | 吞吐 (ops/s) |
|---------|------------|-------------|
| 1 | 18945 | 264 |
| 8 | 6816 | 734 |
| 32 | 1240 | 4,032 |
| 64 | 574 | **8,711** |

**分析**：单线程时每次 append 独占一次 fsync，吞吐被磁盘同步延迟死死封顶（264 ops/s ≈ 3.8ms/次）。并发升高后，一个 Leader 单次 drain 出越来越多请求、合并成一次 fsync，**1→64 线程聚合吞吐提升约 33×**。这正是 group commit 的意义：把 N 次 fsync 摊销成 1 次。

**边界**：绝对值是 macOS F_FULLFSYNC 强一致语义下的保守数；Linux ext4/XFS 的 fsync 通常快得多，单线程基线会更高、相对加速比会更小。换平台需重测。

---

## 3. MemTable 单线程插入 + GC 开销：堆外跳表 vs JDK（2026-06-04）

**被测**：`OffHeapSkipList`（堆外 Arena + 无锁 CAS）vs `java.util.concurrent.ConcurrentSkipListMap`，单线程插入 100 万条（value 100B），对比吞吐与 **JVM 堆内留存 / GC**。

**方法**：自研 `SkipListVsJdkBenchmark`。插入前后各强制两轮 `System.gc()` 读"结构净留存"（指示性，非精确）；GC 次数/耗时取自 `GarbageCollectorMXBean`。JDK 25 需 `--add-opens java.base/java.nio=ALL-UNNAMED`（Arena 反射 `Buffer.address`）。

**复跑**：
```bash
mvn -pl carina-engine -am test-compile
java -Xmx1g -XX:MaxDirectMemorySize=2g --add-opens java.base/java.nio=ALL-UNNAMED \
  -cp "carina-engine/target/test-classes:carina-engine/target/classes:carina-common/target/classes" \
  com.jiashi.db.engine.memtable.SkipListVsJdkBenchmark
```

**结果**：

| 结构 | 耗时 | 吞吐 (ops/s) | GC 次数 | GC 耗时 | 堆内留存 | 堆外占用 |
|------|------|-------------|--------|--------|---------|---------|
| OffHeapSkipList | 363 ms | 2,754,821 | 7 | 2 ms | **~0 MB** | 134 MB |
| ConcurrentSkipListMap | 160 ms | 6,250,000 | 0 | 0 ms | **34 MB** | — |

**分析（诚实结论）**：**原始插入吞吐 JDK 快约 2.3×**——这是真实代价，不是 bug。OffHeapSkipList 慢在每次寻路比较都要 `getKey` 从堆外反序列化出一个新 `byte[]`、并按字节逐个 `ByteBuffer.get` 拷贝（见"待优化"）；ConcurrentSkipListMap 直接持有已存在的堆上 `byte[]` 引用、堆内数组比较，几乎零额外开销。

价值不在速度，在**内存形态**：堆外结构堆内留存 ~0（134MB 全在 DirectByteBuffer），ConcurrentSkipListMap 把 34MB 结构永久压在 JVM 堆上。放大到"64MB MemTable × N 个 Immutable 等待 Flush"的真实场景，后者就是持续的 GC 扫描压力与堆碎片；前者是平坦、可预测、且能作为**一整段连续字节直接刷成 SSTable** 的布局。对一个写完即冻结刷盘的 LSM MemTable，堆外是正确取舍。

**反直觉点**：OffHeap 反而触发了 7 次 minor GC、JDK 0 次——因为 OffHeap 寻路时 `getKey` 制造了大量**临时** `byte[]` 垃圾。这与"堆外=无 GC"的直觉相悖，恰好暴露下面这条优化项的价值。

---

## 4. MemTable 并发写入 TPS：无锁 CAS 扩展性（2026-06-04）

**被测**：同上两结构，扫描线程数 1/2/4/8，每档总写入 100 万条；每线程写一段**不相交** key。重点看无锁 CAS 随核数的扩展性，并借**完整性校验**实证 ARM64 对齐修复。

**方法**：自研 `MemTableConcurrentBenchmark`，`CountDownLatch` 统一发令；墙钟取"开闸→全部 join"。跑完用 Level 0 迭代器全量计数，断言 == 100 万——若 CAS 因非对齐在 ARM 上撕裂/半成功必丢节点、计数对不上。

> **背景**：此前 `Arena` 游标从 1 起步 + `HEADER_SIZE=17`，使节点指针落在 `≡2 (mod 4)` 的非对齐地址。x86 容忍非对齐 CAS，**ARM64（本机 M 系列）的 `LDXR/STXR`/LSE `CAS` 要求 4 字节自然对齐，非对齐 SIGBUS**。已修：游标起点 `ALIGN_BYTES`(4) + Header 重排为 20B（`[Type 1B+pad 3B][KeyLen][ValLen][PtrLen][Height]`），指针区 `base+20+level*4` 全对齐。本基准即该修复的回归实证。

**复跑**：
```bash
java -Xmx1g -XX:MaxDirectMemorySize=2g --add-opens java.base/java.nio=ALL-UNNAMED \
  -cp "carina-engine/target/test-classes:carina-engine/target/classes:carina-common/target/classes" \
  com.jiashi.db.engine.memtable.MemTableConcurrentBenchmark
```

**结果**（两轮取代表值，吞吐 ops/s）：

| 结构 | 1 线程 | 2 线程 | 4 线程 | 8 线程 | 完整性 |
|------|-------|-------|-------|-------|--------|
| OffHeapSkipList | 3.1 M | 4.6 M | 5.1–6.2 M | 7.0–8.5 M | **全档 OK** |
| ConcurrentSkipListMap | 7.1–7.6 M | 9.6–10.1 M | 15.6–18.5 M | 15.4–22.2 M | 全档 OK |

**分析**：
1. **对齐修复实证**：OffHeapSkipList 8 线程并发、两轮共 16 次压测，Level 0 计数全部 == 100 万，**零丢节点**——刚修的 ARM64 CAS 对齐在真实高并发写入下成立。这是本次记录的首要结论。
2. **扩展性**：无锁 CAS 从 1→8 线程约 2.3–2.7× 提升，但**绝对吞吐与扩展斜率都不及 ConcurrentSkipListMap**。差距根因仍是 §3 的逐字节堆外序列化开销——它把单条插入的 CPU 成本抬高，并发只是把这个固定成本并行摊开，掩盖不了单位成本劣势。
3. **边界**：8 线程档抖动明显（OffHeap 7.0–8.5M、JDK 15–22M），因 M 系列性能核/能效核混合调度 + GC 干扰，且本机物理核有限。绝对值仅供同机对比，换机重测。

**结论留档**：当前 `OffHeapSkipList` 的工程定位是"**用 CPU 换堆内零留存 + 可直接刷盘布局**"，而非"比 JDK 更快"。若要在保留堆外优势的同时追平吞吐，关键在下面这条优化。

---

## 5. 端到端引擎全链路：写入 / 点查 / WiscKey 向量打捞（2026-06-04）

**被测**：`CarinaEngine` 完整写读路径。写：32 线程并发 `put` 20 万条，其中 80% 携带 128 维向量（每条 512B，经 WiscKey 旁路写入 `.vlog`）。读：5 万次随机点查，含去 vlog 二次寻址打捞向量。

**方法**：自研 `CarinaEngineBenchmark`，`CountDownLatch` 发令枪统一开闸。写路径真实穿过 WAL 组提交 + vlog 追加（与 WAL 共用**统一屏障**一次 fsync）+ 堆外 MemTable 插入 + 后台 Flush/Compaction；读路径穿过 MemTable → SSTable（min/max → Bloom → 块缓存）→ vlog。JDK 25 需 `--add-opens java.base/java.nio=ALL-UNNAMED`。

**复跑**：
```bash
java -Xmx2g -XX:MaxDirectMemorySize=2g --add-opens java.base/java.nio=ALL-UNNAMED \
  -cp "carina-engine/target/test-classes:carina-engine/target/classes:carina-common/target/classes" \
  com.jiashi.db.engine.CarinaEngineBenchmark
```

**结果**（macOS APFS SSD，单次代表性运行）：

| 阶段 | 量 | 耗时 | 吞吐 (ops/s) | 备注 |
|------|----|------|-------------|------|
| 并发写入 (32T) | 200,000 | 120,081 ms | **1,666** | 80% 带 512B 向量，失败 0 |
| 随机点查 (单线程) | 50,000 | 257 ms | **194,553** | 命中 50000/50000 (100%) |
| └ 向量打捞 | — | — | — | 39,812 / 50000 ≈ **79.6%**（≈ 写入时 80% 带向量比例）|

**收工物理态**：磁盘 117.85 MB，SSTable ×1，vlog ×1。

**分析**：
1. **读写两堵墙，量级差 ~117×**：写 1.6K ops/s vs 读 195K ops/s。写被**持久化 fsync 主导**——这是 LSM 写路径的本质墙，与 §2 同源（macOS F_FULLFSYNC ~3.8ms/次）。端到端写比 §2 纯 WAL（32T=4032）更低，因为全路径还叠加了 vlog 追加、堆外 MemTable 插入（即 §3 那条逐字节序列化慢路径）、以及 80% 写携带 512B 向量。读则全程命中内存/块缓存，无 fsync，故快两个量级。
2. **WiscKey 旁路闭环验证**：向量打捞命中率 79.6% ≈ 写入时 80% 带向量概率 —— 证明 `put → BlobWriter 写 vlog → SSTable 存 20B BlobPointer → query 读出指针 → BlobReader 回 vlog 捞 float[]` 整条二次寻址链路端到端正确。
3. **点查 100% 命中**：5 万次随机 key 全部命中，证明 MemTable + SSTable(二分→Bloom→缓存) 读路径在真实数据集上零漏读。
4. **数据量未触发多级 Compaction**：MemTable 内每条仅 key+value+20B 指针（向量在 vlog），20 万条约 ~16MB，未填满单个 64MB MemTable，故最终仅 1 个 SSTable。要压出 L0→L1 多路归并需把数据量提到百万级或调小 MemTable 阈值 —— 列入待补。

**边界**：写吞吐绝对值受 macOS F_FULLFSYNC 强一致语义封顶，Linux ext4/XFS 会显著更高；单次运行，换平台/换机必重测。

---

## 6. 百万级 Compaction：L0→L1 多级归并（2026-06-05）

**被测**：`CarinaEngine` 后台 compaction。32 线程并发写 100 万条纯 KV（512B value，不带向量，专注 LSM/SSTable/Compaction 路径）；512B 大 value 让 ~10 万条即填满 64MB MemTable → 反复触发 L0 flush → L0 越过 `L0_COMPACTION_TRIGGER(4)` → 后台守护（10s 一跳）归并 L0→L1。写完后轮询数据目录，观测 L0 被抽干、L1 超级块累积。

**方法**：自研 `CompactionBenchmark`。`CountDownLatch` 发令枪统一开闸；写完后每 5s 轮询 L0/L1 文件数直到 L0 降到阈值以下；收尾做 [层级体检] 统计各层文件数与磁盘占用。JDK 25 需 `--add-opens java.base/java.nio=ALL-UNNAMED`。

**复跑**：
```bash
java -Xmx2g -XX:MaxDirectMemorySize=2g --add-opens java.base/java.nio=ALL-UNNAMED \
  -cp "carina-engine/target/test-classes:carina-engine/target/classes:carina-common/target/classes" \
  com.jiashi.db.engine.CompactionBenchmark
```

> ⚠️ **本基准首跑（2026-06-05）暴露了一条 14× 磁盘膨胀的级联 bug**：磁盘爆到 7GB、L0 永不抽干、L1 无限增殖。完整排查与根因链见 [`docs/POSTMORTEM-compaction-disk-bloat.md`](docs/POSTMORTEM-compaction-disk-bloat.md)（源头是 `SSTableBuilder.add` 一个判反的 `if` + flush 异常被线程池静默吞掉）。下表记录**修复前 vs 修复后**两组数据，留作回归基线。

**结果**（macOS APFS SSD）：

| 指标 | 修复前（bug 现场） | 修复后 |
|------|------------------|--------|
| 写入吞吐（1M 条 / 32T） | 2,037 ops/s | 1,751 ops/s |
| `[大清剿结束]`（归并成功次数） | **0** | 正常 |
| compaction EOFException | **每次必抛** | 0 |
| L0 抽干 | 钉死在 9，永不降 | **9 → 1，抽干完成** |
| 单个 L0 文件大小 | 118 MB（2× 重复） | 56.8 MB |
| L1 超级块 | 13 个、无限增殖 | 2 个 × 227 MB |
| **总磁盘占用** | **7045 MB** | **1036 MB**（↓ 6.8×）|

**修复后磁盘构成**：L0 残留 62MB（2 文件，未达归并阈值）+ L1 454MB（2 超级块）+ **WAL 520MB（10 文件）** + vlog 0。SSTable 部分（L0+L1）= 516MB ≈ **1× 原始数据（512MB）**，空间放大回归正常。

**分析**：
1. **归并链路打通**：修复后 compaction 不再抛 EOF，`f.delete()` 正常执行，L0 写完即被抽干（9→1），证明 L0→L1 后台归并端到端生效。
2. **空间放大回归 1×**：SSTable 部分 516MB ≈ 原始 512MB，2× 写重复消除。
3. **写吞吐**：1,751 ops/s，受 macOS F_FULLFSYNC 强一致 fsync 主导（与 §2/§5 同源），略低于修复前是因为修复前大量 flush 异常反而"少干了正经活"，不具可比性。

**已知遗留（非本 bug 链）**：
- **WAL 不回收**：MemTable flush 成 SSTable 后，对应 WAL 数据已持久化但旧 `wal_*.log` 未删除 → 稳定泄漏（本次 10×52MB ≈ 520MB，占总磁盘一半）。`memTable.close()` 应在 flush 成功后 delete 对应 WAL。**待修。**
- **L1 块不再二次归并**：当前架构只有 L0/L1 两层、每轮归并产出独立 L1 超级块、彼此不再合并（2 个 227MB L1 在 key range 上有重叠仍各存一份）。这是架构事实而非 bug；引入 L1→L2 分层或 size-tiered/leveled 策略可进一步去重。

**边界**：写吞吐绝对值受 macOS F_FULLFSYNC 封顶，Linux ext4/XFS 会显著更高；单次运行，换平台/换机必重测。

---

## 7. 向量检索：HNSW vs SIMD 暴力全扫（recall@10 / QPS）（2026-06-11）

**被测**：`HnswIndex`（M=16, efConstruction=200，查询 ef 暂固定 = efC）与 `CarinaEngine.searchVector` 端到端链路（HNSW 图搜索 → vectorId 反查 key → `query(key)` 二次寻址 + 墓碑过滤）。对照组 = `VectorMath` SIMD 暴力全扫的精确 topK（recall 定义为 1.0）。

**方法**：自研 harness × 2，固定种子可复现。`VectorSearchBenchmark`：端到端单点（N=2 万，128 维，K=10，1000 次查询，200 次预热）。`HnswScalingBenchmark`：纯索引规模扫描（N=2 万→20 万，300 次查询/档），支持两种数据分布——`uniform`（128 维均匀随机，ANN 最恶劣输入）与 `manifold`（本征维度 16 的高斯流形线性嵌入 128 维 + 1% 噪声，模拟真实 embedding 的低维流形结构）。

**前置正确性门禁**：`VectorPipelineCorrectnessTest` 全绿——点查 vlog 往返逐元素无损、recall@10 = 0.998（N=3000）、自查询 top-1 100%、删除墓碑过滤零幽灵、删除后超额检索补位仍满额返回（recall 0.996）。

> ⚠️ **本基准 + 正确性测试首跑共揪出 4 个 bug**（详见分析 4）：其中一个是**藏在存储引擎跳表里的多版本读取 bug**——同 key 新版本插在等值区头部，但 `OffHeapSkipList.getRecord` 在高层撞到等值 key 就短路返回，旧节点塔更高时墓碑被绕过（删除偶发失效，25% 升层概率下随机复现）。向量全链路测试把它逼了出来。

**复跑**：
```bash
mvn -pl carina-engine -am test-compile
CP="carina-engine/target/test-classes:carina-engine/target/classes:carina-common/target/classes"
# 正确性门禁
java --add-modules jdk.incubator.vector --add-opens java.base/java.nio=ALL-UNNAMED \
  -cp "$CP" com.jiashi.db.engine.VectorPipelineCorrectnessTest
# 端到端单点（N=2 万）
java --add-modules jdk.incubator.vector --add-opens java.base/java.nio=ALL-UNNAMED \
  -cp "$CP" com.jiashi.db.engine.VectorSearchBenchmark
# 规模扫描（uniform / manifold 两种分布）
java -Xmx3g --add-modules jdk.incubator.vector -cp "$CP" \
  com.jiashi.db.engine.index.hnsw.HnswScalingBenchmark uniform
java -Xmx3g --add-modules jdk.incubator.vector -cp "$CP" \
  com.jiashi.db.engine.index.hnsw.HnswScalingBenchmark manifold
```

**结果 1 —— 端到端单点（N=2 万，uniform）**：

| 指标 | 值 |
|------|-----|
| 纯 HNSW 建图 | 9.8 s（491 µs/条，单线程） |
| 引擎端到端写入 | 102 ops/s（fsync 主导，与 §2/§5 同源） |
| 暴力全扫 | 211 µs/次，**4,741 QPS** |
| 端到端 `searchVector` | 462 µs/次，**2,165 QPS**，recall@10 = 0.904 |

注：本档（2 万条）暴力反而更快——见分析 2 的交叉点；纯 HNSW 与端到端的差值在建图随机性 + JIT 噪声范围内，KV 打捞开销很小（数据全驻 MemTable）。

**结果 2 —— 规模扫描，uniform 分布（最恶劣输入）**：

| N | 建图(s) | 暴力 (µs/q) | HNSW (µs/q) | 加速比 | recall@10 |
|---|--------|------------|------------|--------|-----------|
| 20,000 | 10.1 | 205 | 385 | 0.53× | 0.907 |
| 50,000 | 32.7 | 535 | 497 | **1.08×** | 0.781 |
| 100,000 | 77.6 | 1,292 | 673 | 1.92× | 0.660 |
| 200,000 | 187.1 | 2,762 | 750 | **3.68×** | 0.540 |

**结果 3 —— 规模扫描，manifold 分布（本征 16 维，接近真实 embedding）**：

| N | 建图(s) | 暴力 (µs/q) | HNSW (µs/q) | 加速比 | recall@10 |
|---|--------|------------|------------|--------|-----------|
| 20,000 | 4.8 | 216 | 255 | 0.85× | **1.0000** |
| 50,000 | 16.8 | 601 | 453 | 1.33× | 0.9997 |
| 100,000 | 44.8 | 1,287 | 558 | 2.31× | 0.9997 |
| 200,000 | 114.3 | 2,959 | 626 | **4.73×** | **1.0000** |

**分析**：

1. **交叉点 ≈ 3.5–5 万条**：暴力全扫严格 O(N)（205→2,762 µs，13.5× 线性增长），HNSW 近似平坦（385→750 µs，仅 1.9×）。2 万条以下 SIMD 暴力扫连续内存反而更快——HNSW 的指针跳跃 + 装箱 `Integer` 邻接表常数项大；规模越大 HNSW 优势越大，且差距随 N 持续拉开。**结论：向量检索没有银弹，小库用暴力扫，大库才值得建图。**
2. **recall 取决于数据分布，而非实现**：uniform 随机高维数据距离高度集中（维度灾难），recall 随 N 崩塌（0.91→0.54）；同一份代码在低本征维度流形数据上全档 recall ≈ 1.0。诊断过程留档：先后排除了图稀疏假设（实测 Level 0 平均度数 29.1/32、零孤立节点）与建图路由假设（修复 nextObj 前后 recall 无差异），最终用流形对照实验定位为数据特性——与 ann-benchmarks 社区在 random 数据集上的结论一致。真实 embedding（文本/图像）都躺在低维流形上，结果 3 更接近生产预期。
3. **manifold 数据建图还快 39%**（114 s vs 187 s @ 20 万）：流形结构让贪心搜索更早收敛，searchLayer 访问的节点更少——建图成本同样受数据分布支配。
4. **本轮测试驱动修复的 4 个 bug 留档**：
   - `HnswIndex.insert` 注册时序 NPE：新节点先双向连边、后注册进 `nodes`，邻居溢出裁剪反查时必炸（3000 条必现）。修复：连边前注册（此时无入边，不影响搜索）。
   - `OffHeapSkipList.getRecord` 多版本读取 bug（见上方警示框）：高层等值命中改为继续降层，仅信任 Level 0 等值区首节点。**这是存储引擎的正确性 bug，影响所有 delete-后-read 路径，不限于向量。**
   - `searchVector` 删除后凑不满 K：HNSW 图不支持删除，靠 `query()` 撞墓碑过滤，过滤后结果可能 < K。修复：超额检索 2K 再截断。
   - `insert` 下层入口用了大顶堆 `peek()`（最远候选）而非最近候选：已修为与论文一致；实测本数据集上 recall 无可测差异，作为正确性修复留档（修复前 uniform 四档 recall：0.906/0.783/0.672/0.545）。
5. **查询 ef 是尚未开放的 recall/QPS 旋钮**：当前 `search` 复用 efConstruction(200) 做查询 ef。开放 efSearch 参数后，uniform 数据可用更大 ef 换 recall、manifold 数据可用更小 ef 换 QPS——列入待优化。

**边界**：单线程、单次运行、Apple M 系列；HNSW 索引纯内存，**无持久化（重启不重建）、无并发插入保护、无图内删除**（删除一致性靠查询层墓碑过滤 + 超额检索兜底）；synthetic 数据（uniform 与 manifold 是两个极端，真实 embedding 介于其间偏后者）。换机/换数据集必重测。

---

## 待优化 / 已知瓶颈

- **`NodeAccessor.getKey` 寻路热路径每次比较都新分配 `byte[]` 并逐字节 `ByteBuffer.get` 拷贝**（§3/§4 慢的根因）。可改为：寻路比较时**直接对堆外字节与目标 key 逐字节比较，不分配中间数组**（类似 RocksDB 的 `Slice` 视图）；`Arena.getBytes/putBytes` 改 `Unsafe.copyMemory` 批量拷贝。预计能吃掉与 JDK 的大部分差距。— 属核心引擎代码，待作者本人实现。
- **HNSW 查询 ef 不可调**（§7 分析 5）：`search` 复用 efConstruction 当查询 ef，应开放 `efSearch` 参数作为运行时 recall/QPS 旋钮。— 属核心索引代码，待作者本人实现。
- **HNSW 对象图常数项大**（§7 分析 1 中 2 万条以下输给暴力扫的根因）：装箱 `Integer` 邻接表 + `HashSet` visited + 每跳堆分配。可压成 primitive 数组布局（hnswlib 做法：邻接表为 `int[]`、visited 为位图/epoch 数组）。— 属核心索引代码，待作者本人实现。

## 待补基准

- [x] WAL 组提交吞吐 → 见 §2
- [x] MemTable 单线程插入 + GC 开销 → 见 §3
- [x] MemTable 并发写入 TPS（含 ARM 对齐回归实证）→ 见 §4
- [x] 端到端引擎写入 / 查询（`CarinaEngineBenchmark`）→ 见 §5
- [x] 百万级写入压出 L0→L1 多级 Compaction → 见 §6（首跑暴露并修复了 14× 磁盘膨胀 bug）
- [x] 向量检索 recall@K / QPS（暴力 KNN vs HNSW，含规模扫描与数据分布对照）→ 见 §7（首跑揪出 4 个 bug，含 1 个存储引擎多版本读取 bug）
