# 故障复盘：Compaction 导致磁盘 14× 膨胀

> 一次基准测试压出的真实 bug。从"磁盘爆到 7GB"的症状，顺着 LSM 读写链路逐层下钻，最终定位到一个 `if` 条件判反 + 线程池静默吞异常。记录排查方法论与根因链，供面试复述。
>
> 时间：2026-06-05 · 模块：`carina-engine`（LSM / SSTable / Compaction）

---

## 1. 症状（Symptom）

跑 `CompactionBenchmark`（100 万条纯 KV，512B value，32 线程并发写）时观测到：

| 现象 | 数据 | 是否正常 |
|------|------|---------|
| 写入完成 | 100 万条，失败 0 | ✅ |
| L0 文件数 | 全程钉在 **9**，写完后 90s 抽干期**完全不降** | ❌ |
| L1 超级块 | 5 → 13，每 10s **稳定 +1** | ❌ |
| 每个 L1 块大小 | 几乎全是 **439.8 MB**（彼此完全相同）| ❌ |
| **总磁盘占用** | **7045 MB** —— 原始数据仅 ~512MB（1M × 512B）| ❌ **~14× 膨胀** |

直觉判断：100 万条 512B 数据落盘应在 ~600MB 量级。7GB 不是写放大能解释的，必有结构性 bug。

---

## 2. 排查方法（Investigation）

**自顶向下，每一步用证据证伪假设，不靠猜。**

### Step 1 — L0 抽不干 + L1 等差增长 → 锁定"重复归并"
后台日志一直在喊"L0 层积压 9 个文件，开始大清剿"，但：
- L0 永远 9（写完没新 flush 了还不降）
- L1 每个 tick 准时 +1，且每块体积**完全相同**

三个信号自洽指向一个假设：**compaction 把同一批 L0 归并成新 L1 后，没把源 L0 删掉** → 每 10s 拿同样的 9 个 L0 重新归并一次。

### Step 2 — 读 `backgroundCompaction` 源码：delete 明明存在
```java
Path newSST = compactor.executeCompaction(scanners, 1, newL1FileId, true);  // 先写 L1
for (SSTableScanner s : scanners) s.close();
for (File f : l0Files) f.delete();                                           // 再删 L0
```
delete 在 executeCompaction **之后**。若中间抛异常，delete 就执行不到——但 L1 文件已经写出来了。这解释了"L1 增殖 + L0 不删"。**假设升级为：executeCompaction 抛异常。**

### Step 3 — grep 日志验证：每次 compaction 都 EOF
```
$ grep -c "大清剿结束" log    →  0     （成功日志一次没打）
$ grep "Exception" log
后台合并发生异常: null
java.io.EOFException
    at SSTableScanner.next(SSTableScanner.java:60)        ← readFully(key) 撞 EOF
    at Compactor.advanceScanner / executeCompaction
    at CarinaEngine.backgroundCompaction(...:359)
```
坐实：**每一次** compaction 都在 `SSTableScanner.next` 抛 `EOFException`，被外层 try-catch 吞掉打个 log，delete 永不执行。

### Step 4 — 为什么 scanner 会 EOF？直接解析磁盘文件
`SSTableScanner` 靠 `currentReadOffset < dataEndOffset` 判断是否还有数据，`dataEndOffset` 取自文件末尾 40 字节 footer 的第一个 long（`filterOffset`）。用 python 直接解析一个 L0 文件的尾部：

```python
filterOff, indexOff, ..., magic = struct.unpack('>5q', data[-40:])
# magic 读出来 = 0xff030080ff01，而正确值应为 0x436172696E614442 ("CarinaDB")
```
**footer 根本不在 `fileSize-40`！** 全文件搜索 magic number `"CarinaDB"` → **出现 0 次**。文件**没有 footer**。

后果闭环：scanner 把数据块中间的垃圾字节当 `dataEndOffset`（恰好一个巨大正数）→ `hasNext()` 恒真 → 越读越乱 → `readFully` 撞 EOF。

### Step 5 — 为什么 L0 文件没 footer？
- 文件尾部停在 **Bloom filter 的字节**（`0000ff03 0080ff01` 这种位数组）→ `builder.finish()` 写完数据块 + Bloom filter，但**没走到 index/footer 就死了**。
- `grep -c "Flush 完成" log → 0`，`grep -c "后台刷盘致命失败" log → 0`。
- flush 任务里只 `catch(IOException)`，且没人 check `flushExecutor` 的 `Future` → flush 在 `finish()` 里抛的是 **非 IOException（RuntimeException/Error）**，被线程池**彻底吞掉**，连栈都不打。这就是"两条日志都为 0"的原因。

