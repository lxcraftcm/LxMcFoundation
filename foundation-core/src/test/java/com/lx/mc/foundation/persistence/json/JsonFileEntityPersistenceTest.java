package com.lx.mc.foundation.persistence.json;

import com.google.gson.GsonBuilder;
import com.lx.mc.foundation.persistence.PersistedRecord;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 验证 JSON 文件适配器的数据往返、幂等删除和损坏文件隔离行为。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 13:04</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>在临时目录写入、加载和删除测试快照。</li>
 *     <li>构造非法 JSON 并验证原始数据不会被覆盖。</li>
 * </ol>
 *
 * <p>主要调用方：Maven Surefire 测试执行器。</p>
 */
public final class JsonFileEntityPersistenceTest {

    /**
     * 为每个测试方法提供自动清理的独立文件系统目录。
     */
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    /**
     * 验证单个 JSON 快照可以安全写入、重新加载和删除。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>创建临时 JSON 适配器并写入测试记录。</li>
     *     <li>重新加载记录并核对全部字段。</li>
     *     <li>删除记录并确认重复删除保持幂等。</li>
     * </ol>
     *
     * <p>主要调用方：JUnit 测试执行器。</p>
     *
     * @throws Exception 创建临时目录或执行文件 I/O 失败时抛出
     */
    @Test
    public void shouldWriteLoadAndDeleteRecord() throws Exception {
        // 1. 创建 JSON 适配器并写入单个不可变测试快照
        Path dataRoot = temporaryFolder.newFolder("data").toPath();
        JsonFileEntityPersistence<TestRecord> persistence = createPersistence(dataRoot);
        TestRecord expected = new TestRecord("entity-1", 3L, "initial");
        persistence.write(expected);
        assertTrue(Files.exists(dataRoot.resolve("records/entity-1.json")));

        // 2. 从磁盘重新加载并核对记录标识、修订号和业务字段
        List<TestRecord> records = persistence.loadAll();
        assertEquals(1, records.size());
        TestRecord actual = records.get(0);
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getRevision(), actual.getRevision());
        assertEquals(expected.getName(), actual.getName());

        // 3. 删除记录并确认目标文件消失且重复删除不会失败
        persistence.delete(expected.getId());
        persistence.delete(expected.getId());
        assertFalse(Files.exists(dataRoot.resolve("records/entity-1.json")));
        assertTrue(persistence.loadAll().isEmpty());
    }

    /**
     * 验证非法 JSON 会被保留为损坏副本并使启动加载失败。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>在实体目录中写入无法解析的 JSON 文件。</li>
     *     <li>执行加载并确认抛出 IOException。</li>
     *     <li>确认正式文件被移动为带时间戳的损坏副本。</li>
     * </ol>
     *
     * <p>主要调用方：JUnit 测试执行器。</p>
     *
     * @throws Exception 创建临时目录或执行文件 I/O 失败时抛出
     */
    @Test
    public void shouldQuarantineCorruptJson() throws Exception {
        // 1. 创建实体目录并写入无法解析的 JSON 内容
        Path dataRoot = temporaryFolder.newFolder("corrupt-data").toPath();
        Path namespaceDirectory = dataRoot.resolve("records");
        Files.createDirectories(namespaceDirectory);
        Path corruptFile = namespaceDirectory.resolve("entity-1.json");
        Files.write(
                corruptFile,
                "{invalid-json".getBytes(StandardCharsets.UTF_8)
        );
        JsonFileEntityPersistence<TestRecord> persistence = createPersistence(dataRoot);

        // 2. 执行加载并确认适配器拒绝使用损坏数据继续启动
        IOException failure = null;
        try {
            persistence.loadAll();
            fail("Expected corrupt JSON to fail loading");
        } catch (IOException exception) {
            failure = exception;
        }
        assertNotNull(failure);
        assertFalse(Files.exists(corruptFile));

        // 3. 扫描目录并确认原始数据被保留为唯一损坏副本
        int quarantinedFiles = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                namespaceDirectory,
                "entity-1.json.corrupt-*"
        )) {
            for (Path ignored : stream) {
                quarantinedFiles++;
            }
        }
        assertEquals(1, quarantinedFiles);
    }

    /**
     * 验证归档完整写入后才移除普通活跃记录。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-02</p>
     * <p>主要逻辑：写入活跃记录，执行带时间标识的归档并核对两个路径状态。</p>
     * <p>主要调用方：JUnit 测试执行器。</p>
     *
     * @throws Exception 创建临时目录或执行文件 I/O 失败时抛出
     */
    @Test
    public void shouldArchiveBeforeRemovingActiveRecord() throws Exception {
        // 1. 创建并写入一条可正常加载的活跃记录
        Path dataRoot = temporaryFolder.newFolder("archive-data").toPath();
        JsonFileEntityPersistence<TestRecord> persistence =
                createPersistence(dataRoot);
        TestRecord record = new TestRecord("entity-2", 4L, "archive");
        persistence.write(record);

        // 2. 归档后活跃文件消失，带独立标识的完整 JSON 保留
        persistence.archive(record, "entity-2-1000-r4");
        assertFalse(Files.exists(dataRoot.resolve(
                "records/entity-2.json"
        )));
        assertTrue(Files.exists(dataRoot.resolve(
                "records/archive/entity-2-1000-r4.json"
        )));
        assertTrue(persistence.loadAll().isEmpty());
    }

    /**
     * 创建使用美化 JSON 输出的测试持久化适配器。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：将测试根目录、固定命名空间、记录类型和 Gson 组合为适配器。</p>
     * <p>主要调用方：当前测试类的 JSON 行为测试方法。</p>
     *
     * @param dataRoot 测试使用的临时业务数据根目录
     * @return 针对 TestRecord 的 JSON 文件持久化适配器
     */
    private JsonFileEntityPersistence<TestRecord> createPersistence(Path dataRoot) {
        // 1. 使用固定测试类型和美化输出创建 JSON 文件适配器
        return new JsonFileEntityPersistence<TestRecord>(
                dataRoot,
                "records",
                TestRecord.class,
                new GsonBuilder().setPrettyPrinting().create()
        );
    }

    /**
     * 表示 JSON 适配器测试使用的不可变持久化快照。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：保存标识、修订号和用于往返校验的名称。</p>
     * <p>主要调用方：JsonFileEntityPersistenceTest。</p>
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
         * 用于验证 JSON 业务字段往返的名称。
         */
        private final String name;

        /**
         * 创建一个不可变测试快照。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：保存测试记录的标识、修订号和名称。</p>
         * <p>主要调用方：JSON 适配器测试方法和 Gson 反序列化。</p>
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
         * <p>主要调用方：JSON 适配器和测试断言。</p>
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
         * <p>主要调用方：JSON 适配器和测试断言。</p>
         *
         * @return 测试记录修订号
         */
        @Override
        public long getRevision() {
            // 1. 返回不可变测试记录修订号
            return revision;
        }

        /**
         * 获取用于验证 JSON 往返的测试名称。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：返回构造阶段保存的名称。</p>
         * <p>主要调用方：JSON 适配器测试断言。</p>
         *
         * @return 测试记录名称
         */
        private String getName() {
            // 1. 返回不可变测试记录名称
            return name;
        }
    }
}
