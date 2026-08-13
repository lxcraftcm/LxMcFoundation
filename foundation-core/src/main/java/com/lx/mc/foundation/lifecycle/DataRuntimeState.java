package com.lx.mc.foundation.lifecycle;

/**
 * 定义单个插件数据运行时从初始化到关闭的状态。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-08-13</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>区分加载、正常、持久化降级、关闭中、失败和已关闭。</li>
 *     <li>为业务入口提供统一的缓存可用性判断。</li>
 * </ol>
 *
 * <p>主要调用方：DataRuntime 和业务插件入口守卫。</p>
 */
public enum DataRuntimeState {
    /** 正在准备目录并加载数据。 */
    INITIALIZING,
    /** 数据与缓存已就绪。 */
    READY,
    /** 持久化失败但权威内存缓存仍可用。 */
    DEGRADED,
    /** 正在拒绝新写入并执行最终刷盘。 */
    STOPPING,
    /** 初始化或运行期间发生不可安全恢复的错误。 */
    FAILED,
    /** 持久化队列与适配器已关闭。 */
    STOPPED;

    /**
     * 判断当前状态是否允许继续使用权威内存缓存。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：READY 和仅底层持久化降级的 DEGRADED 都保持业务可用。</p>
     * <p>主要调用方：命令、菜单和事件入口。</p>
     *
     * @return 缓存可安全支撑业务时返回 true
     */
    public boolean isBusinessAvailable() {
        // 1. 持久化暂时失败不回滚已成功发布的内存状态
        return this == READY || this == DEGRADED;
    }
}
