package com.lx.mc.foundation.persistence.json;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.lx.mc.foundation.persistence.ArchivableEntityPersistence;
import com.lx.mc.foundation.persistence.PersistedRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 使用“一个实体一个 JSON 文件”的方式实现阻塞式实体持久化。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 13:04</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>从实体命名空间目录读取并校验全部 JSON 快照。</li>
 *     <li>通过临时文件和原子替换安全写入单个实体。</li>
 *     <li>保留无法解析的数据文件，避免错误数据被空内容覆盖。</li>
 * </ol>
 *
 * <p>主要调用方：异步持久化队列和数据基础设施装配流程。</p>
 * <p>约束：所有公开 I/O 方法只能由持久化工作线程调用。</p>
 *
 * @param <T> 不可变持久化快照类型
 */
public final class JsonFileEntityPersistence<T extends PersistedRecord>
        implements ArchivableEntityPersistence<T> {

    /**
     * 限制实体标识只能包含可安全用于文件名的字符，防止目录穿越。
     */
    private static final Pattern SAFE_RECORD_ID = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * 限制命名空间为小写字母、数字、连字符和下划线，保证目录结构稳定。
     */
    private static final Pattern SAFE_NAMESPACE = Pattern.compile("[a-z0-9_-]+");

    /**
     * 当前实体类型的唯一持久化命名空间。
     */
    private final String namespace;

    /**
     * 当前实体类型全部 JSON 文件所在目录。
     */
    private final Path directory;

    /**
     * 用于反序列化 JSON 快照的具体记录类型。
     */
    private final Class<T> recordType;

    /**
     * 负责 JSON 序列化和反序列化的 Gson 实例。
     */
    private final Gson gson;

    /**
     * 创建指定实体类型的 JSON 文件持久化适配器。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>校验数据根目录、命名空间、记录类型和 Gson 实例。</li>
     *     <li>计算当前实体类型的独立数据目录。</li>
     * </ol>
     *
     * <p>主要调用方：数据基础设施装配流程。</p>
     *
     * @param dataRoot  全部业务数据的根目录，不能为空
     * @param namespace 当前实体类型的安全目录名，不能为空
     * @param recordType 当前 JSON 对应的具体记录类型，不能为空
     * @param gson       用于读写 JSON 的 Gson 实例，不能为空
     * @throws IllegalArgumentException 命名空间包含不安全字符时抛出
     */
    public JsonFileEntityPersistence(
            Path dataRoot,
            String namespace,
            Class<T> recordType,
            Gson gson
    ) {
        // 1. 校验构造参数，避免在首次 I/O 时才暴露装配错误
        Objects.requireNonNull(dataRoot, "dataRoot");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(recordType, "recordType");
        Objects.requireNonNull(gson, "gson");
        if (!SAFE_NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Unsafe persistence namespace: " + namespace);
        }

        // 2. 保存类型信息并计算当前命名空间的数据目录
        this.namespace = namespace;
        this.directory = dataRoot.resolve(namespace);
        this.recordType = recordType;
        this.gson = gson;
    }

    /**
     * 获取当前实体类型的持久化命名空间。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：返回构造阶段已经校验的稳定命名空间。</p>
     * <p>主要调用方：异步持久化队列。</p>
     *
     * @return 当前实体类型的持久化命名空间
     */
    @Override
    public String getNamespace() {
        // 1. 返回用于区分实体类型的稳定命名空间
        return namespace;
    }

    /**
     * 加载当前命名空间中的全部 JSON 快照。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>创建并扫描当前实体目录中的 JSON 文件。</li>
     *     <li>按文件名排序后逐个读取、解析和校验快照。</li>
     *     <li>遇到非法 JSON 时保留损坏副本并终止本次加载。</li>
     * </ol>
     *
     * <p>主要调用方：插件启动加载流程。</p>
     * <p>副作用：首次加载时创建数据目录，损坏数据会被转移为隔离副本。</p>
     *
     * @return 按文件名稳定排序的全部持久化快照
     * @throws IOException 创建目录、读取文件、解析数据或隔离损坏文件失败时抛出
     */
    @Override
    public List<T> loadAll() throws IOException {
        // 1. 确保当前实体的数据目录已经存在
        Files.createDirectories(directory);

        // 2. 收集全部 JSON 文件并按路径排序，保证启动加载顺序稳定
        List<Path> jsonFiles = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path path : stream) {
                jsonFiles.add(path);
            }
        }
        Collections.sort(jsonFiles);

        // 3. 逐个解析并校验文件名与记录标识，发现损坏数据时立即隔离
        List<T> records = new ArrayList<T>(jsonFiles.size());
        for (Path jsonFile : jsonFiles) {
            try {
                T record = readRecord(jsonFile);
                String fileId = removeJsonExtension(jsonFile.getFileName().toString());
                if (!fileId.equals(record.getId())) {
                    throw new JsonParseException(
                            "Record id does not match file name: " + jsonFile
                    );
                }
                records.add(record);
            } catch (JsonParseException | IllegalArgumentException exception) {
                Path quarantinedFile = quarantine(jsonFile);
                throw new IOException(
                        "Invalid JSON record moved to " + quarantinedFile,
                        exception
                );
            }
        }

        // 4. 返回与磁盘当前状态对应的完整快照列表
        return records;
    }

    /**
     * 使用原子文件替换方式写入单个 JSON 快照。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>校验快照标识和修订号。</li>
     *     <li>将快照序列化为 UTF-8 JSON 内容。</li>
     *     <li>写入临时文件并替换正式文件。</li>
     * </ol>
     *
     * <p>主要调用方：异步持久化队列。</p>
     * <p>副作用：新增或替换当前快照对应的 JSON 文件。</p>
     *
     * @param snapshot 待写入的不可变数据快照，不能为空
     * @throws IOException 创建目录、序列化或写入文件失败时抛出
     */
    @Override
    public void write(T snapshot) throws IOException {
        // 1. 校验快照及其持久化元数据
        Objects.requireNonNull(snapshot, "snapshot");
        validateRecordId(snapshot.getId());
        if (snapshot.getRevision() < 0L) {
            throw new IllegalArgumentException("Record revision cannot be negative");
        }

        // 2. 将完整快照序列化为 UTF-8 JSON 字节
        byte[] content;
        try {
            content = gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw new IOException("Cannot serialize JSON record: " + snapshot.getId(), exception);
        }

        // 3. 确保数据目录存在并使用临时文件原子替换正式文件
        Files.createDirectories(directory);
        writeAtomically(resolveRecordPath(snapshot.getId()), content);
    }

    /**
     * 删除指定标识对应的 JSON 文件。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：校验记录标识后删除对应文件，文件不存在时视为成功。</p>
     * <p>主要调用方：异步持久化队列。</p>
     * <p>副作用：删除当前记录对应的 JSON 文件。</p>
     *
     * @param id 待删除记录的唯一标识，不能为空
     * @throws IOException 删除文件失败时抛出
     */
    @Override
    public void delete(String id) throws IOException {
        // 1. 校验并解析目标记录路径，防止目录穿越
        Path recordPath = resolveRecordPath(id);

        // 2. 删除目标文件，记录不存在时保持幂等成功
        Files.deleteIfExists(recordPath);
    }

    /**
     * 将完整记录写入 archive 子目录后删除普通活跃文件。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-02</p>
     * <p>主要逻辑：校验标识、原子写入归档副本，确认成功后才删除普通记录。</p>
     * <p>主要调用方：共享箱子管理员强制解散归档流程。</p>
     */
    @Override
    public void archive(T snapshot, String archiveId) throws IOException {
        // 1. 校验快照与归档文件名，避免归档目录穿越
        Objects.requireNonNull(snapshot, "snapshot");
        validateRecordId(snapshot.getId());
        validateRecordId(archiveId);

        // 2. 先把完整 JSON 原子写入固定 archive 子目录
        byte[] content;
        try {
            content = gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw new IOException(
                    "Cannot serialize archived JSON record: " + snapshot.getId(),
                    exception
            );
        }
        Path archiveDirectory = directory.resolve("archive");
        Files.createDirectories(archiveDirectory);
        writeAtomically(
                archiveDirectory.resolve(archiveId + ".json"),
                content
        );

        // 3. 只有归档完整落盘后才删除活跃记录，失败时至少保留一份数据
        Files.deleteIfExists(resolveRecordPath(snapshot.getId()));
    }

    /**
     * 刷新 JSON 适配器内部缓冲内容。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：JSON 写入已在单次操作内强制落盘，因此无需额外处理。</p>
     * <p>主要调用方：插件停用和显式刷盘流程。</p>
     */
    @Override
    public void flush() {
        // 1. 单次 JSON 写入已执行强制落盘，不存在适配器级待刷新缓冲区
    }

    /**
     * 关闭 JSON 文件持久化适配器。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：本适配器不长期持有文件句柄，因此无需释放额外资源。</p>
     * <p>主要调用方：数据基础设施停用流程。</p>
     */
    @Override
    public void close() {
        // 1. 所有文件句柄均在单次操作内关闭，不存在长期持有的资源
    }

    /**
     * 读取并解析单个 JSON 文件。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：读取 UTF-8 文本、反序列化记录并校验基础元数据。</p>
     * <p>主要调用方：loadAll 方法。</p>
     *
     * @param jsonFile 待读取的 JSON 文件，不能为空
     * @return 解析并通过基础校验的记录快照
     * @throws IOException 读取文件失败时抛出
     * @throws JsonParseException JSON 无法解析或记录内容不合法时抛出
     */
    private T readRecord(Path jsonFile) throws IOException {
        // 1. 读取完整 UTF-8 JSON 内容
        String json = new String(Files.readAllBytes(jsonFile), StandardCharsets.UTF_8);

        // 2. 将 JSON 转换为指定记录类型
        T record = gson.fromJson(json, recordType);
        if (record == null) {
            throw new JsonParseException("JSON record cannot be null: " + jsonFile);
        }

        // 3. 校验记录标识和修订号的基础约束
        validateRecordId(record.getId());
        if (record.getRevision() < 0L) {
            throw new JsonParseException("Record revision cannot be negative: " + jsonFile);
        }
        return record;
    }

    /**
     * 将 JSON 内容安全写入正式记录文件。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>在同目录创建临时文件并强制写入完整内容。</li>
     *     <li>优先使用原子移动替换正式文件。</li>
     *     <li>文件系统不支持原子移动时退化为普通替换。</li>
     * </ol>
     *
     * <p>主要调用方：write 方法。</p>
     * <p>副作用：替换目标文件，并在失败时清理临时文件。</p>
     *
     * @param target  正式记录文件路径，不能为空
     * @param content 待写入的完整 UTF-8 JSON 字节，不能为空
     * @throws IOException 写入、强制落盘或移动文件失败时抛出
     */
    private void writeAtomically(Path target, byte[] content) throws IOException {
        // 1. 在正式文件同目录创建临时文件，确保后续移动位于同一文件系统
        Path targetDirectory = Objects.requireNonNull(
                target.getParent(),
                "target parent"
        );
        Files.createDirectories(targetDirectory);
        Path temporaryFile = Files.createTempFile(
                targetDirectory,
                ".ourcity3-",
                ".tmp"
        );
        boolean replaced = false;
        try {
            // 2. 写入完整内容并要求文件系统刷新文件数据
            try (FileChannel channel = FileChannel.open(
                    temporaryFile,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            // 3. 优先原子替换正式文件，不支持时使用同目录普通替换
            try {
                Files.move(
                        temporaryFile,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
            replaced = true;
        } finally {
            // 4. 仅在替换未完成时清理残留临时文件
            if (!replaced) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    /**
     * 计算指定记录标识对应的正式 JSON 文件路径。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：校验安全标识后拼接固定 JSON 扩展名。</p>
     * <p>主要调用方：write 和 delete 方法。</p>
     *
     * @param id 记录唯一标识，不能为空
     * @return 当前命名空间内的安全 JSON 文件路径
     * @throws IllegalArgumentException 记录标识包含不安全字符时抛出
     */
    private Path resolveRecordPath(String id) {
        // 1. 校验标识只能使用允许的文件名字符
        validateRecordId(id);

        // 2. 在固定命名空间目录内生成 JSON 文件路径
        return directory.resolve(id + ".json");
    }

    /**
     * 校验记录标识能否安全用于单个文件名。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：拒绝空值、目录标识和安全字符集合之外的内容。</p>
     * <p>主要调用方：读取、写入和删除路径解析流程。</p>
     *
     * @param id 待校验的记录唯一标识
     * @throws IllegalArgumentException 记录标识不符合安全规则时抛出
     */
    private void validateRecordId(String id) {
        // 1. 拒绝空标识、当前目录标识和上级目录标识
        if (id == null || id.isEmpty() || ".".equals(id) || "..".equals(id)) {
            throw new IllegalArgumentException("Record id cannot be empty or a directory marker");
        }

        // 2. 拒绝任何可能改变目录结构或生成非法文件名的字符
        if (!SAFE_RECORD_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Unsafe record id: " + id);
        }
    }

    /**
     * 将无法解析的 JSON 文件移动为带时间戳的损坏副本。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：生成不覆盖现有文件的隔离名称并移动原始数据。</p>
     * <p>主要调用方：loadAll 的损坏数据处理流程。</p>
     * <p>副作用：移动原始 JSON 文件以防后续空数据覆盖。</p>
     *
     * @param jsonFile 需要隔离的损坏 JSON 文件
     * @return 损坏文件移动后的实际路径
     * @throws IOException 无法移动损坏文件时抛出
     */
    private Path quarantine(Path jsonFile) throws IOException {
        // 1. 使用当前时间生成基础隔离文件名
        String baseName = jsonFile.getFileName().toString()
                + ".corrupt-"
                + System.currentTimeMillis();
        Path quarantinedFile = jsonFile.resolveSibling(baseName);

        // 2. 名称冲突时追加递增序号，避免覆盖之前保留的损坏数据
        int suffix = 1;
        while (Files.exists(quarantinedFile)) {
            quarantinedFile = jsonFile.resolveSibling(baseName + "-" + suffix);
            suffix++;
        }

        // 3. 将原始文件移动到隔离路径并返回实际位置
        return Files.move(jsonFile, quarantinedFile);
    }

    /**
     * 移除 JSON 文件名末尾的固定扩展名。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：删除扫描阶段已经确认存在的“.json”后缀。</p>
     * <p>主要调用方：loadAll 的文件名与记录标识校验流程。</p>
     *
     * @param fileName 包含 .json 扩展名的文件名
     * @return 不含扩展名的记录标识
     */
    private String removeJsonExtension(String fileName) {
        // 1. 移除目录扫描规则已经保证存在的固定 JSON 后缀
        return fileName.substring(0, fileName.length() - ".json".length());
    }
}
