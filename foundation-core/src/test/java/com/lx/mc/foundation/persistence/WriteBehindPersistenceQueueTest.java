package com.lx.mc.foundation.persistence;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证异步写后队列的版本顺序、删除语义、刷盘和失败脏标记。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 13:04</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>使用内存测试适配器观察后台写入和删除结果。</li>
 *     <li>验证同一实体的新修订版本最终覆盖旧版本。</li>
 *     <li>验证失败任务经过有限重试后继续保留为脏数据。</li>
 * </ol>
 *
 * <p>主要调用方：Maven Surefire 测试执行器。</p>
 */
public final class WriteBehindPersistenceQueueTest {

    /**
     * 每个测试独立使用的单线程异步写后队列。
     */
    private WriteBehindPersistenceQueue queue;

    /**
     * 保存测试记录并模拟持久化失败的内存适配器。
     */
    private RecordingPersistence persistence;

    /**
     * 为每个测试创建关闭控制台输出的队列和内存适配器。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>创建不输出预期失败日志的测试 Logger。</li>
     *     <li>创建允许一次重试的写后队列和内存适配器。</li>
     * </ol>
     *
     * <p>主要调用方：JUnit 在每个测试方法前调用。</p>
     */
    @Before
    public void setUp() {
        // 1. 创建关闭父处理器和日志级别的独立测试 Logger
        Logger logger = Logger.getLogger(
                WriteBehindPersistenceQueueTest.class.getName()
                        + "."
                        + System.nanoTime()
        );
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);

