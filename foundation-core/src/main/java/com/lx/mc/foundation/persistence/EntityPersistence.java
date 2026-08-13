package com.lx.mc.foundation.persistence;

import java.io.IOException;
import java.util.List;

/**
 * 定义单类实体的阻塞式持久化操作，并隔离 JSON 或数据库实现细节。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 13:04</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>加载指定命名空间中的全部数据快照。</li>
 *     <li>按唯一标识写入或删除单条数据。</li>
 *     <li>在关闭前刷新并释放持久化资源。</li>
 * </ol>
 *
 * <p>主要调用方：异步持久化队列和数据基础设施装配流程。</p>
 * <p>约束：接口方法执行阻塞 I/O，只能由持久化工作线程调用。</p>
 *
 * @param <T> 不可变持久化快照类型
 */
public interface EntityPersistence<T extends PersistedRecord> extends AutoCloseable {

    /**
     * 获取当前实体类型的唯一持久化命名空间。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：返回用于区分实体类型和任务键的稳定名称。</p>
     * <p>主要调用方：异步持久化队列。</p>
     *
     * @return 非空且稳定的持久化命名空间
     */
    String getNamespace();

    /**
     * 加载当前命名空间中的全部持久化快照。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：读取、解析并校验当前实体类型的全部数据。</p>
     * <p>主要调用方：插件启动加载流程。</p>
     *
     * @return 当前持久化介质中存在的全部快照
     * @throws IOException 读取、解析或校验数据失败时抛出
     */
    List<T> loadAll() throws IOException;

    /**
     * 写入单个不可变数据快照。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：按照快照标识新增或替换持久化记录。</p>
     * <p>主要调用方：异步持久化队列。</p>
     * <p>副作用：修改文件或数据库中的持久化数据。</p>
     *
     * @param snapshot 待写入的不可变数据快照，不能为空
     * @throws IOException 写入数据失败时抛出
     */
    void write(T snapshot) throws IOException;

    /**
     * 删除指定唯一标识对应的持久化记录。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：定位并删除指定持久化记录。</p>
     * <p>主要调用方：异步持久化队列。</p>
     * <p>副作用：删除文件或数据库中的持久化数据。</p>
     *
     * @param id 待删除记录的唯一标识，不能为空
     * @throws IOException 删除数据失败时抛出
     */
    void delete(String id) throws IOException;

    /**
     * 将适配器内部尚未提交的内容刷新到底层介质。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：提交适配器内部缓冲区中的待写内容。</p>
     * <p>主要调用方：插件停用和显式刷盘流程。</p>
     * <p>副作用：可能执行文件或数据库 I/O。</p>
     *
     * @throws IOException 刷新失败时抛出
     */
    void flush() throws IOException;

    /**
     * 关闭持久化适配器并释放其持有的资源。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：关闭文件、连接池或其他底层资源。</p>
     * <p>主要调用方：数据基础设施停用流程。</p>
     * <p>副作用：关闭后适配器不再接受新的读写操作。</p>
     *
     * @throws IOException 关闭资源失败时抛出
     */
    @Override
    void close() throws IOException;
}
