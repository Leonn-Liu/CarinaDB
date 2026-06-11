package com.jiashi.db.engine;

import com.jiashi.db.common.model.LogRecord;
import com.jiashi.db.common.model.LogRecordType;
import com.jiashi.db.engine.blob.BlobPointer;
import com.jiashi.db.engine.blob.BlobReader;
import com.jiashi.db.engine.blob.BlobWriter;
import com.jiashi.db.engine.compaction.Compactor;
import com.jiashi.db.engine.index.hnsw.HnswIndex;
import com.jiashi.db.engine.memtable.MemTable;
import com.jiashi.db.engine.sstable.SSTableBuilder;
import com.jiashi.db.engine.sstable.SSTableReader;
import com.jiashi.db.engine.sstable.SSTableScanner;
import com.jiashi.db.engine.wal.WAL;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 存储引擎：负责 MemTable 调度、WiscKey 键值分离、后台刷盘与读写管线
 */
public class CarinaEngine {

    private final String dbDirectory;

    private final AtomicLong sstFileIdGenerator;
    private final AtomicLong blobFileIdGenerator;

    // ======== 内存流转区 ========
    private volatile MemTable activeMemTable;
    private final List<MemTable> immutableMemTables = new CopyOnWriteArrayList<>();

    // ======== 全局极值地图 ========
    private final List<SSTableReader> ssTables = new CopyOnWriteArrayList<>();

    // ======== WiscKey 向量分离区 ========
    private final BlobWriter blobWriter;
    private final ConcurrentHashMap<Long, BlobReader> blobReaders = new ConcurrentHashMap<>();

    // ======== 向量索引区 ========
    private final HnswIndex hnswIndex;
    private final AtomicInteger vectorIdGenerator = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, byte[]> vectorIdToKey = new ConcurrentHashMap<>();

    // ======== 后台调度器 ========
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();
    private final Compactor compactor;
    private final ScheduledExecutorService compactionDaemon;

    // 宏观配置
    private static final int MEMTABLE_CAPACITY = 64 * 1024 * 1024; // 64MB
    private static final int L0_COMPACTION_TRIGGER = 4;

    // 结果载体：二次寻址后返回给用户的数据对
    public static class QueryResult {
        public final byte[] value;
        public final float[] vector;
        public QueryResult(byte[] value, float[] vector) {
            this.value = value;
            this.vector = vector;
        }
    }

    public CarinaEngine(String dbDirectory) throws IOException {
        this.dbDirectory = dbDirectory;
        Path dirPath = Paths.get(dbDirectory);
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }

        long maxSstId = loadMaxSSTableId();
        long maxBlobId = loadMaxBlobFileId();
        this.sstFileIdGenerator = new AtomicLong(maxSstId);
        this.blobFileIdGenerator = new AtomicLong(maxBlobId == 0 ? 1 : maxBlobId);

        Path blobPath = Paths.get(dbDirectory, String.format("vlog-%04d.data", blobFileIdGenerator.get()));
        this.blobWriter = new BlobWriter(blobPath, blobFileIdGenerator.get());

        // 极速扫盘，建立极值地图
        loadSSTables();

        loadAllBlobReaders();

        // 初始化第一个活跃内存表
        this.activeMemTable = createNewMemTable();

        recoverFromOldWals();