        // 2. 创建允许一次重试的队列和用于记录结果的内存适配器
        queue = new WriteBehindPersistenceQueue(
                logger,
                1,
                "Foundation-Persistence-Test"
        );
        persistence = new RecordingPersistence();
    }

    /**
     * 在每个测试结束后终止后台工作线程。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：停止新任务并在两秒内终止测试队列。</p>
     * <p>主要调用方：JUnit 在每个测试方法后调用。</p>
     *
     * @throws Exception 等待测试工作线程退出期间被中断时抛出
     */
    @After
    public void tearDown() throws Exception {
        // 1. 测试队列存在时停止任务入口并终止后台工作线程
        if (queue != null) {
            queue.stopAccepting();
            queue.terminate(2L, TimeUnit.SECONDS);
        }
    }

    /**
     * 验证同一实体连续写入后最终只暴露最新修订版本。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>连续登记同一实体的两个递增版本。</li>
     *     <li>等待队列完成全部持久化操作。</li>
     *     <li>确认最终记录为最新版本且没有脏数据。</li>
     * </ol>
     *
     * <p>主要调用方：JUnit 测试执行器。</p>
     *
     * @throws Exception 等待异步刷盘期间被中断时抛出
     */
    @Test
    public void shouldPersistLatestRevision() throws Exception {
        // 1. 连续登记同一记录的初始版本和更新版本
        queue.enqueueWrite(
                persistence,
                new TestRecord("entity-1", 1L, "initial")
        );
        queue.enqueueWrite(
                persistence,
                new TestRecord("entity-1", 2L, "updated")
        );

        // 2. 在限定时间内等待全部写入任务完成
        assertTrue(queue.flush(2L, TimeUnit.SECONDS));

        // 3. 确认持久化结果为最新修订版本且队列没有脏数据
        TestRecord stored = persistence.get("entity-1");
        assertEquals(2L, stored.getRevision());
        assertEquals("updated", stored.getName());
        assertEquals(0, queue.getDirtyCount());
        assertEquals(0, queue.getPendingTaskCount());
    }

    /**
     * 验证更高修订号的删除任务不会被旧写入任务重新覆盖。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>写入并刷盘一个初始测试记录。</li>
     *     <li>登记更高修订号的删除任务。</li>
     *     <li>确认最终持久化介质中不存在该记录。</li>
     * </ol>
     *
     * <p>主要调用方：JUnit 测试执行器。</p>
     *
     * @throws Exception 等待异步刷盘期间被中断时抛出
     */
    @Test
    public void shouldDeleteWithNewerRevision() throws Exception {
        // 1. 写入初始记录并确认第一次刷盘成功
        queue.enqueueWrite(
                persistence,
                new TestRecord("entity-1", 1L, "initial")
        );
        assertTrue(queue.flush(2L, TimeUnit.SECONDS));
        assertTrue(persistence.contains("entity-1"));

        // 2. 使用更高修订号登记删除任务并等待完成
        queue.enqueueDelete(persistence, "entity-1", 2L);
        assertTrue(queue.flush(2L, TimeUnit.SECONDS));

        // 3. 确认持久化介质中不再存在被删除记录
        assertFalse(persistence.contains("entity-1"));
        assertEquals(0, queue.getDirtyCount());
    }

    /**
     * 验证写入持续失败时执行有限重试并保留脏数据。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>启用测试适配器的强制写入失败。</li>
     *     <li>登记写入任务并等待本轮重试结束。</li>
     *     <li>确认刷盘失败、执行两次尝试且脏数据仍被保留。</li>
     * </ol>
     *
     * <p>主要调用方：JUnit 测试执行器。</p>
     *
     * @throws Exception 等待异步刷盘期间被中断时抛出
     */
    @Test
    public void shouldKeepFailedWriteDirtyAfterRetries() throws Exception {
        // 1. 配置测试适配器拒绝全部写入操作
        persistence.setFailWrites(true);

        // 2. 登记写入任务并等待首次尝试和一次重试结束
        queue.enqueueWrite(
                persistence,
                new TestRecord("entity-1", 1L, "initial")
        );
        assertFalse(queue.flush(2L, TimeUnit.SECONDS));

        // 3. 确认任务尝试次数受限且失败实体继续保持脏状态
        assertEquals(2, persistence.getWriteAttempts());
        assertEquals(1, queue.getDirtyCount());
        assertEquals(1, queue.getFailedCommandCount());
        assertEquals(0, queue.getPendingTaskCount());
    }

    /**
     * 使用线程安全内存集合模拟阻塞式实体持久化适配器。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>保存异步工作线程写入的最新测试记录。</li>
     *     <li>按测试开关模拟持续写入失败。</li>
     * </ol>
     *
     * <p>主要调用方：WriteBehindPersistenceQueueTest。</p>
     */
    private static final class RecordingPersistence
            implements EntityPersistence<TestRecord> {

        /**
         * 保存后台线程成功持久化的最新测试记录。
         */
        private final Map<String, TestRecord> records =
                new ConcurrentHashMap<String, TestRecord>();

        /**
         * 统计适配器 write 方法的实际调用次数。
         */
        private final AtomicInteger writeAttempts = new AtomicInteger();

        /**
         * 控制 write 方法是否始终抛出测试异常。
         */
        private volatile boolean failWrites;

        /**
         * 获取测试适配器的固定实体命名空间。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：返回用于测试队列组合键的 records 名称。</p>
         * <p>主要调用方：异步写后队列。</p>
         *
         * @return 固定返回 records
         */
        @Override
        public String getNamespace() {
            // 1. 返回测试记录使用的固定持久化命名空间
            return "records";
        }

        /**
         * 加载内存适配器中的全部测试记录。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：复制线程安全 Map 中的全部当前值。</p>
         * <p>主要调用方：异步写后队列加载测试。</p>
         *
         * @return 当前全部测试记录的独立列表
         */
        @Override
        public List<TestRecord> loadAll() {
            // 1. 复制当前持久化记录并返回独立列表
            return new ArrayList<TestRecord>(records.values());
        }

        /**
         * 写入单个测试快照或按测试开关抛出 IOException。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：增加尝试次数，失败开关关闭时保存最新记录。</p>
         * <p>主要调用方：异步写后队列。</p>
         * <p>副作用：修改测试记录集合或抛出预期异常。</p>
         *
         * @param snapshot 待写入的不可变测试快照
         * @throws IOException 测试失败开关开启时抛出
         */
        @Override
        public void write(TestRecord snapshot) throws IOException {
            // 1. 统计每一次由队列发起的实际写入尝试
            writeAttempts.incrementAndGet();

            // 2. 失败开关开启时模拟底层持久化不可用
            if (failWrites) {
                throw new IOException("Expected test write failure");
            }

            // 3. 写入成功时保存指定实体的最新快照
            records.put(snapshot.getId(), snapshot);
        }

        /**
         * 删除指定标识对应的测试记录。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：从线程安全 Map 移除指定实体。</p>
         * <p>主要调用方：异步写后队列。</p>
         * <p>副作用：修改测试记录集合。</p>
         *
         * @param id 待删除测试记录唯一标识
         */
        @Override
        public void delete(String id) {
            // 1. 从测试持久化介质中删除指定记录
            records.remove(id);
        }

        /**
         * 刷新测试适配器内部缓冲区。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：内存适配器没有缓冲区，因此无需额外处理。</p>
         * <p>主要调用方：数据基础设施关闭测试。</p>
         */
        @Override
        public void flush() {
            // 1. 测试内存适配器没有需要刷新的缓冲内容
        }

        /**
         * 关闭测试持久化适配器。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：内存适配器不持有外部资源，因此无需额外处理。</p>
         * <p>主要调用方：数据基础设施关闭测试。</p>
         */
        @Override
        public void close() {
            // 1. 测试内存适配器没有需要释放的外部资源
        }

        /**
         * 设置后续写入是否应模拟失败。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：更新工作线程可见的失败控制开关。</p>
         * <p>主要调用方：失败重试测试方法。</p>
         *
         * @param failWrites true 表示后续 write 调用全部失败
         */
        private void setFailWrites(boolean failWrites) {
            // 1. 更新用于模拟底层持久化故障的线程可见开关
            this.failWrites = failWrites;
        }

        /**
         * 获取指定标识对应的当前测试记录。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：从线程安全 Map 查询当前记录。</p>
         * <p>主要调用方：最新修订版本测试断言。</p>
         *
         * @param id 待查询测试记录标识
         * @return 当前持久化的测试记录，不存在时返回 null
         */
        private TestRecord get(String id) {
            // 1. 查询当前测试持久化介质中的指定记录
            return records.get(id);
        }

        /**
         * 判断测试持久化介质中是否存在指定记录。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：检查线程安全 Map 是否包含指定标识。</p>
         * <p>主要调用方：删除顺序测试断言。</p>
         *
         * @param id 待查询测试记录标识
         * @return 存在对应记录时返回 true
         */
        private boolean contains(String id) {
            // 1. 判断当前测试持久化介质是否包含指定记录
            return records.containsKey(id);
        }

        /**
         * 获取 write 方法的实际调用次数。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：读取线程安全尝试计数器。</p>
         * <p>主要调用方：失败重试次数测试断言。</p>
         *
         * @return 当前实际写入尝试次数
         */
        private int getWriteAttempts() {
            // 1. 返回当前累计的写入尝试次数
            return writeAttempts.get();
        }
    }

    /**
     * 表示写后队列测试使用的不可变持久化快照。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：保存标识、修订号和用于版本断言的名称。</p>
     * <p>主要调用方：WriteBehindPersistenceQueueTest。</p>
     * <p>约束：构造后全部字段保持不变。</p>
     */
    private static final class TestRecord implements PersistedRecord {

        /**
         * 当前测试快照的唯一记录标识。
         */
        private final String id;

        /**
         * 当前测试快照的递增修订号。
         */
        private final long revision;

        /**
         * 用于区分不同修订版本的测试名称。
         */
        private final String name;

        /**
         * 创建一个不可变队列测试快照。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：保存测试记录的标识、修订号和名称。</p>
         * <p>主要调用方：写后队列测试方法。</p>
         *
         * @param id       测试记录唯一标识
         * @param revision 测试记录修订号
         * @param name     测试记录名称
         */
        private TestRecord(String id, long revision, String name) {
            // 1. 保存构造阶段提供的全部不可变测试字段
            this.id = id;
            this.revision = revision;
            this.name = name;
        }

        /**
         * 获取测试记录唯一标识。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：返回构造阶段保存的标识。</p>
         * <p>主要调用方：异步队列、测试适配器和测试断言。</p>
         *
         * @return 测试记录唯一标识
         */
        @Override
        public String getId() {
            // 1. 返回不可变测试记录标识
            return id;
        }

        /**
         * 获取测试记录修订号。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：返回构造阶段保存的修订号。</p>
         * <p>主要调用方：异步队列和测试断言。</p>
         *
         * @return 测试记录修订号
         */
        @Override
        public long getRevision() {
            // 1. 返回不可变测试记录修订号
            return revision;
        }

        /**
         * 获取用于验证最新版本的测试名称。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：返回构造阶段保存的名称。</p>
         * <p>主要调用方：最新修订版本测试断言。</p>
         *
         * @return 测试记录名称
         */
        private String getName() {
            // 1. 返回不可变测试记录名称
            return name;
        }
    }
}
