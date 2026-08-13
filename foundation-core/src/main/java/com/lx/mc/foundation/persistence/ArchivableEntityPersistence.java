package com.lx.mc.foundation.persistence;

import java.io.IOException;

/**
 * 为需要保留最终快照的实体扩展阻塞式原子归档能力。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-02</p>
 * <p>主要逻辑：先保存带独立名称的归档快照，再删除普通活跃记录。</p>
 * <p>主要调用方：共享箱子缓存仓储和 JSON 文件适配器。</p>
 *
 * @param <T> 不可变持久化快照类型
 */
public interface ArchivableEntityPersistence<T extends PersistedRecord>
        extends EntityPersistence<T> {

    /**
     * 将当前快照归档并移除其普通活跃记录。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-02</p>
     * <p>主要逻辑：归档写入完整成功后才删除普通记录。</p>
     * <p>主要调用方：共享箱子专用持久化队列。</p>
     *
     * @param snapshot 待归档完整快照
     * @param archiveId 不覆盖其他归档的安全文件标识
     * @throws IOException 归档写入或普通记录删除失败时抛出
     */
    void archive(T snapshot, String archiveId) throws IOException;
}
