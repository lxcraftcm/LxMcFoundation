package com.lx.mc.foundation.persistence;

/**
 * 定义可以由写后队列按实体修订顺序执行的自定义阻塞持久化操作。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-02</p>
 * <p>主要逻辑：允许归档等复合 I/O 复用写入、删除相同的合并、重试和脏状态协议。</p>
 * <p>主要调用方：WriteBehindPersistenceQueue 和共享箱子归档仓储。</p>
 */
@FunctionalInterface
public interface PersistenceOperation {

    /**
     * 执行一次阻塞式持久化操作。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-02</p>
     * <p>主要逻辑：由专用持久化线程调用具体文件或数据库操作。</p>
     * <p>主要调用方：WriteBehindPersistenceQueue 后台工作线程。</p>
     *
     * @throws Exception 底层持久化失败时抛出
     */
    void execute() throws Exception;
}