### Step 6 — 最初的源头：一个 `if` 判反
```java
// BlockBuilder.add 约定：true=写成功(有空间)，false=块满(没写进去)
if(dataBlockBuilder.add(...)) {   // ← 判的是"写成功"，应该是 if(!...) "块满"
    flushDataBlock();
    dataBlockBuilder.add(...);     // 又写一遍
}
```
正确意图是「块满了才刷盘换新块再写」。少了个 `!`，导致：
- **每写一条成功就刷一次块、并把该条重复写一遍** → data 区 ~2× 膨胀（实测 L0 118MB vs 应有 ~52MB），且 `memIndex` 每条记录一个条目（暴涨）。
- 膨胀的 memIndex / direct memory 让 `finish()` 在写 index 阶段抛出那个被吞掉的异常 → 文件没 footer。

---

## 3. 完整根因链（Root-Cause Chain）

```
① SSTableBuilder.add 条件判反 (缺 !)
        │  每条记录重复写 + 每条一个 index 条目
        ▼
② 数据 2× 膨胀、memIndex 暴涨 → flushMemTableToDisk 在 finish() 抛 RuntimeException
        │
        ▼
③ switchMemTable 只 catch(IOException) + 不查 Future → 异常被线程池静默吞掉
        │  L0 文件写到 Bloom filter 就停，没有 footer
        ▼
④ SSTableScanner 盲读 fileSize-40 当 footer → dataEndOffset 是垃圾 → next() 撞 EOFException
        │
        ▼
⑤ EOF 抛出 backgroundCompaction → f.delete() 永不执行
        │  L0 不删，每 10s 重复归并出残缺 L1
        ▼
⑥ 磁盘 14× 膨胀 (7GB)，L0 永久积压
```

**一句话总结**：一个少写的 `!` 引发数据膨胀，膨胀触发的异常又被过窄的 `catch` 和未检查的 `Future` 联手藏起来，最终在毫不相关的 compaction 链路上以 EOF 的形式爆发。

---

## 4. 修复（Fix）

| # | 位置 | 修复 | 性质 |
|---|------|------|------|
| 1 | `SSTableBuilder.java:68` | `if(...)` → `if(!...)`：块满才刷盘换块 | **根因** |
| 2 | `CarinaEngine.java:247` | `catch(IOException)` → `catch(Exception)`：让 flush 真异常可见 | **止血/可观测性** |
| 3 | `SSTableScanner` 构造 | （建议）读 footer 先校验 magic number，损坏文件 fail-fast | 防御加固 |
| 4 | `backgroundCompaction` | （建议）归并失败回滚残缺 L1 / 不留半成品文件 | 防御加固 |

---

## 5. 教训（Lessons）—— 面试可直接复述

1. **"判反的布尔"是最贵的 bug**：单测可能恰好没覆盖（小数据量下块从不写满，永远走 false 分支，行为看似正常）。§5 端到端基准曾"100% 命中"，正是因为数据量没填满 MemTable、几乎没触发多块 flush，把这个 bug 完美掩盖。**只有上量级（百万级压测）才暴露。**
2. **异常被吞 = 故障被推迟到最坏的地方爆发**：`catch(IOException)` 太窄 + 不检查 `ExecutorService.submit` 返回的 `Future`，让真正的失败无声无息，最终在 compaction 处以面目全非的 EOF 出现。**线程池里的任务异常必须主动暴露**（catch 宽一点 / 检查 Future / 设 `UncaughtExceptionHandler`）。
3. **二进制格式要自带校验**：footer 不校验 magic number，就把"文件损坏"延迟成了"读到垃圾偏移"。**任何持久化格式都该有 magic + 长度/校验**，让损坏在入口处 fail-fast，而不是在下游引发诡异行为。
4. **排查方法论**：自顶向下、每步用证据证伪（日志计数、栈帧、直接 hexdump/解析磁盘文件），不在源码里空想。"L1 等差增长 + 每块同样大小"这种**模式**本身就是线索。

### 可能的面试追问
- **Q: 你怎么发现是重复归并而不是写放大？** A: 写放大不会让每个 L1 块大小完全相同、也不会让 L0 在没有新写入时纹丝不动；"等差增长 + 等大"只能用"同一批输入被反复处理"解释。
- **Q: 为什么单元测试没抓到？** A: bug 只在数据块写满、触发跨块 flush 时显现；小数据量永远走不到那个分支。教训是基准/测试要覆盖"填满并跨越块边界"的量级。
- **Q: 为什么异常没报出来？** A: `flushExecutor.submit(Runnable)` 的异常存进 Future 不会自动抛；任务内又只 catch 了 IOException，真正的 RuntimeException 两头漏网。
