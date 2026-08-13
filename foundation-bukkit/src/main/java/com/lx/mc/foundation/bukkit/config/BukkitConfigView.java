package com.lx.mc.foundation.bukkit.config;

import com.lx.mc.foundation.config.ConfigView;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 Bukkit FileConfiguration 适配为平台无关的 ConfigView。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：委托基本类型读取，并将配置节点复制为不可修改文本映射。</p>
 * <p>主要调用方：Bukkit 业务插件的 Settings 解析流程。</p>
 * <p>约束：应在 Bukkit 主线程创建并完成 Settings 快照解析。</p>
 */
public final class BukkitConfigView implements ConfigView {

    /** 当前 Bukkit 已加载的配置对象。 */
    private final FileConfiguration configuration;

    /**
     * 创建指向当前 Bukkit 配置的读取视图。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：校验并保存 Bukkit 配置引用。</p>
     * <p>主要调用方：插件 onEnable 与配置重载流程。</p>
     *
     * @param configuration Bukkit 已加载的配置
     */
    public BukkitConfigView(FileConfiguration configuration) {
        // 1. 配置读取端口不允许在运行期指向空对象
        this.configuration = Objects.requireNonNull(
                configuration,
                "configuration"
        );
    }

    /**
     * 读取可缺省的文本配置。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：将文本读取委托给 FileConfiguration。</p>
     * <p>主要调用方：Settings 工厂。</p>
     *
     * @param path 配置路径
     * @return 文本值或 null
     */
    @Override
    public String getString(String path) {
        // 1. Bukkit 自行处理缺失路径的 null 语义
        return configuration.getString(
                Objects.requireNonNull(path, "path")
        );
    }

    /**
     * 读取整数配置。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：委托 Bukkit 的带默认值整数读取。</p>
     * <p>主要调用方：Settings 工厂。</p>
     *
     * @param path 配置路径
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    @Override
    public int getInt(String path, int defaultValue) {
        // 1. 默认值由具体业务 Settings 决定
        return configuration.getInt(
                Objects.requireNonNull(path, "path"),
                defaultValue
        );
    }

    /**
     * 读取长整数配置。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：委托 Bukkit 的带默认值长整数读取。</p>
     * <p>主要调用方：Settings 工厂。</p>
     *
     * @param path 配置路径
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    @Override
    public long getLong(String path, long defaultValue) {
        // 1. 默认值由具体业务 Settings 决定
        return configuration.getLong(
                Objects.requireNonNull(path, "path"),
                defaultValue
        );
    }

    /**
     * 读取小数配置。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：委托 Bukkit 的带默认值小数读取。</p>
     * <p>主要调用方：Settings 工厂。</p>
     *
     * @param path 配置路径
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    @Override
    public double getDouble(String path, double defaultValue) {
        // 1. 默认值由具体业务 Settings 决定
        return configuration.getDouble(
                Objects.requireNonNull(path, "path"),
                defaultValue
        );
    }

    /**
     * 读取布尔配置。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：委托 Bukkit 的带默认值布尔读取。</p>
     * <p>主要调用方：Settings 工厂。</p>
     *
     * @param path 配置路径
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    @Override
    public boolean getBoolean(String path, boolean defaultValue) {
        // 1. 默认值由具体业务 Settings 决定
        return configuration.getBoolean(
                Objects.requireNonNull(path, "path"),
                defaultValue
        );
    }

    /**
     * 读取指定路径的整数列表。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：复制 Bukkit 列表并返回不可修改快照。</p>
     * <p>主要调用方：Settings 工厂。</p>
     *
     * @param path 配置路径
     * @return 不可修改的整数列表
     */
    @Override
    public List<Integer> getIntegerList(String path) {
        // 1. 隔离 Bukkit 返回的可变列表，避免 Settings 快照被外部修改
        return Collections.unmodifiableList(
                configuration.getIntegerList(
                        Objects.requireNonNull(path, "path")
                )
        );
    }

    /**
     * 读取指定节点的直接文本键值对。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>定位目标节点。</li>
     *     <li>忽略非文本子值。</li>
     *     <li>返回不可修改副本。</li>
     * </ol>
     * <p>主要调用方：语言别名 Settings 解析。</p>
     *
     * @param path 配置节点路径
     * @return 不可修改文本映射
     */
    @Override
    public Map<String, String> getStringMap(String path) {
        // 1. 节点缺失时使用可安全遍历的空映射
        ConfigurationSection section = configuration.getConfigurationSection(
                Objects.requireNonNull(path, "path")
        );
        if (section == null) {
            return Collections.emptyMap();
        }

        // 2. 只复制直接文本子值，不向上层暴露 Bukkit 节点对象
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null) {
                values.put(key, value);
            }
        }

        // 3. Settings 解析器不能反向修改底层配置
        return Collections.unmodifiableMap(values);
    }
}
