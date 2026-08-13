package com.lx.mc.foundation.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 使用进程内 LinkedHashMap 实现按插入顺序稳定遍历的实体缓存。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 13:04</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>在业务插件指定的单一所有者线程内维护实体键值。</li>
 *     <li>通过复制集合的方式向调用方提供只读快照。</li>
 * </ol>
 *
 * <p>主要调用方：具体业务实体仓储的基础设施实现。</p>
 * <p>约束：本实现不提供并发写保护，由业务插件保证单线程修改。</p>
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 */
public final class InMemoryCacheStore<K, V> implements CacheStore<K, V> {

    /**
     * 保存当前进程内的实体数据，并维持稳定的插入顺序。
     */
    private final Map<K, V> values;

    /**
     * 创建一个空的内存缓存。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：初始化用于保存实体的 LinkedHashMap。</p>
     * <p>主要调用方：数据基础设施装配流程和单元测试。</p>
     */
    public InMemoryCacheStore() {
        // 1. 初始化具有稳定遍历顺序的缓存容器
        this.values = new LinkedHashMap<K, V>();
    }

    /**
     * 按唯一标识查询缓存数据。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：校验键后返回对应缓存值。</p>
     * <p>主要调用方：领域仓储查询方法。</p>
     *
     * @param key 待查询的缓存键，不能为空
     * @return 包含缓存值的 Optional，不存在时返回空
     */
    @Override
    public Optional<V> find(K key) {
        // 1. 校验查询键，避免空键进入缓存访问流程
        Objects.requireNonNull(key, "key");

        // 2. 查询当前值并使用 Optional 表达不存在状态
        return Optional.ofNullable(values.get(key));
    }

    /**
     * 新增或替换指定缓存数据。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：校验键值后写入内存容器。</p>
     * <p>主要调用方：领域仓储保存方法和启动数据安装流程。</p>
     * <p>副作用：修改当前内存缓存。</p>
     *
     * @param key   待写入的缓存键，不能为空
     * @param value 待写入的缓存值，不能为空
     */
    @Override
    public void put(K key, V value) {
        // 1. 校验键和值，保证缓存中不存在空元素
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        // 2. 新增或覆盖指定缓存数据
        values.put(key, value);
    }

    /**
     * 删除指定缓存数据并返回删除前的值。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：校验键后移除并封装原缓存值。</p>
     * <p>主要调用方：领域仓储删除方法。</p>
     * <p>副作用：修改当前内存缓存。</p>
     *
     * @param key 待删除的缓存键，不能为空
     * @return 包含删除前缓存值的 Optional，不存在时返回空
     */
    @Override
    public Optional<V> remove(K key) {
        // 1. 校验删除键，避免空键进入缓存访问流程
        Objects.requireNonNull(key, "key");

        // 2. 删除数据并使用 Optional 表达原值是否存在
        return Optional.ofNullable(values.remove(key));
    }

    /**
     * 获取当前全部缓存值的只读快照。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：复制当前值集合并阻止调用方修改集合结构。</p>
     * <p>主要调用方：领域仓储批量查询和停服刷盘流程。</p>
     *
     * @return 与调用时缓存状态对应的只读集合快照
     */
    @Override
    public Collection<V> snapshot() {
        // 1. 复制当前缓存值，隔离调用方与内部容器
        Collection<V> snapshot = new ArrayList<V>(values.values());

        // 2. 返回不可修改的集合视图
        return Collections.unmodifiableCollection(snapshot);
    }

    /**
     * 使用一组完整数据替换当前缓存内容。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：完整校验输入后一次性替换当前缓存。</p>
     * <p>主要调用方：插件启动数据加载流程。</p>
     * <p>副作用：替换当前内存缓存中的全部数据。</p>
     *
     * @param newValues 需要安装的完整键值集合，不能为空且不能包含空键或空值
     */
    @Override
    public void replaceAll(Map<K, V> newValues) {
        // 1. 校验输入集合及其全部键值，避免部分替换后才发现非法数据
        Objects.requireNonNull(newValues, "newValues");
        for (Map.Entry<K, V> entry : newValues.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "newValues contains null key");
            Objects.requireNonNull(entry.getValue(), "newValues contains null value");
        }

        // 2. 清空旧缓存并整体安装启动数据
        values.clear();
        values.putAll(newValues);
    }

    /**
     * 清空当前缓存中的全部数据。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：移除内存容器中的全部键值。</p>
     * <p>主要调用方：插件关闭和测试清理流程。</p>
     * <p>副作用：清空当前内存缓存。</p>
     */
    @Override
    public void clear() {
        // 1. 清空当前缓存持有的全部数据
        values.clear();
    }

    /**
     * 获取当前缓存实体数量。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：读取内存容器当前大小。</p>
     * <p>主要调用方：状态监控和测试断言。</p>
     *
     * @return 当前缓存实体数量
     */
    @Override
    public int size() {
        // 1. 返回当前缓存容器中的实体数量
        return values.size();
    }
}
