package com.lx.mc.foundation.config;

import java.util.List;
import java.util.Map;

/**
 * 定义应用设置解析器使用的平台无关配置读取视图。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-08-13</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>以稳定路径读取基本配置类型。</li>
 *     <li>屏蔽 Bukkit、YAML 或后续其他配置来源。</li>
 *     <li>将字段默认值和业务校验保留给各插件的 Settings 类。</li>
 * </ol>
 *
 * <p>主要调用方：具体业务插件的不可变 Settings 工厂。</p>
 * <p>约束：实现不得在单次读取中隐式执行网络 I/O。</p>
 */
public interface ConfigView {

    /**
     * 读取可缺省的文本配置。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：将底层文本值或缺省状态返回给设置解析器。</p>
     * <p>主要调用方：Settings 工厂方法。</p>
     *
     * @param path 点分隔的配置路径，不能为空
     * @return 已配置文本，不存在时返回 null
     */
    String getString(String path);

    /**
     * 读取整数配置并在缺省时使用调用方默认值。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：以统一默认值语义读取 int。</p>
     * <p>主要调用方：Settings 工厂方法。</p>
     *
     * @param path 配置路径
     * @param defaultValue 缺省时使用的值
     * @return 配置值或默认值
     */
    int getInt(String path, int defaultValue);

    /**
     * 读取长整数配置并在缺省时使用调用方默认值。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：以统一默认值语义读取 long。</p>
     * <p>主要调用方：Settings 工厂方法。</p>
     *
     * @param path 配置路径
     * @param defaultValue 缺省时使用的值
     * @return 配置值或默认值
     */
    long getLong(String path, long defaultValue);

    /**
     * 读取小数配置并在缺省时使用调用方默认值。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：以统一默认值语义读取 double。</p>
     * <p>主要调用方：比例、系数等 Settings 工厂方法。</p>
     *
     * @param path 配置路径
     * @param defaultValue 缺省时使用的值
     * @return 配置值或默认值
     */
    double getDouble(String path, double defaultValue);

    /**
     * 读取布尔配置并在缺省时使用调用方默认值。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：以统一默认值语义读取 boolean。</p>
     * <p>主要调用方：Settings 工厂方法。</p>
     *
     * @param path 配置路径
     * @param defaultValue 缺省时使用的值
     * @return 配置值或默认值
     */
    boolean getBoolean(String path, boolean defaultValue);

    /**
     * 读取一组按原始顺序保留的整数。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：以不可修改列表返回当前配置快照。</p>
     * <p>主要调用方：倒计时、奖励档位等 Settings 工厂方法。</p>
     *
     * @param path 配置路径
     * @return 不可修改的整数列表，不存在时返回空列表
     */
    List<Integer> getIntegerList(String path);

    /**
     * 读取指定节点下的文本键值对。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：返回当前快照中的直接子键文本映射。</p>
     * <p>主要调用方：语言别名等小型结构化配置解析器。</p>
     *
     * @param path 配置节点路径
     * @return 不可修改的直接文本键值对
     */
    Map<String, String> getStringMap(String path);
}
