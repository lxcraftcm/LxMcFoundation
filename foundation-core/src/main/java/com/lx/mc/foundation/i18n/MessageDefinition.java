package com.lx.mc.foundation.i18n;

/**
 * 定义业务插件枚举需要提供的稳定消息元数据。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-08-13</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>由每个业务插件的枚举声明 YAML 路径。</li>
 *     <li>由业务插件决定不依赖自然语言的消息前缀样式。</li>
 *     <li>公共库只渲染元数据，不知道小镇、投票或清理业务。</li>
 * </ol>
 *
 * <p>主要调用方：MessageService 和 Bukkit YAML 语言目录加载器。</p>
 */
public interface MessageDefinition {

    /**
     * 获取当前消息在语言 YAML 中的稳定路径。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：返回由具体业务枚举声明的路径。</p>
     * <p>主要调用方：语言目录和消息渲染服务。</p>
     *
     * @return 非空的点分隔消息路径
     */
    String getPath();

    /**
     * 获取模板保留 `{prefix}` 占位符对应的受信任样式。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：将固定图标和颜色留在代码内，不从玩家输入构造。</p>
     * <p>主要调用方：MessageService。</p>
     *
     * @return 可交给 MessageColorizer 的前缀模板；不需要前缀时返回空字符串
     */
    String getPrefixTemplate();
}