        this.compactor = new Compactor(dbDirectory);
        this.compactionDaemon = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Carina-Compaction-Daemon");
            t.setDaemon(true);
            return t;
        });
        this.compactionDaemon.scheduleWithFixedDelay(this::backgroundCompaction, 10, 10, TimeUnit.SECONDS);

        System.out.println("CarinaDB 启动成功，已挂载 " + ssTables.size() + " 个 SSTable。");

        this.hnswIndex = new HnswIndex(16, 200);
    }

    /**
     * 核心写链路 —— 带向量的高维数据写入
     */
    public void put(byte[] key, byte[] value, float[] vector) throws IOException {
        byte[] pointerBytes = null;

        byte type = LogRecordType.PUT_KV;

        if(vector != null && vector.length >0){
            BlobPointer pointer = blobWriter.append(vector);
            if(pointer != null){
                pointerBytes = pointer.toBytes();
                type = LogRecordType.PUT_VECTOR;
            }
            int vectorId = vectorIdGenerator.getAndIncrement();
            vectorIdToKey.put(vectorId, key);
            hnswIndex.insert(vectorId, vector);
        }

        // 先尝试写入当前 Active MemTable
        boolean success = activeMemTable.put(type, key, value, pointerBytes);

        // 如果物理水位已满，必须切换
        if (!success) {
            synchronized (this) {
                success = activeMemTable.put(type, key, value, pointerBytes);
                if (!success) {
                    switchMemTable();
                    // 切换完成后，必须是用 pointerBytes 重试，绝不能再塞 vector！
                    activeMemTable.put(type, key, value, pointerBytes);
                }
            }
        }
    }

    /**
     * 删除链路
     */
    public void delete(byte[] key) throws IOException {
        byte type = LogRecordType.DELETE;

        boolean success = activeMemTable.put(type, key, null, null);
        if (!success) {
            synchronized (this) {
                success = activeMemTable.put(type, key, null, null);
                if (!success) {
                    switchMemTable();
                    activeMemTable.put(type, key, null, null);
                }
            }
        }
    }

    public QueryResult query(byte[] key) throws IOException {
        LogRecord record = null;
        record = activeMemTable.getRecord(key);
        if(record == null){
            for (int i = immutableMemTables.size() - 1; i >= 0; i--) {
                record = immutableMemTables.get(i).getRecord(key);
                if (record != null) break;
            }
        }

        if(record == null){
            for (SSTableReader reader : ssTables) {
                if(compareBytes(key, reader.getMinKey()) < 0 || compareBytes(key, reader.getMaxKey()) > 0){
                    continue;
                }
                record = reader.searchBinaryFullRecord(key);
                if (record != null) break;
            }
        }

        if(record == null || record.getType() == LogRecordType.DELETE){
            return null;
        }

        byte[] value = record.getValue();
        byte[] pointerBytes = record.getBlobPointer();
        float[] vector = null;
        if(pointerBytes != null && pointerBytes.length == 20){
            BlobPointer pointer = BlobPointer.fromBytes(pointerBytes);
            BlobReader blobReader = blobReaders.get(pointer.fileId);
            if(blobReader != null){
                vector = blobReader.readVector(pointer.offset, pointer.vectorDim);
            }else{
                System.err.println("[数据一致性警告] 找不到 fileId=" + pointer.fileId + " 的 vLog 文件！");
            }
        }
        return new QueryResult(value, vector);
    }

    /**
     * 向量近似最近邻查询
     * @param queryVector 查询向量
     * @param k           返回的最近邻数量
     * @return 距离最近的 k 个节点的完整数据
     */
    public List<QueryResult> searchVector(float[] queryVector, int k) throws IOException {
        // 超额检索：HNSW 图不支持删除，已删 key 靠下面 query() 撞墓碑后过滤。
        // 若只取 k 个，过滤后会凑不满，这里多要一倍、过滤后再截回 k 条。
        List<HnswIndex.NodeDistance> hits = hnswIndex.search(queryVector, k * 2);

        List<QueryResult> results = new ArrayList<>(k);
        for (HnswIndex.NodeDistance hit : hits) {
            byte[] key = vectorIdToKey.get(hit.nodeId);
            if (key == null) continue;

            QueryResult result = query(key);
            if (result != null) {
                results.add(result);
                if (results.size() >= k) break;
            }
        }
        return results;
    }

    /**
     * 扫盘与加载极值地图
     * @throws IOException
     */
    private void loadSSTables() throws IOException {
        try (Stream<Path> paths = Files.list(Paths.get(dbDirectory))) {
            List<Path> sstPaths = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sst"))
                    // 物理事实法则：文件号越大代表数据越新，降序排列
                    .sorted(Comparator.comparing(Path::getFileName, Comparator.reverseOrder()))
                    .collect(Collectors.toList());

            for (Path sstPath : sstPaths) {
                SSTableReader reader = new SSTableReader(sstPath);
                ssTables.add(reader);
            }
        }
    }

    /**
     * 分配具有独立 WAL 的新 MemTable
     */
    private MemTable createNewMemTable() throws IOException {
        String walFileName = "wal_" + System.currentTimeMillis() + ".log";
        MemTable memTable = new MemTable(MEMTABLE_CAPACITY, dbDirectory, walFileName);

        memTable.setBlobWriter(this.blobWriter);

        return memTable;
    }

    /**
     * 核心状态翻转与触发刷盘
     */
    private synchronized void switchMemTable() throws IOException {
        // Double-check 防止并发写线程引发的重复切换
        if (!activeMemTable.isImmutable()) {
            activeMemTable.freeze(); // 强制冻结，补齐状态机
        }

        // 1. 退居二线
        MemTable tableToFlush = this.activeMemTable;
        immutableMemTables.add(tableToFlush);

        // 2. 瞬间挂载新的接客 MemTable
        this.activeMemTable = createNewMemTable();

        // 3. 异步提交物理落盘任务
        flushExecutor.submit(() -> {
            try {
                flushMemTableToDisk(tableToFlush);
            } catch (Exception e) {
                System.err.println("后台刷盘致命失败: " + e.getMessage());
            }
        });
    }

    /**
     * 真实的后台流式落盘逻辑
     */
    private void flushMemTableToDisk(MemTable memTable) throws IOException {
        long newFileId = sstFileIdGenerator.incrementAndGet();
        String fileName = String.format("L0-%06d.sst", newFileId);
        Path sstPath = Paths.get(dbDirectory, fileName);

        // 1. 召唤包工头。由于 MemTable 没暴露 size()，这里根据 64MB 经验预估约 10 万条 KV
        int estimatedInsertions = 100000;
        SSTableBuilder builder = new SSTableBuilder(sstPath, estimatedInsertions);

        // 2. 流式铺铁轨：遍历 Immutable MemTable 暴露的 LogRecord
        Iterator<LogRecord> iterator = memTable.iterator();
        while (iterator.hasNext()) {
            LogRecord record = iterator.next();
            // 将 Record 解析为底层的 key/value 喂给 Builder
            // 如果是 DELETE 墓碑，value 本身就是 byte[0]，Builder 也会忠实记录
            builder.add(record.getKey(), record.getValue(), record.getType(), record.getBlobPointer());
        }

        // 3. 终极结账：追加元数据和 40 字节 Footer
        builder.finish();

        // 4. 将新生成的死文件唤醒为极值地图节点
        SSTableReader newReader = new SSTableReader(sstPath);

        // 极其重要的物理事实：新生成的文件包含最新的数据，必须插在 List 的最前面！
        ssTables.add(0, newReader);

        // 5. 功成身退：清理内存和废弃的 WAL
        immutableMemTables.remove(memTable);
        memTable.close(); // 释放堆外内存和关闭旧日志

        System.out.println("Flush 完成：生成新段文件 " + fileName);
    }

    private int extractMaxFileId() {
        if (ssTables.isEmpty()) return 0;
        String fileName = ssTables.get(0).getFileId();
        try {
            return Integer.parseInt(fileName.replace(".sst", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void recoverFromOldWals() throws IOException {
        System.out.println("🚀 开始扫描并回放历史 WAL 日志...");
        try(Stream<Path> paths = Files.list(Paths.get(dbDirectory))){
            List<Path> walPaths = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().contains("wal_") && p.toString().endsWith(".log"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .collect(Collectors.toList());

            if (walPaths.isEmpty()) {
                System.out.println("✅ 没有发现遗留的 WAL 日志，无需恢复。");
                return;
            }

            for (Path walPath : walPaths) {
                String oldFileName = walPath.getFileName().toString();
                if (oldFileName.equals(activeMemTable.getWalFileName())) {
                    continue;
                }
                WAL wal = new WAL(dbDirectory,oldFileName);
                wal.recover(record -> {
                            activeMemTable.restoreFromWal(record);
                    }
                );
            }
        }
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int len1 = a.length;
        int len2 = b.length;
        int lim = Math.min(len1, len2);
        for (int i = 0; i < lim; i++) {
            int c1 = a[i] & 0xFF;
            int c2 = b[i] & 0xFF;
            if (c1 != c2) { return c1 - c2; }
        }
        return len1 - len2;
    }

    /**
     * 无锁的后台幽灵合并机制
     * 注意：方法签名绝对不能有 throws IOException，所有异常必须在内部 try-catch 消化！
     */
    private void backgroundCompaction() {
        try {
            File dir = new File(dbDirectory);
            File[] l0Files = dir.listFiles((d, name) -> name.startsWith("L0-") && name.endsWith(".sst"));

            if (l0Files == null || l0Files.length < L0_COMPACTION_TRIGGER) return;

            System.out.println("\n[后台雷达] L0 层积压 " + l0Files.length + " 个文件，开始大清剿...");

            List<SSTableScanner> scanners = new ArrayList<>();
            for (File f : l0Files) {
                scanners.add(new SSTableScanner(f.toPath()));
            }

            long newL1FileId = sstFileIdGenerator.incrementAndGet();
            Path newSST = compactor.executeCompaction(scanners, 1, newL1FileId, true);

            for (SSTableScanner s : scanners) s.close();
            for (File f : l0Files) f.delete();

            synchronized (ssTables) {
                ssTables.add(0, new SSTableReader(newSST));
            }

            System.out.println("[大清剿结束] 新纪元超级块诞生: " + newSST.getFileName());
        } catch (Exception e) {
            System.err.println("后台合并发生异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private long loadMaxSSTableId() {
        if (ssTables.isEmpty()) return 0;
        String fileName = ssTables.get(0).getFileId();
        try {
            // 利用正则只保留数字，支持类似 L0-000001.sst 这样的解析
            String idStr = fileName.replaceAll("[^0-9]", "");
            return idStr.isEmpty() ? 0 : Long.parseLong(idStr);
        } catch (Exception e) {
            return 0;
        }
    }

    private long loadMaxBlobFileId() {
        File dir = new File(dbDirectory);
        File[] files = dir.listFiles((d, name) -> name.startsWith("vlog-") && name.endsWith(".data"));
        long maxId = 0;
        if (files != null) {
            for (File f : files) {
                try {
                    long id = Long.parseLong(f.getName().substring(5, f.getName().lastIndexOf('.')));
                    maxId = Math.max(maxId, id);
                } catch (Exception ignored) {}
            }
        }
        return maxId;
    }

    /**
     * 加载所有历史 vLog 文件
     */
    private void loadAllBlobReaders() {
        File dir = new File(dbDirectory);
        File[] files = dir.listFiles((d, name) -> name.startsWith("vlog-") && name.endsWith(".data"));
        if (files != null) {
            for (File f : files) {
                try {
                    // 提取 vlog-0001.data 中的数字 1
                    long id = Long.parseLong(f.getName().substring(5, f.getName().lastIndexOf('.')));
                    blobReaders.put(id, new BlobReader(f.toPath(), id));
                } catch (Exception e) {
                    System.err.println("加载 vLog 文件失败: " + f.getName());
                    e.printStackTrace();
                }
            }
        }
    }

    public void close() {
        System.out.println("开始执行 CarinaDB 安全停机...");
        try {
            // 1. 强制将最后一个活跃内存表推入刷盘队列
            if (activeMemTable != null && !activeMemTable.isImmutable()) {
                activeMemTable.freeze();
                immutableMemTables.add(activeMemTable);
                flushExecutor.submit(() -> {
                    try {
                        flushMemTableToDisk(activeMemTable);
                    } catch (IOException e) {
                        System.err.println("停机前刷盘失败: " + e.getMessage());
                    }
                });
            }

            // 2. 优雅关闭线程池并阻塞等待 (工业级事实标准做法)
            flushExecutor.shutdown();
            if (!flushExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                System.err.println("警告：部分刷盘任务超时未能完成！");
            }

            // 3. 安全关闭所有物理文件句柄
            for (SSTableReader reader : ssTables) {
                reader.close();
            }
            System.out.println("CarinaDB 安全停机完毕。");

        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }
}